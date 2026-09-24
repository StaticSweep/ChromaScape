package com.chromascape.web.debug.tickdebugger.websocket;

import com.chromascape.web.aiplayer.prototype.FishingShadowDecision;
import com.chromascape.web.aiplayer.prototype.FishingShadowDecisionService;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartRequest;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.dto.SessionStopRequest;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService.SessionStartOutcome;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService.SessionStartRollbackToken;
import com.chromascape.web.debug.tickdebugger.service.TickBatchCanonicalChecksumService;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService.TickBatchAcknowledgment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket handler for receiving tick batch data from Micro6 client.
 *
 * <p>This handler processes three message types:
 *
 * <ul>
 *   <li>SESSION_START: Initialize a new debug session
 *   <li>TICK_BATCH: Per-tick data containing logs, state, and overlays
 *   <li>SESSION_STOP: Finalize the debug session
 * </ul>
 *
 * <p>Endpoint: {@code /ws/micro6/tick-stream}
 *
 * <p>Each Micro6 WebSocket owns at most one canonical debug session, and each canonical debug
 * session has at most one Micro6 WebSocket owner. Ownership is tracked in {@code sessionBindings}
 * (websocket id to binding) and {@code sessionOwners} (canonical session id to websocket id), while
 * disconnected active sessions are reserved in {@code pendingReconnects}; all three mappings are
 * mutated only under {@code ownershipLock}. Per-WebSocket callbacks are serialized by synchronizing
 * on the WebSocket session, which also covers transport-error and close handling. The lock order is
 * {@code WebSocketSession monitor → ownershipLock} and is never reversed.
 *
 * <p>Session start is transactional: the preferred session id is reserved under {@code
 * ownershipLock} before the (potentially slow) service call, the service call runs outside the
 * lock, and the reservation is converted into canonical ownership before the binding is committed.
 * A failed start is compensated through {@link DebugSessionService#rollbackSessionStart}; when
 * compensation fails the binding and ownership are retained so the close callback performs exactly
 * one guarded retry. Only an {@link BindingPhase#ACTIVE} binding accepts ticks.
 *
 * <p>Protocol rejections receive machine-readable {@code ERROR} responses ({@code
 * SESSION_ALREADY_BOUND}, {@code SESSION_ALREADY_OWNED}, {@code SESSION_NOT_BOUND}, {@code
 * SESSION_STOPPING}, {@code SESSION_ID_MISMATCH}).
 */
@Component
public class TickStreamWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(TickStreamWebSocketHandler.class);

  private final ObjectMapper objectMapper;
  private final DebugSessionService sessionService;
  private final TickStreamIngestionService ingestionService;
  private final DebuggerTickWebSocketHandler debuggerWebSocketHandler;
  private final FishingShadowDecisionService shadowDecisionService;
  private final TickBatchCanonicalChecksumService tickBatchChecksumService;
  private final Set<WebSocketSession> micro6Clients = ConcurrentHashMap.newKeySet();

  /** Binding phase of a WebSocket-bound debug session. */
  enum BindingPhase {
    /** The start transaction is in progress (reserved/owned, shadow run not yet open). */
    STARTING,
    /** The bound session accepts ticks. */
    ACTIVE,
    /** Shadow-run open (or pre-commit work) failed and compensation failed; close may retry. */
    START_ROLLBACK_FAILED,
    /** The success response could not be delivered and compensation failed; close may retry. */
    START_RESPONSE_FAILED,
    /** An explicit SESSION_STOP was attempted but finalization failed; close may retry. */
    EXPLICIT_STOP_FAILED
  }

  /**
   * One WebSocket's ownership of a debug session.
   *
   * @param sessionId the canonical debug session id
   * @param shadowRunId the shadow run id opened for this binding
   * @param phase whether the connection still accepts ticks
   * @param shadowOpened whether the shadow run was opened successfully
   * @param rollbackToken describes the exact mutations a failed start must compensate
   */
  record SessionBinding(
      String sessionId,
      String shadowRunId,
      BindingPhase phase,
      boolean shadowOpened,
      SessionStartRollbackToken rollbackToken,
      Boolean recordingEnabled,
      String recordingId,
      Long startTick,
      String recordingFailureReason) {

    SessionBinding withPhase(BindingPhase newPhase) {
      return new SessionBinding(
          sessionId,
          shadowRunId,
          newPhase,
          shadowOpened,
          rollbackToken,
          recordingEnabled,
          recordingId,
          startTick,
          recordingFailureReason);
    }
  }

  /** An active binding detached from a dead socket while Micro6 is allowed to reconnect. */
  private static final class PendingReconnect {
    private final String token = UUID.randomUUID().toString();
    private final SessionBinding binding;
    private final String cause;
    private volatile ScheduledFuture<?> timeout;

    private PendingReconnect(SessionBinding binding, String cause) {
      this.binding = binding;
      this.cause = cause;
    }
  }

  /**
   * Maps WebSocket session IDs to bound debug session ownership. Populated on successful
   * SESSION_START, detached during reconnect grace, and cleared on successful explicit SESSION_STOP
   * or final disconnect finalization. Mutated only under {@code ownershipLock}.
   */
  private final Map<String, SessionBinding> sessionBindings = new ConcurrentHashMap<>();

  /**
   * Maps canonical debug session IDs to the WebSocket session ID that owns them. Enforces exclusive
   * ownership of a preferred session across connections. Mutated only under {@code ownershipLock}.
   */
  private final Map<String, String> sessionOwners = new ConcurrentHashMap<>();

  /**
   * Maps a preferred session id to the WebSocket session id that reserved it while a SESSION_START
   * transaction is in flight. Prevents two sockets from racing to start the same preferred session
   * without holding the ownership lock during recording startup. Mutated only under {@code
   * ownershipLock}.
   */
  private final Map<String, String> preferredReservations = new ConcurrentHashMap<>();

  /** Active sessions awaiting a replacement socket, keyed by canonical debug session id. */
  private final Map<String, PendingReconnect> pendingReconnects = new ConcurrentHashMap<>();

  private final ScheduledExecutorService reconnectScheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "tick-stream-reconnect-grace");
            thread.setDaemon(true);
            return thread;
          });

  @Value("${debug.tick-stream.reconnect-grace-ms:30000}")
  private long reconnectGraceMillis = 30_000L;

  /**
   * Serializes session creation, canonical ownership claims, and ownership release. Acquired only
   * after the owning WebSocket session monitor.
   */
  private final Object ownershipLock = new Object();

  public TickStreamWebSocketHandler(
      ObjectMapper objectMapper,
      DebugSessionService sessionService,
      TickStreamIngestionService ingestionService,
      DebuggerTickWebSocketHandler debuggerWebSocketHandler,
      FishingShadowDecisionService shadowDecisionService) {
    this(
        objectMapper,
        sessionService,
        ingestionService,
        debuggerWebSocketHandler,
        shadowDecisionService,
        null);
  }

  /** Spring constructor keeps one canonical parser/writer for the WebSocket boundary. */
  @org.springframework.beans.factory.annotation.Autowired
  public TickStreamWebSocketHandler(
      ObjectMapper objectMapper,
      DebugSessionService sessionService,
      TickStreamIngestionService ingestionService,
      DebuggerTickWebSocketHandler debuggerWebSocketHandler,
      FishingShadowDecisionService shadowDecisionService,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          TickBatchCanonicalChecksumService tickBatchChecksumService) {
    this.objectMapper = objectMapper;
    this.sessionService = sessionService;
    this.ingestionService = ingestionService;
    this.debuggerWebSocketHandler = debuggerWebSocketHandler;
    this.shadowDecisionService = shadowDecisionService;
    this.tickBatchChecksumService =
        tickBatchChecksumService != null
            ? tickBatchChecksumService
            : new TickBatchCanonicalChecksumService(objectMapper);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    log.info("Micro6 tick stream client connected: {}", session.getId());
    micro6Clients.add(session);
    super.afterConnectionEstablished(session);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    // Serialize all lifecycle handling for this WebSocket so binding transitions, tick validation,
    // transport errors, and close handling can never interleave.
    synchronized (session) {
      try {
        JsonNode root = tickBatchChecksumService.parseWireJson(message.getPayload());
        String type = root.has("type") ? root.get("type").asText() : "";

        switch (type) {
          case "SESSION_START":
            handleSessionStart(session, root);
            break;
          case "TICK_BATCH":
            handleTickBatch(session, root);
            break;
          case "SESSION_STOP":
            handleSessionStop(session, root);
            break;
          default:
            log.warn("Unknown message type: {}", type);
        }
      } catch (Exception e) {
        log.error("Error processing tick stream message: {}", e.getMessage(), e);
      }
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    synchronized (session) {
      log.info("Micro6 tick stream client disconnected: {}, status: {}", session.getId(), status);
      // Finalize first, then clean up — ensures binding is consumed before client removal.
      finalizeOnDisconnect(session);
      micro6Clients.remove(session);
      super.afterConnectionClosed(session, status);
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    synchronized (session) {
      log.error(
          "Tick stream transport error for session {}: {}",
          session.getId(),
          exception.getMessage(),
          exception);
      finalizeOnTransportError(session);
      super.handleTransportError(session, exception);
    }
  }

  /**
   * Handle SESSION_START message from Micro6.
   *
   * <p>Session start is transactional: the preferred session id is reserved under the ownership
   * lock, the authoritative service call runs outside the lock, and the reservation is converted
   * into canonical ownership before a {@code STARTING} binding is installed. The shadow run opens
   * before the binding becomes {@code ACTIVE}, and a response is written only after commit. A
   * failure before the success response is delivered is compensated via the unified cleanup path,
   * which retains ownership when compensation fails so the close callback can retry exactly once.
   */
  private void handleSessionStart(WebSocketSession wsSession, JsonNode root) {
    // Single-response guard: once the success response is written, any later exception in this
    // handler must not emit a second (ERROR) response to the same SESSION_START.
    boolean successResponseSent = false;
    String wsId = wsSession.getId();
    try {
      SessionStartRequest request = objectMapper.treeToValue(root, SessionStartRequest.class);
      String clientRequestId = request.getClientRequestId();
      log.info(
          "Starting debug session via WebSocket: account={}, script={}, recording={},"
              + " clientRequestId={}",
          request.getAccountName(),
          request.getScriptName(),
          request.getRecordingEnabled(),
          clientRequestId != null ? clientRequestId : "none");

      if (tryResumePendingSession(wsSession, request)) {
        return;
      }

      // A WebSocket owns at most one session: reject a second SESSION_START on this connection,
      // whether it already has a binding or a start reservation is still in flight.
      if (sessionBindings.containsKey(wsId) || hasStartReservation(wsId)) {
        log.warn("Rejecting second SESSION_START on connection {}: already bound/reserved", wsId);
        sendErrorResponse(
            wsSession,
            "SESSION_ALREADY_BOUND",
            "This connection already owns a debug session",
            clientRequestId);
        return;
      }

      boolean recordingEnabled = Boolean.TRUE.equals(request.getRecordingEnabled());
      String preferred = request.getPreferredSessionId();
      String shadowRunId = "shadow_" + UUID.randomUUID().toString().replace("-", "");

      // Reserve the preferred session id under the ownership lock, then release the lock before
      // the (potentially slow) service call. This prevents two sockets from racing to start the
      // same preferred session without holding the global lock during recording startup.
      // Rejections are decided under the lock but the error is sent outside it, so the global
      // lock is never held during a blocking network send.
      String reservationRejection = null;
      synchronized (ownershipLock) {
        if (sessionBindings.containsKey(wsId) || hasStartReservation(wsId)) {
          reservationRejection = "SESSION_ALREADY_BOUND";
        } else if (preferred != null && !preferred.isBlank()) {
          String owner = sessionOwners.get(preferred);
          String reserver = preferredReservations.get(preferred);
          if ((owner != null && !owner.equals(wsId))
              || (reserver != null && !reserver.equals(wsId))) {
            log.warn(
                "Rejecting SESSION_START on connection {}: preferred session {} owned/reserved by"
                    + " another connection",
                wsId,
                preferred);
            reservationRejection = "SESSION_ALREADY_OWNED";
          } else {
            preferredReservations.put(preferred, wsId);
          }
        }
      }
      if (reservationRejection != null) {
        sendErrorResponse(
            wsSession,
            reservationRejection,
            "SESSION_ALREADY_OWNED".equals(reservationRejection)
                ? "The requested debug session belongs to another connection"
                : "This connection already owns a debug session",
            clientRequestId);
        return;
      }

      // Call the authoritative service outside the ownership lock.
      SessionStartOutcome outcome;
      try {
        outcome =
            sessionService.startSessionWithOutcome(
                request.getAccountName(),
                request.getScriptName(),
                recordingEnabled,
                request.getTriggerSource(),
                preferred);
      } catch (Exception e) {
        log.error("Error starting debug session: {}", e.getMessage(), e);
        releasePreferredReservation(preferred, wsId);
        if (!sendErrorResponse(
            wsSession, "SESSION_START failed", e.getMessage(), clientRequestId)) {
          closeWebSocketSafely(wsSession);
        }
        return;
      }

      String canonicalSessionId = outcome.response().getSessionId();

      // Reacquire the ownership lock: check the authoritative canonical session id for conflicts,
      // convert the reservation into canonical ownership, and install a STARTING binding.
      // Reacquire the ownership lock: check the authoritative canonical session id for conflicts
      // and convert the reservation into canonical ownership. Compensation (which may stop a
      // recording or touch the repository) and the error send run outside the lock so neither a
      // slow rollback nor a blocking send serializes all ownership transitions.
      SessionStartRollbackToken conflictToken = null;
      synchronized (ownershipLock) {
        String canonicalOwner = sessionOwners.get(canonicalSessionId);
        if (canonicalOwner != null && !canonicalOwner.equals(wsId)) {
          conflictToken = outcome.rollbackToken();
          if (preferred != null && !preferred.isBlank()) {
            preferredReservations.remove(preferred, wsId);
          }
        } else {
          sessionOwners.put(canonicalSessionId, wsId);
          // The preferred reservation is obsolete once canonical ownership is claimed; remove it
          // regardless of whether the canonical id equals the preferred id.
          if (preferred != null && !preferred.isBlank()) {
            preferredReservations.remove(preferred, wsId);
          }
          sessionBindings.put(
              wsId,
              new SessionBinding(
                  canonicalSessionId,
                  shadowRunId,
                  BindingPhase.STARTING,
                  false,
                  outcome.rollbackToken(),
                  outcome.response().getRecordingEnabled(),
                  outcome.response().getRecordingId(),
                  outcome.response().getStartTick(),
                  outcome.response().getRecordingFailureReason()));
        }
      }
      if (conflictToken != null) {
        log.warn(
            "Rejecting SESSION_START on connection {}: canonical session {} owned by another"
                + " connection",
            wsId,
            canonicalSessionId);
        // A canonical conflict has no canonical ownership for this socket. If compensation fails,
        // retain a non-active binding solely as a guarded cleanup handle; otherwise the started
        // recorder/session would become unreachable.
        if (!compensateOutcome(conflictToken, "session start canonical ownership conflict")) {
          synchronized (ownershipLock) {
            sessionBindings.putIfAbsent(
                wsId,
                new SessionBinding(
                    canonicalSessionId,
                    shadowRunId,
                    BindingPhase.START_ROLLBACK_FAILED,
                    false,
                    conflictToken,
                    outcome.response().getRecordingEnabled(),
                    outcome.response().getRecordingId(),
                    outcome.response().getStartTick(),
                    outcome.response().getRecordingFailureReason()));
          }
          closeWebSocketSafely(wsSession);
          return;
        }
        sendErrorResponse(
            wsSession,
            "SESSION_ALREADY_OWNED",
            "The requested debug session belongs to another connection",
            clientRequestId);
        return;
      }

      // Open the shadow run; only a successful open advances the binding to ACTIVE.
      boolean shadowOpened;
      try {
        shadowOpened = shadowDecisionService.openSession(canonicalSessionId, shadowRunId);
      } catch (Exception e) {
        log.error(
            "Failed to open shadow run for session {}: {}", canonicalSessionId, e.getMessage(), e);
        shadowOpened = false;
      }

      if (!shadowOpened) {
        startFailureCleanup(
            wsSession,
            clientRequestId,
            BindingPhase.START_ROLLBACK_FAILED,
            "Failed to open shadow run for the session",
            true);
        return;
      }

      // Atomically replace the STARTING binding with an ACTIVE binding.
      SessionBinding starting = sessionBindings.get(wsId);
      SessionBinding active =
          new SessionBinding(
              canonicalSessionId,
              shadowRunId,
              BindingPhase.ACTIVE,
              true,
              outcome.rollbackToken(),
              outcome.response().getRecordingEnabled(),
              outcome.response().getRecordingId(),
              outcome.response().getStartTick(),
              outcome.response().getRecordingFailureReason());
      synchronized (ownershipLock) {
        sessionBindings.replace(wsId, starting, active);
      }

      // Echo the transport correlation id unchanged and expose the run id for observability.
      SessionStartResponse response = outcome.response();
      response.setClientRequestId(clientRequestId);
      response.setShadowRunId(shadowRunId);

      log.info(
          "SESSION_START result: sessionId={}, shadowRunId={}, recordingId={}, failureReason={},"
              + " clientRequestId={}",
          response.getSessionId(),
          shadowRunId,
          response.getRecordingId() != null ? response.getRecordingId() : "none",
          response.getRecordingFailureReason() != null
              ? response.getRecordingFailureReason()
              : "none",
          response.getClientRequestId() != null ? response.getClientRequestId() : "none");

      // Serialize and send the response. A failure here happens after the binding was committed:
      // compensate synchronously and close so the close callback (if any) performs no further work.
      String responseJson;
      try {
        responseJson = objectMapper.writeValueAsString(response);
      } catch (Exception e) {
        log.error(
            "Failed to serialize SESSION_START response for session {}; compensating and closing",
            canonicalSessionId,
            e);
        startFailureCleanup(
            wsSession,
            clientRequestId,
            BindingPhase.START_RESPONSE_FAILED,
            "Failed to serialize SESSION_START response",
            false);
        return;
      }
      try {
        wsSession.sendMessage(new TextMessage(responseJson));
        successResponseSent = true;
      } catch (Exception e) {
        log.error(
            "Failed to send SESSION_START response for session {}; compensating and closing",
            canonicalSessionId,
            e);
        startFailureCleanup(
            wsSession,
            clientRequestId,
            BindingPhase.START_RESPONSE_FAILED,
            "Failed to send SESSION_START response",
            false);
        return;
      }

      log.info(
          "Sent SESSION_START response to Micro6: sessionId={}, shadowRunId={}",
          response.getSessionId(),
          shadowRunId);

      // Broadcast session creation to all debugger clients only after the send succeeded.
      broadcastSessionStartToDebugger(response, request.getAccountName());

    } catch (Exception e) {
      log.error("Error handling SESSION_START: {}", e.getMessage(), e);
      if (!successResponseSent) {
        String clientRequestId =
            root.has("clientRequestId") && !root.get("clientRequestId").isNull()
                ? root.get("clientRequestId").asText()
                : null;
        startFailureCleanup(
            wsSession,
            clientRequestId,
            BindingPhase.START_ROLLBACK_FAILED,
            e.getMessage() != null ? e.getMessage() : "SESSION_START failed",
            true);
      }
    }
  }

  /**
   * Rebinds a reconnecting Micro6 socket to an active session without starting another session,
   * recording, or shadow run. The preferred session id is the reconnect token already used by the
   * wire protocol. Claiming and timeout cancellation are atomic with respect to grace expiry.
   */
  private boolean tryResumePendingSession(WebSocketSession wsSession, SessionStartRequest request) {
    String preferred = request.getPreferredSessionId();
    if (preferred == null || preferred.isBlank()) {
      return false;
    }

    String wsId = wsSession.getId();
    PendingReconnect pending;
    synchronized (ownershipLock) {
      if (sessionBindings.containsKey(wsId) || hasStartReservation(wsId)) {
        return false;
      }
      pending = pendingReconnects.remove(preferred);
      if (pending == null) {
        return false;
      }
      sessionOwners.put(preferred, wsId);
      sessionBindings.put(wsId, pending.binding.withPhase(BindingPhase.ACTIVE));
    }

    ScheduledFuture<?> timeout = pending.timeout;
    if (timeout != null) {
      timeout.cancel(false);
    }

    SessionBinding binding = pending.binding;
    SessionStartResponse response =
        new SessionStartResponse(
            binding.sessionId(),
            binding.recordingEnabled(),
            binding.recordingId(),
            binding.startTick(),
            binding.recordingFailureReason(),
            request.getClientRequestId());
    response.setShadowRunId(binding.shadowRunId());

    try {
      wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
      log.info(
          "Rebound Micro6 connection {} to debug session {} during reconnect grace",
          wsId,
          binding.sessionId());
    } catch (Exception e) {
      log.error(
          "Failed to send reconnect response for session {}; returning it to reconnect grace",
          binding.sessionId(),
          e);
      deferActiveFinalization(wsSession, "reconnect response failure");
      try {
        wsSession.close(CloseStatus.SERVER_ERROR);
      } catch (Exception closeFailure) {
        log.debug("Failed to close socket after reconnect response failure", closeFailure);
      }
    }
    return true;
  }

  /**
   * Unified cleanup for a failed session-start transaction.
   *
   * <p>Order: mark the binding with a non-active failure phase, close the exact shadow run if it
   * was opened, call {@link DebugSessionService#rollbackSessionStart}, then either remove binding +
   * ownership + reservation (compensation succeeded) or retain them and close the socket so the
   * close callback performs exactly one guarded retry (compensation failed).
   *
   * @param sendError when true, a correlated error is sent if no success response may have been
   *     delivered; when false (response delivery already failed), the socket is closed instead.
   */
  private void startFailureCleanup(
      WebSocketSession wsSession,
      String clientRequestId,
      BindingPhase failurePhase,
      String detail,
      boolean sendError) {
    String wsId = wsSession.getId();
    SessionBinding binding = sessionBindings.get(wsId);
    if (binding == null) {
      // Nothing was bound; release any lingering preferred reservation, then deliver the error.
      releaseReservationsForWsId(wsId);
      if (sendError) {
        if (!sendErrorResponse(wsSession, "SESSION_START failed", detail, clientRequestId)) {
          closeWebSocketSafely(wsSession);
        }
      }
      return;
    }

    // 1. Mark the binding with the non-active failure phase.
    SessionBinding failing = binding.withPhase(failurePhase);
    synchronized (ownershipLock) {
      sessionBindings.replace(wsId, binding, failing);
    }

    // 2 + 3. Close the shadow run (if opened) and compensate the session start.
    boolean compensated = rollbackBinding(wsId, failing, detail);

    if (compensated) {
      // 4. Remove binding, canonical ownership, and any preferred reservation.
      removeBindingAndOwnershipAndReservation(wsId, failing);
      if (sendError) {
        if (!sendErrorResponse(wsSession, "SESSION_START failed", detail, clientRequestId)) {
          closeWebSocketSafely(wsSession);
        }
      } else {
        closeWebSocketSafely(wsSession);
      }
    } else {
      // 5. Retain binding and ownership; close so the close callback retries exactly once.
      closeWebSocketSafely(wsSession);
    }
  }

  /**
   * Closes the shadow run (if it was opened) and rolls back the session start. Both operations are
   * required: losing the in-memory run cleanup must retain retry state just like a failed recording
   * rollback.
   */
  private boolean rollbackBinding(String wsId, SessionBinding binding, String reason) {
    boolean shadowCleaned = true;
    if (binding.shadowOpened()) {
      try {
        shadowDecisionService.cleanupSession(binding.sessionId(), binding.shadowRunId());
      } catch (Exception e) {
        log.error("Failed to clean up shadow run during start rollback: {}", e.getMessage(), e);
        shadowCleaned = false;
      }
    }
    try {
      sessionService.rollbackSessionStart(binding.rollbackToken(), reason);
      return shadowCleaned;
    } catch (Exception e) {
      log.error(
          "Failed to roll back session start for session {}: {}",
          binding.sessionId(),
          e.getMessage(),
          e);
      return false;
    }
  }

  /**
   * Compensates a start outcome without an installed binding (e.g. canonical ownership conflict).
   */
  private boolean compensateOutcome(SessionStartRollbackToken token, String reason) {
    try {
      sessionService.rollbackSessionStart(token, reason);
      return true;
    } catch (Exception e) {
      log.error("Failed to compensate session start outcome: {}", e.getMessage(), e);
      return false;
    }
  }

  /** Releases a preferred-session reservation made by this WebSocket, idempotently. */
  private void releasePreferredReservation(String preferred, String wsId) {
    if (preferred == null || preferred.isBlank()) {
      return;
    }
    synchronized (ownershipLock) {
      preferredReservations.remove(preferred, wsId);
    }
  }

  /** Returns true when this WebSocket holds a preferred-session reservation. */
  private boolean hasStartReservation(String wsId) {
    synchronized (ownershipLock) {
      return preferredReservations.containsValue(wsId);
    }
  }

  /**
   * Closes the WebSocket and performs the guarded final cleanup immediately when a failed-start
   * binding remains. This does not rely on a container close callback, which may never arrive for
   * an already-closed or broken transport; a later callback observes no binding and is a no-op.
   */
  private void closeWebSocketSafely(WebSocketSession wsSession) {
    try {
      wsSession.close(CloseStatus.SERVER_ERROR);
    } catch (Exception e) {
      log.warn("Failed to close WebSocket after SESSION_START failure: {}", e.getMessage());
    }
    // The start failure has already made one compensation attempt. This is the single guarded
    // fallback attempt and is safe whether close succeeded, threw, or invoked its callback inline.
    finalizeOnDisconnect(wsSession);
  }

  /** Handle TICK_BATCH message from Micro6. */
  private void handleTickBatch(WebSocketSession session, JsonNode root) {
    SessionBinding binding = sessionBindings.get(session.getId());
    if (binding == null) {
      log.warn("Rejecting tick batch on connection {}: SESSION_NOT_BOUND", session.getId());
      sendBatchErrorResponse(
          session, "SESSION_NOT_BOUND", "Send SESSION_START before sending ticks", batchId(root));
      return;
    }
    if (binding.phase() != BindingPhase.ACTIVE) {
      log.warn(
          "Rejecting tick batch on connection {} for session {}: SESSION_STOPPING",
          session.getId(),
          binding.sessionId());
      sendBatchErrorResponse(
          session,
          "SESSION_STOPPING",
          "This connection no longer accepts ticks for its session",
          batchId(root));
      return;
    }
    try {
      JsonNode exactWireDocument = root;
      TickBatchMessage batch = objectMapper.treeToValue(exactWireDocument, TickBatchMessage.class);

      if (!"TICK_BATCH".equals(batch.getType())) {
        log.warn("Received non-batch message in TICK_BATCH handler: {}", batch.getType());
        return;
      }

      if (!binding.sessionId().equals(batch.getSessionId())) {
        log.warn(
            "Rejecting tick batch on connection {}: SESSION_ID_MISMATCH expected={}, actual={}",
            session.getId(),
            binding.sessionId(),
            batch.getSessionId());
        sendBatchErrorResponse(
            session,
            "SESSION_ID_MISMATCH",
            "The tick session does not match the connection binding",
            batchId(root));
        return;
      }

      log.info(
          "Received tick batch: sessionId={}, tick={}, logs={}, overlays={}",
          batch.getSessionId(),
          batch.getTick(),
          batch.getLogs() != null ? batch.getLogs().size() : 0,
          batch.getOverlays() != null ? batch.getOverlays().size() : 0);

      // Persistence is synchronous and transactional. The ACK is emitted only after the service
      // returns successfully; a failure therefore leaves the exact client batch eligible for retry.
      TickBatchAcknowledgment acknowledgment = ingestionService.processTickBatch(batch, root);
      if (acknowledgment == null) {
        throw new IllegalStateException("Tick ingestion returned no acknowledgment");
      }
      sendBatchAcknowledgment(session, acknowledgment);

      // Shadow evaluation is observational and must never be used as persistence evidence.
      try {
        shadowDecisionService.evaluateAndPublish(
            batch, binding.shadowRunId(), this::broadcastShadowDecision);
      } catch (Exception shadowFailure) {
        log.warn(
            "Shadow evaluation failed after persisted batch {}: {}",
            batch.getBatchId(),
            shadowFailure.getMessage());
      }

    } catch (Exception e) {
      log.error("Error handling TICK_BATCH: {}", e.getMessage(), e);
      String errorCode = mapTickBatchError(e);
      sendBatchErrorResponse(session, errorCode, e.getMessage(), batchId(root));
    }
  }

  private String batchId(JsonNode root) {
    if (root == null || !root.has("batchId") || root.get("batchId").isNull()) {
      return null;
    }
    String value = root.get("batchId").asText();
    return value == null || value.isBlank() ? null : value;
  }

  private String mapTickBatchError(Exception error) {
    String message = error.getMessage() == null ? "" : error.getMessage();
    if (message.contains("BATCH_CHECKSUM_CONFLICT")) return "BATCH_CHECKSUM_CONFLICT";
    if (message.contains("BATCH_WIRE_IDENTITY_MISMATCH")) return "BATCH_WIRE_IDENTITY_MISMATCH";
    if (message.contains("BATCH_SESSION_MISMATCH")) return "BATCH_SESSION_MISMATCH";
    if (message.contains("BATCH_ORDER_INVALID")) return "BATCH_ORDER_INVALID";
    if (message.contains("ACTION_CHECKSUM_CONFLICT")) return "ACTION_CHECKSUM_CONFLICT";
    if (message.contains("PACKET_")) return "BATCH_PACKET_INVALID";
    if (error instanceof IllegalArgumentException) return "BATCH_INPUT_INVALID";
    return "BATCH_PERSISTENCE_FAILED";
  }

  private void sendBatchAcknowledgment(
      WebSocketSession session, TickBatchAcknowledgment acknowledgment) {
    try {
      Map<String, Object> response = new java.util.LinkedHashMap<>();
      response.put("type", "TICK_BATCH_ACK");
      response.put("schema", "tick-batch-ack/v1");
      response.put("batchId", acknowledgment.batchId());
      response.put("sessionId", acknowledgment.sessionId());
      response.put("tick", acknowledgment.tick());
      response.put("payloadChecksum", acknowledgment.payloadChecksum());
      response.put("status", acknowledgment.status());
      session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
    } catch (Exception e) {
      log.warn(
          "Failed to send TICK_BATCH_ACK for {}: {}", acknowledgment.batchId(), e.getMessage());
    }
  }

  /** Broadcast one shadow decision to all debugger UI clients. */
  private void broadcastShadowDecision(FishingShadowDecision decision) {
    try {
      String messageJson = objectMapper.writeValueAsString(decision);
      debuggerWebSocketHandler.broadcast(new TextMessage(messageJson));
      log.debug(
          "Broadcast shadow decision to {} debugger client(s): sessionId={}, tick={}, status={}",
          debuggerWebSocketHandler.getClientCount(),
          decision.sessionId(),
          decision.tick(),
          decision.status());
    } catch (Exception e) {
      log.error("Failed to broadcast shadow decision: {}", e.getMessage(), e);
    }
  }

  /** Handle SESSION_STOP message from Micro6. */
  private void handleSessionStop(WebSocketSession session, JsonNode root) {
    try {
      SessionStopRequest request = objectMapper.treeToValue(root, SessionStopRequest.class);
      SessionBinding binding = sessionBindings.get(session.getId());
      if (binding == null) {
        log.warn("Rejecting SESSION_STOP on connection {}: SESSION_NOT_BOUND", session.getId());
        sendErrorResponse(
            session,
            "SESSION_NOT_BOUND",
            "This connection has no bound debug session to stop",
            null);
        return;
      }
      if (!binding.sessionId().equals(request.getSessionId())) {
        log.warn(
            "Rejecting SESSION_STOP on connection {}: SESSION_ID_MISMATCH expected={}, actual={}",
            session.getId(),
            binding.sessionId(),
            request.getSessionId());
        sendErrorResponse(
            session,
            "SESSION_ID_MISMATCH",
            "The stop session does not match the connection binding",
            null);
        return;
      }
      if (binding.phase() != BindingPhase.ACTIVE) {
        log.warn(
            "Ignoring repeated SESSION_STOP on connection {} while session {} is already stopping",
            session.getId(),
            binding.sessionId());
        sendErrorResponse(
            session,
            "SESSION_STOPPING",
            "Session finalization is already in progress or awaiting disconnect",
            null);
        return;
      }

      log.info(
          "Stopping debug session: sessionId={}, shadowRunId={}, endTick={}",
          binding.sessionId(),
          binding.shadowRunId(),
          request.getEndTick());

      // Atomically change ACTIVE -> EXPLICIT_STOP_FAILED before finalization; from this point
      // onward ticks on the connection are rejected. The interim phase is retained whenever the
      // explicit finalization fails so close can perform exactly one fallback.
      SessionBinding stopping = binding.withPhase(BindingPhase.EXPLICIT_STOP_FAILED);
      if (!sessionBindings.replace(session.getId(), binding, stopping)) {
        return;
      }

      // Recording finalization and shadow cleanup are separate error boundaries: a recording
      // finalization failure must not leak shadow baselines or counters.
      boolean finalizationSucceeded = false;
      try {
        sessionService.stopSession(request.getSessionId(), request.getEndTick(), true);
        finalizationSucceeded = true;
      } catch (Exception e) {
        log.error("Error stopping debug session {}: {}", request.getSessionId(), e.getMessage(), e);
      } finally {
        shadowDecisionService.cleanupSession(binding.sessionId(), binding.shadowRunId());
      }
      // Clear this WebSocket's binding and canonical ownership when explicit finalization
      // succeeded. When it failed, the EXPLICIT_STOP_FAILED binding is retained so a later
      // disconnect runs exactly one close fallback.
      if (finalizationSucceeded) {
        removeBindingAndOwnership(session.getId(), stopping);
      }
    } catch (Exception e) {
      log.error("Error handling SESSION_STOP: {}", e.getMessage(), e);
    }
  }

  /**
   * Send error response to Micro6 client.
   *
   * @return true when the error was delivered, false when the send failed (e.g. the session is
   *     closed or the serialization failed)
   */
  private boolean sendErrorResponse(
      WebSocketSession session, String error, String detail, String clientRequestId) {
    try {
      java.util.LinkedHashMap<String, Object> errorResponse = new java.util.LinkedHashMap<>();
      errorResponse.put("type", "ERROR");
      errorResponse.put("error", error);
      errorResponse.put("detail", detail);
      if (clientRequestId != null && !clientRequestId.isBlank()) {
        errorResponse.put("clientRequestId", clientRequestId);
      }
      session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorResponse)));
      return true;
    } catch (Exception e) {
      log.error("Failed to send error response: {}", e.getMessage());
      return false;
    }
  }

  private boolean sendBatchErrorResponse(
      WebSocketSession session, String error, String detail, String batchId) {
    try {
      java.util.LinkedHashMap<String, Object> response = new java.util.LinkedHashMap<>();
      response.put("type", "ERROR");
      response.put("error", error);
      response.put("detail", detail);
      if (batchId != null && !batchId.isBlank()) {
        response.put("batchId", batchId);
      }
      session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
      return true;
    } catch (Exception e) {
      log.error("Failed to send batch error response: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Removes one WebSocket's binding and, only when that removal actually succeeded, releases the
   * canonical ownership if it still points to this WebSocket.
   */
  private void removeBindingAndOwnership(String wsId, SessionBinding expected) {
    synchronized (ownershipLock) {
      if (sessionBindings.remove(wsId, expected)) {
        sessionOwners.remove(expected.sessionId(), wsId);
      }
    }
  }

  /** Releases every preferred-session reservation held by this WebSocket, idempotently. */
  private void releaseReservationsForWsId(String wsId) {
    synchronized (ownershipLock) {
      preferredReservations.entrySet().removeIf(e -> wsId.equals(e.getValue()));
    }
  }

  /**
   * Removes one WebSocket's binding, canonical ownership, and any lingering preferred reservation.
   */
  private void removeBindingAndOwnershipAndReservation(String wsId, SessionBinding expected) {
    synchronized (ownershipLock) {
      if (sessionBindings.remove(wsId, expected)) {
        sessionOwners.remove(expected.sessionId(), wsId);
      }
      preferredReservations.entrySet().removeIf(e -> wsId.equals(e.getValue()));
    }
  }

  /**
   * Broadcast session creation to all debugger web UI clients.
   *
   * <p>This allows the UI to detect when Micro6 creates a debug session and automatically sync to
   * it, fixing the session ID mismatch issue. The message includes the shadow run id so the
   * debugger can correlate decisions and status to the exact run.
   */
  private void broadcastSessionStartToDebugger(SessionStartResponse session, String accountName) {
    try {
      Map<String, Object> message =
          Map.of(
              "type",
              "SESSION_CREATED",
              "sessionId",
              session.getSessionId(),
              "shadowRunId",
              session.getShadowRunId() != null ? session.getShadowRunId() : "",
              "accountName",
              accountName != null ? accountName : "",
              "startTick",
              session.getStartTick());

      String messageJson = objectMapper.writeValueAsString(message);
      debuggerWebSocketHandler.broadcast(new TextMessage(messageJson));

      log.info(
          "Broadcast SESSION_CREATED to {} debugger client(s): sessionId={}, shadowRunId={}",
          debuggerWebSocketHandler.getClientCount(),
          session.getSessionId(),
          session.getShadowRunId());
    } catch (Exception e) {
      log.error("Failed to broadcast session start to debugger: {}", e.getMessage(), e);
    }
  }

  /** Defers finalization after a transport error so a replacement socket can resume the session. */
  private void finalizeOnTransportError(WebSocketSession wsSession) {
    String wsId = wsSession.getId();
    SessionBinding binding = sessionBindings.get(wsId);
    if (binding == null) {
      log.debug(
          "No debug session bound to WS session {} — nothing to finalize on transport error", wsId);
      return;
    }

    switch (binding.phase()) {
      case ACTIVE -> deferActiveFinalization(wsSession, "transport error");
      case STARTING, START_ROLLBACK_FAILED, START_RESPONSE_FAILED -> log.debug(
          "Skipping transport-error finalization on WS session {} while start is in progress/failed"
              + " (phase {})",
          wsId,
          binding.phase());
      case EXPLICIT_STOP_FAILED -> log.debug(
          "Skipping transport-error finalization on WS session {} while session {} awaits close "
              + "fallback",
          wsId,
          binding.sessionId());
    }
  }

  /**
   * Implicitly finalize the debug session bound to a WebSocket that disconnected without sending an
   * explicit SESSION_STOP.
   *
   * <p>This is the final fallback path: it runs at most once per binding, always closes the shadow
   * run idempotently, and removes the binding and (only if it still points to this WebSocket) the
   * canonical ownership after this final attempt regardless of success. A failed start retains
   * exactly one guarded retry of its compensation here. A repeated close callback therefore
   * performs no further finalization.
   */
  private void finalizeOnDisconnect(WebSocketSession wsSession) {
    String wsId = wsSession.getId();
    SessionBinding binding = sessionBindings.get(wsId);
    if (binding == null) {
      log.debug(
          "No debug session bound to WS session {} — nothing to finalize on disconnect", wsId);
      return;
    }

    switch (binding.phase()) {
      case STARTING, START_ROLLBACK_FAILED, START_RESPONSE_FAILED -> {
        log.info(
            "Implicit session start cleanup on disconnect for WS session {}, debugSessionId={},"
                + " phase={}",
            wsId,
            binding.sessionId(),
            binding.phase());
        // Exactly one guarded retry of the start compensation; then remove binding + ownership
        // regardless of success.
        rollbackBinding(wsId, binding, "disconnect");
        removeBindingAndOwnershipAndReservation(wsId, binding);
      }
      case ACTIVE -> deferActiveFinalization(wsSession, "disconnect");
      case EXPLICIT_STOP_FAILED -> {
        log.info("Close fallback for WS session {}, debugSessionId={}", wsId, binding.sessionId());
        try {
          sessionService.finalizeSessionOnDisconnect(binding.sessionId(), "disconnect");
        } catch (Exception e) {
          log.error(
              "Failed to finalize session {} on disconnect fallback: {}",
              binding.sessionId(),
              e.getMessage(),
              e);
        } finally {
          shadowDecisionService.cleanupSession(binding.sessionId(), binding.shadowRunId());
          removeBindingAndOwnershipAndReservation(wsId, binding);
        }
      }
    }
  }

  /** Detaches a dead socket and reserves its active session for a bounded reconnect window. */
  private void deferActiveFinalization(WebSocketSession wsSession, String cause) {
    String wsId = wsSession.getId();
    SessionBinding binding = sessionBindings.get(wsId);
    if (binding == null || binding.phase() != BindingPhase.ACTIVE) {
      return;
    }

    PendingReconnect pending = new PendingReconnect(binding, cause);
    synchronized (ownershipLock) {
      if (!sessionBindings.remove(wsId, binding)) {
        return;
      }
      sessionOwners.remove(binding.sessionId(), wsId);
      preferredReservations.entrySet().removeIf(e -> wsId.equals(e.getValue()));
      PendingReconnect previous = pendingReconnects.put(binding.sessionId(), pending);
      if (previous != null && previous.timeout != null) {
        previous.timeout.cancel(false);
      }
    }

    long delayMillis = Math.max(1L, reconnectGraceMillis);
    try {
      pending.timeout =
          reconnectScheduler.schedule(
              () -> expireReconnectGrace(binding.sessionId(), pending.token),
              delayMillis,
              TimeUnit.MILLISECONDS);
      log.info(
          "Detached WS session {} from debug session {}; waiting {} ms for Micro6 reconnect ({})",
          wsId,
          binding.sessionId(),
          delayMillis,
          cause);
    } catch (RuntimeException schedulingFailure) {
      log.error(
          "Could not schedule reconnect grace for session {}; finalizing now",
          binding.sessionId(),
          schedulingFailure);
      expireReconnectGrace(binding.sessionId(), pending.token);
    }
  }

  /** Finalizes only if the same pending reconnect generation still owns the grace reservation. */
  private void expireReconnectGrace(String sessionId, String token) {
    PendingReconnect pending;
    synchronized (ownershipLock) {
      pending = pendingReconnects.get(sessionId);
      if (pending == null || !pending.token.equals(token)) {
        return;
      }
      pendingReconnects.remove(sessionId, pending);
    }

    log.info(
        "Reconnect grace expired for debug session {} after {}; finalizing session and recording",
        sessionId,
        pending.cause);
    try {
      sessionService.finalizeSessionOnDisconnect(sessionId, pending.cause + " grace expired");
    } catch (Exception e) {
      log.error(
          "Failed to finalize session {} after reconnect grace: {}", sessionId, e.getMessage(), e);
    } finally {
      shadowDecisionService.cleanupSession(sessionId, pending.binding.shadowRunId());
    }
  }

  @PreDestroy
  void shutdownReconnectScheduler() {
    reconnectScheduler.shutdownNow();
    for (Map.Entry<String, PendingReconnect> entry : Map.copyOf(pendingReconnects).entrySet()) {
      expireReconnectGrace(entry.getKey(), entry.getValue().token);
    }
  }

  /**
   * Broadcast a feed control message to connected Micro6 clients.
   *
   * @param enabled true to resume sending tick data, false to pause
   * @return number of clients notified
   */
  public int broadcastFeedControl(boolean enabled) {
    if (micro6Clients.isEmpty()) {
      return 0;
    }
    int sent = 0;
    try {
      Map<String, Object> message = Map.of("type", "FEED_CONTROL", "enabled", enabled);
      String messageJson = objectMapper.writeValueAsString(message);
      TextMessage textMessage = new TextMessage(messageJson);

      for (WebSocketSession client : micro6Clients) {
        if (client.isOpen()) {
          synchronized (client) {
            client.sendMessage(textMessage);
          }
          sent++;
        }
      }
    } catch (Exception e) {
      log.error("Failed to broadcast FEED_CONTROL: {}", e.getMessage(), e);
    }
    return sent;
  }
}

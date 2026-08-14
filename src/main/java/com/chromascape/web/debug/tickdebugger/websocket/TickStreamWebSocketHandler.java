package com.chromascape.web.debug.tickdebugger.websocket;

import com.chromascape.web.aiplayer.prototype.FishingShadowDecision;
import com.chromascape.web.aiplayer.prototype.FishingShadowDecisionService;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartRequest;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.dto.SessionStopRequest;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * (websocket id to binding) and {@code sessionOwners} (canonical session id to websocket id); both
 * mappings are mutated only under {@code ownershipLock}. Per-WebSocket callbacks are serialized by
 * synchronizing on the WebSocket session, which also covers transport-error and close handling. The
 * lock order is {@code WebSocketSession monitor → ownershipLock} and is never reversed.
 *
 * <p>Protocol rejections receive machine-readable {@code ERROR} responses ({@code
 * SESSION_ALREADY_BOUND}, {@code SESSION_ALREADY_OWNED}, {@code SESSION_NOT_BOUND}, {@code
 * SESSION_STOPPING}, {@code SESSION_ID_MISMATCH}).
 *
 * <p>Stop, transport-error, and close follow a state machine that permits only the allowed
 * finalization attempts: an explicit stop that fails is not retried during a transport callback,
 * and close performs at most one final fallback before removing the binding regardless of success.
 */
@Component
public class TickStreamWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(TickStreamWebSocketHandler.class);

  private final ObjectMapper objectMapper;
  private final DebugSessionService sessionService;
  private final TickStreamIngestionService ingestionService;
  private final DebuggerTickWebSocketHandler debuggerWebSocketHandler;
  private final FishingShadowDecisionService shadowDecisionService;
  private final Set<WebSocketSession> micro6Clients = ConcurrentHashMap.newKeySet();

  /** Binding phase of a WebSocket-bound debug session. */
  enum BindingPhase {
    /** The bound session accepts ticks. */
    ACTIVE,
    /** An explicit SESSION_STOP was attempted but finalization failed; close may retry. */
    EXPLICIT_STOP_FAILED,
    /** Transport-error finalization failed; close may retry. */
    TRANSPORT_FINALIZATION_FAILED
  }

  /**
   * One WebSocket's ownership of a debug session.
   *
   * @param sessionId the canonical debug session id
   * @param shadowRunId the shadow run id opened for this binding
   * @param phase whether the connection still accepts ticks
   */
  record SessionBinding(String sessionId, String shadowRunId, BindingPhase phase) {}

  /**
   * Maps WebSocket session IDs to bound debug session ownership. Populated on successful
   * SESSION_START, cleared on successful explicit SESSION_STOP or final disconnect finalization.
   * Mutated only under {@code ownershipLock}.
   */
  private final Map<String, SessionBinding> sessionBindings = new ConcurrentHashMap<>();

  /**
   * Maps canonical debug session IDs to the WebSocket session ID that owns them. Enforces exclusive
   * ownership of a preferred session across connections. Mutated only under {@code ownershipLock}.
   */
  private final Map<String, String> sessionOwners = new ConcurrentHashMap<>();

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
    this.objectMapper = objectMapper;
    this.sessionService = sessionService;
    this.ingestionService = ingestionService;
    this.debuggerWebSocketHandler = debuggerWebSocketHandler;
    this.shadowDecisionService = shadowDecisionService;
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
        JsonNode root = objectMapper.readTree(message.getPayload());
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

  /** Handle SESSION_START message from Micro6. */
  private void handleSessionStart(WebSocketSession wsSession, JsonNode root) {
    // Response-attempt guard: set immediately before the first sendMessage call. At most one
    // response-write attempt is allowed per SESSION_START, so a failure while writing the
    // success/degraded response never produces a second (ERROR) response, and exceptions before
    // any response attempt still produce exactly one correlated SESSION_START failed.
    boolean responseAttempted = false;
    try {
      SessionStartRequest request = objectMapper.treeToValue(root, SessionStartRequest.class);
      String clientRequestId = request.getClientRequestId();
      String wsId = wsSession.getId();
      log.info(
          "Starting debug session via WebSocket: account={}, script={}, recording={}, clientRequestId={}",
          request.getAccountName(),
          request.getScriptName(),
          request.getRecordingEnabled(),
          clientRequestId != null ? clientRequestId : "none");

      // A WebSocket owns at most one session: reject a second SESSION_START on this connection.
      if (sessionBindings.containsKey(wsId)) {
        log.warn(
            "Rejecting second SESSION_START on connection {}: already bound to session {}",
            wsId,
            sessionBindings.get(wsId).sessionId());
        sendErrorResponse(
            wsSession,
            "SESSION_ALREADY_BOUND",
            "This connection already owns a debug session",
            clientRequestId);
        return;
      }

      boolean recordingEnabled = Boolean.TRUE.equals(request.getRecordingEnabled());
      SessionStartResponse response;
      String shadowRunId;
      synchronized (ownershipLock) {
        // Reject a requested preferred session already owned by another WebSocket.
        String preferred = request.getPreferredSessionId();
        if (preferred != null && !preferred.isBlank()) {
          String owner = sessionOwners.get(preferred);
          if (owner != null && !owner.equals(wsId)) {
            log.warn(
                "Rejecting SESSION_START on connection {}: preferred session {} owned by {}",
                wsId,
                preferred,
                owner);
            sendErrorResponse(
                wsSession,
                "SESSION_ALREADY_OWNED",
                "The requested debug session belongs to another connection",
                clientRequestId);
            return;
          }
        }

        // Create/start the debug session while still inside the ownership transaction.
        response =
            sessionService.startSession(
                request.getAccountName(),
                request.getScriptName(),
                recordingEnabled,
                request.getTriggerSource(),
                preferred);

        // Inspect the canonical session id returned by the service and reject any unexpected
        // canonical ownership conflict.
        String canonicalSessionId = response.getSessionId();
        String canonicalOwner = sessionOwners.get(canonicalSessionId);
        if (canonicalOwner != null && !canonicalOwner.equals(wsId)) {
          log.warn(
              "Rejecting SESSION_START on connection {}: canonical session {} owned by {}",
              wsId,
              canonicalSessionId,
              canonicalOwner);
          sendErrorResponse(
              wsSession,
              "SESSION_ALREADY_OWNED",
              "The requested debug session belongs to another connection",
              clientRequestId);
          return;
        }

        // Generate a collision-resistant shadow run id and add both ownership mappings.
        shadowRunId = "shadow_" + UUID.randomUUID().toString().replace("-", "");
        sessionBindings.put(
            wsId, new SessionBinding(canonicalSessionId, shadowRunId, BindingPhase.ACTIVE));
        sessionOwners.put(canonicalSessionId, wsId);
        log.debug(
            "Bound WS session {} to debug session {} run {}",
            wsId,
            canonicalSessionId,
            shadowRunId);
      }

      // Echo the transport correlation id unchanged and expose the run id for observability. The
      // full constructor carries clientRequestId to avoid handler-side mutation of the DTO.
      SessionStartResponse outbound =
          new SessionStartResponse(
              response.getSessionId(),
              response.getRecordingEnabled(),
              response.getRecordingId(),
              response.getStartTick(),
              response.getRecordingFailureReason(),
              clientRequestId);
      outbound.setShadowRunId(shadowRunId);

      log.info(
          "SESSION_START result: sessionId={}, shadowRunId={}, recordingId={}, failureReason={}, clientRequestId={}",
          outbound.getSessionId(),
          shadowRunId,
          outbound.getRecordingId() != null ? outbound.getRecordingId() : "none",
          outbound.getRecordingFailureReason() != null
              ? outbound.getRecordingFailureReason()
              : "none",
          outbound.getClientRequestId() != null ? outbound.getClientRequestId() : "none");

      // Open the shadow run before the first tick. The run id is unique per binding, so a
      // conflicting duplicate open is practically impossible; a rejection is logged and does not
      // undo the binding.
      shadowDecisionService.openSession(outbound.getSessionId(), shadowRunId);

      // Send response back to Micro6 with the sessionId. The guard is armed before the first
      // response write: when sending the success/degraded response throws, no additional ERROR is
      // attempted, the server binding is retained so disconnect finalization cleans up the created
      // session, and the socket is closed to trigger that finalization.
      responseAttempted = true;
      String responseJson = objectMapper.writeValueAsString(outbound);
      try {
        wsSession.sendMessage(new TextMessage(responseJson));
      } catch (Exception sendFailure) {
        log.error(
            "Failed to send SESSION_START response for session {}; closing connection for disconnect finalization: {}",
            outbound.getSessionId(),
            sendFailure.getMessage());
        try {
          wsSession.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
          // best-effort
        }
        return;
      }

      log.info(
          "Sent SESSION_START response to Micro6: sessionId={}, shadowRunId={}",
          outbound.getSessionId(),
          shadowRunId);

      // Broadcast session creation to all debugger clients
      broadcastSessionStartToDebugger(outbound, request.getAccountName());

    } catch (Exception e) {
      log.error("Error handling SESSION_START: {}", e.getMessage(), e);
      if (!responseAttempted) {
        String clientRequestId =
            root.has("clientRequestId") && !root.get("clientRequestId").isNull()
                ? root.get("clientRequestId").asText()
                : null;
        sendErrorResponse(wsSession, "SESSION_START failed", e.getMessage(), clientRequestId);
      }
    }
  }

  /** Handle TICK_BATCH message from Micro6. */
  private void handleTickBatch(WebSocketSession session, JsonNode root) {
    SessionBinding binding = sessionBindings.get(session.getId());
    if (binding == null) {
      log.warn("Rejecting tick batch on connection {}: SESSION_NOT_BOUND", session.getId());
      sendErrorResponse(
          session, "SESSION_NOT_BOUND", "Send SESSION_START before sending ticks", null);
      return;
    }
    if (binding.phase() != BindingPhase.ACTIVE) {
      log.warn(
          "Rejecting tick batch on connection {} for session {}: SESSION_STOPPING",
          session.getId(),
          binding.sessionId());
      sendErrorResponse(
          session,
          "SESSION_STOPPING",
          "This connection no longer accepts ticks for its session",
          null);
      return;
    }
    try {
      TickBatchMessage batch = objectMapper.treeToValue(root, TickBatchMessage.class);

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
        sendErrorResponse(
            session,
            "SESSION_ID_MISMATCH",
            "The tick session does not match the connection binding",
            null);
        return;
      }

      log.info(
          "Received tick batch: sessionId={}, tick={}, logs={}, overlays={}",
          batch.getSessionId(),
          batch.getTick(),
          batch.getLogs() != null ? batch.getLogs().size() : 0,
          batch.getOverlays() != null ? batch.getOverlays().size() : 0);

      // Shadow evaluation and publication run synchronously in received WebSocket order; the
      // resulting decision is broadcast before the existing asynchronous tick-ingestion path
      // continues. A shadow evaluation/publication failure must never prevent tick ingestion, so
      // they live in separate error boundaries: try shadow evaluation/publication, finally process
      // tick ingestion.
      try {
        shadowDecisionService.evaluateAndPublish(
            batch, binding.shadowRunId(), this::broadcastShadowDecision);
      } finally {
        ingestionService.processTickBatch(batch);
      }

    } catch (Exception e) {
      log.error("Error handling TICK_BATCH: {}", e.getMessage(), e);
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
      SessionBinding stopping =
          new SessionBinding(
              binding.sessionId(), binding.shadowRunId(), BindingPhase.EXPLICIT_STOP_FAILED);
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

  /** Send error response to Micro6 client. */
  private void sendErrorResponse(
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
    } catch (Exception e) {
      log.error("Failed to send error response: {}", e.getMessage());
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

  /**
   * Implicitly finalize the debug session bound to a WebSocket that experienced a transport error
   * without sending an explicit SESSION_STOP.
   *
   * <p>For an {@code ACTIVE} binding, one implicit transport finalization is attempted; on success
   * the binding and ownership are released, on failure the binding is retained in {@link
   * BindingPhase#TRANSPORT_FINALIZATION_FAILED} for the close callback. For an {@code
   * EXPLICIT_STOP_FAILED} binding no further finalization runs during the transport callback and
   * the binding is retained for close. Repeated {@code TRANSPORT_FINALIZATION_FAILED} callbacks are
   * ignored.
   */
  private void finalizeOnTransportError(WebSocketSession wsSession) {
    String wsId = wsSession.getId();
    SessionBinding binding = sessionBindings.get(wsId);
    if (binding == null) {
      log.debug(
          "No debug session bound to WS session {} — nothing to finalize on transport error", wsId);
      return;
    }

    switch (binding.phase()) {
      case ACTIVE -> {
        log.info(
            "Implicit session stop on transport error for WS session {}, debugSessionId={}",
            wsId,
            binding.sessionId());
        boolean finalizationSucceeded = false;
        try {
          sessionService.finalizeSessionOnDisconnect(binding.sessionId(), "transport error");
          finalizationSucceeded = true;
        } catch (Exception e) {
          log.error(
              "Failed to implicitly finalize session {} on transport error: {}",
              binding.sessionId(),
              e.getMessage(),
              e);
        } finally {
          shadowDecisionService.cleanupSession(binding.sessionId(), binding.shadowRunId());
        }
        if (finalizationSucceeded) {
          removeBindingAndOwnership(wsId, binding);
        } else {
          synchronized (ownershipLock) {
            sessionBindings.replace(
                wsId,
                binding,
                new SessionBinding(
                    binding.sessionId(),
                    binding.shadowRunId(),
                    BindingPhase.TRANSPORT_FINALIZATION_FAILED));
          }
        }
      }
      case EXPLICIT_STOP_FAILED -> log.debug(
          "Skipping transport-error finalization on WS session {} while session {} awaits close "
              + "fallback",
          wsId,
          binding.sessionId());
      case TRANSPORT_FINALIZATION_FAILED -> log.debug(
          "Ignoring repeated transport-error finalization on WS session {} for session {}",
          wsId,
          binding.sessionId());
    }
  }

  /**
   * Implicitly finalize the debug session bound to a WebSocket that disconnected without sending an
   * explicit SESSION_STOP.
   *
   * <p>This is the final fallback path: it runs at most once per binding (including when an
   * explicit SESSION_STOP or transport-error finalization previously failed), always closes the
   * shadow run idempotently, and removes the binding and (only if it still points to this
   * WebSocket) the canonical ownership after this final attempt regardless of success. A repeated
   * close callback therefore performs no further finalization.
   */
  private void finalizeOnDisconnect(WebSocketSession wsSession) {
    String wsId = wsSession.getId();
    SessionBinding binding = sessionBindings.get(wsId);
    if (binding == null) {
      log.debug(
          "No debug session bound to WS session {} — nothing to finalize on disconnect", wsId);
      return;
    }

    log.info(
        "Implicit session stop on disconnect for WS session {}, debugSessionId={}, shadowRunId={}",
        wsId,
        binding.sessionId(),
        binding.shadowRunId());

    try {
      sessionService.finalizeSessionOnDisconnect(binding.sessionId(), "disconnect");
    } catch (Exception e) {
      log.error(
          "Failed to implicitly finalize session {} on disconnect: {}",
          binding.sessionId(),
          e.getMessage(),
          e);
    } finally {
      shadowDecisionService.cleanupSession(binding.sessionId(), binding.shadowRunId());
      removeBindingAndOwnership(wsId, binding);
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

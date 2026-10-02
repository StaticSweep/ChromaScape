package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.SessionCountProjection;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionFailureCode;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingService.RecordingMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing debug session lifecycle.
 *
 * <p>Handles session creation, termination, and optional video recording linkage.
 */
@Service
public class DebugSessionService {

  private static final Logger log = LoggerFactory.getLogger(DebugSessionService.class);

  private final DebugSessionRepository sessionRepository;
  private final TickSnapshotRepository snapshotRepository;
  private final TickLogRepository tickLogRepository;
  private final TimelineMarkerRepository timelineMarkerRepository;
  private final RecordingService recordingService;
  private final RecordingSessionRepository recordingSessionRepository;
  private final RecordingConfig recordingConfig;
  private final ObjectMapper objectMapper;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.chromascape.web.recording.provenance.BehaviorRunContextService contexts;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.chromascape.web.recording.provenance.CanonicalFrameStore canonicalFrames;

  public DebugSessionService(
      DebugSessionRepository sessionRepository,
      TickSnapshotRepository snapshotRepository,
      TickLogRepository tickLogRepository,
      TimelineMarkerRepository timelineMarkerRepository,
      RecordingService recordingService,
      RecordingSessionRepository recordingSessionRepository,
      RecordingConfig recordingConfig,
      ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.snapshotRepository = snapshotRepository;
    this.tickLogRepository = tickLogRepository;
    this.timelineMarkerRepository = timelineMarkerRepository;
    this.recordingService = recordingService;
    this.recordingSessionRepository = recordingSessionRepository;
    this.recordingConfig = recordingConfig;
    this.objectMapper = objectMapper;
  }

  /**
   * Start a new debug session. Defaults to {@link RecordingTriggerSource#MICRO6_AUTO} for
   * backward-compatibility with the Micro6 WebSocket path.
   *
   * @param accountName the account name
   * @param scriptName the script being run
   * @param recordingEnabled whether to start video recording
   * @return response with session ID and optional recording ID
   */
  @Transactional
  public SessionStartResponse startSession(
      String accountName, String scriptName, Boolean recordingEnabled) {
    return startSessionWithOutcome(
            accountName, scriptName, recordingEnabled, RecordingTriggerSource.MICRO6_AUTO, null)
        .response();
  }

  /**
   * Start a new debug session with an explicit trigger source.
   *
   * @param accountName the account name
   * @param scriptName the script being run
   * @param recordingEnabled whether to start video recording
   * @param triggerSource the source that initiated this session
   * @return response with session ID and optional recording ID
   */
  @Transactional
  public SessionStartResponse startSession(
      String accountName,
      String scriptName,
      Boolean recordingEnabled,
      RecordingTriggerSource triggerSource) {
    return startSessionWithOutcome(accountName, scriptName, recordingEnabled, triggerSource, null)
        .response();
  }

  /**
   * Start a session/recording with optional preferred session binding.
   *
   * <p>When preferredSessionId is provided and active, it is reused to ensure recording and tick
   * ingestion stay on the same session.
   */
  @Transactional
  public SessionStartResponse startSession(
      String accountName,
      String scriptName,
      Boolean recordingEnabled,
      RecordingTriggerSource triggerSource,
      String preferredSessionId) {
    return startSessionWithOutcome(
            accountName, scriptName, recordingEnabled, triggerSource, preferredSessionId)
        .response();
  }

  /**
   * Start a session/recording with optional preferred session binding, returning authoritative
   * creation/reuse metadata plus a rollback token so a failed WebSocket transaction can compensate
   * exactly the mutations this operation performed.
   *
   * <p>This is the authoritative resolve-or-create entry point. The returned {@link
   * SessionStartOutcome} is the only source of truth for whether a session was newly created or a
   * preferred session was reused, and whether this operation started a recording. The metadata is
   * internal and never serialized to Micro6 or the debugger.
   */
  @Transactional
  public SessionStartOutcome startSessionWithOutcome(
      String accountName,
      String scriptName,
      Boolean recordingEnabled,
      RecordingTriggerSource triggerSource,
      String preferredSessionId) {

    return startSessionWithOutcome(
        accountName,
        scriptName,
        recordingEnabled,
        triggerSource,
        preferredSessionId,
        null,
        null,
        null);
  }

  @Transactional
  public SessionStartOutcome startSessionWithOutcome(
      String accountName,
      String scriptName,
      Boolean recordingEnabled,
      RecordingTriggerSource triggerSource,
      String preferredSessionId,
      String taskKey,
      com.fasterxml.jackson.databind.JsonNode taskArgs,
      com.fasterxml.jackson.databind.JsonNode runContext) {
    var provenance =
        com.chromascape.web.recording.provenance.RecordingProvenance.capture(taskKey, taskArgs);
    com.fasterxml.jackson.databind.node.ObjectNode contextSnapshot = null;
    if (runContext != null && !runContext.isNull()) {
      if (contexts == null)
        throw new com.chromascape.web.recording.provenance.ProvenanceException(
            "CONTEXT_SERVICE_UNAVAILABLE");
      contextSnapshot = contexts.snapshot(runContext, provenance, accountName);
    }
    // Reconnect/restart may drain the original durable outbox after disconnect finalization.
    // Reuse only the exact immutable capture; do not restart video or reopen its quality gate.
    if (contextSnapshot != null && preferredSessionId != null) {
      var previous = sessionRepository.findBySessionId(preferredSessionId);
      if (previous.isPresent() && previous.get().getRecordingId() != null) {
        var row = contexts.recording(previous.get().getRecordingId());
        var expected = contexts.bind(contextSnapshot, row.getRecordingId(), preferredSessionId);
        if (!expected.equals(row.getRunContext()))
          throw new com.chromascape.web.recording.provenance.ProvenanceException(
              "IMMUTABLE_PROVENANCE");
        if (!previous.get().isActive()) {
          var response =
              new SessionStartResponse(
                  preferredSessionId,
                  false,
                  row.getRecordingId(),
                  previous.get().getStartTick(),
                  null);
          response.setSelectedTickBatchVersion(3);
          response.setSelectedAckVersion(1);
          response.setRunContext(row.getRunContext());
          return new SessionStartOutcome(
              response,
              new SessionStartRollbackToken(
                  preferredSessionId,
                  false,
                  null,
                  row.getRecordingId(),
                  previous.get().getStartTime(),
                  previous.get().getLifecycleState()),
              false,
              true,
              false);
        }
      }
    }
    ResolveResult resolved = resolveOrCreateSession(accountName, scriptName, preferredSessionId);
    DebugSession session = resolved.session();
    boolean createdNewSession = resolved.createdNewSession();
    boolean reusedPreferredSession = resolved.reusedPreferredSession();
    if (contextSnapshot != null && createdNewSession)
      session.setSessionId(UUID.randomUUID().toString());
    String sessionId = session.getSessionId();
    Long startTick = session.getStartTick() != null ? session.getStartTick() : 0L;

    // Capture the pre-mutation recording/lifecycle state so a reused session can be restored on
    // rollback.
    if (reusedPreferredSession && session.getRecordingId() != null) {
      var existing = recordingSessionRepository.findByRecordingId(session.getRecordingId());
      if (existing.isPresent()
          && taskKey != null
          && (!java.util.Objects.equals(existing.get().getTaskKey(), taskKey)
              || taskArgs != null
                  && !java.util.Objects.equals(
                      existing.get().getSettingsHash(), provenance.settingsHash())))
        throw new com.chromascape.web.recording.provenance.ProvenanceException(
            "IMMUTABLE_PROVENANCE");
    }
    String previousRecordingId = session.getRecordingId();
    LocalDateTime previousStartTime = session.getStartTime();
    DebugSessionLifecycleState previousLifecycleState = session.getLifecycleState();

    String recordingId = null;
    String recordingFailureReason = null;
    boolean recordingStartedByOperation = false;
    LocalDateTime sessionStartTime;

    // Optionally start video recording
    boolean shouldStartRecording =
        Boolean.TRUE.equals(recordingEnabled) && shouldStartRecording(triggerSource);

    if (shouldStartRecording) {
      String existingRecordingId = session.getRecordingId();
      if (existingRecordingId != null && !existingRecordingId.isBlank()) {
        // Reuse an existing recording association: never start a second recording and never treat
        // the existing recording as owned by this operation.
        recordingId = existingRecordingId;
        log.info("Reusing existing recording {} for debug session {}", recordingId, sessionId);
      } else {
        try {
          recordingId = recordingService.startRecording(triggerSource);
          // From this point the recorder is an external side effect that must be compensated if
          // any database write below fails. Set ownership before the first persistence call.
          recordingStartedByOperation = true;
          sessionStartTime = recordingService.getRecordingStartTimeAsLocalDateTime();

          if (sessionStartTime == null) {
            sessionStartTime = LocalDateTime.now();
            log.warn("Recording started but time unavailable, using current time");
          }

          // Update (or bind existing) session with recording ID and aligned start time
          session.setRecordingId(recordingId);
          session.setStartTime(sessionStartTime);

          // Create RecordingSession entity for debug session recording
          RecordingSession recordingSession =
              new RecordingSession(
                  recordingId,
                  session.getAccountName(),
                  session.getScriptName(),
                  sessionStartTime,
                  toSessionTrigger(triggerSource),
                  null);
          recordingSession.captureProvenance(provenance);
          if (contextSnapshot != null)
            recordingSession.bindRunContext(
                contexts.bind(contextSnapshot, recordingId, sessionId), "CAPTURED");
          recordingSessionRepository.save(recordingSession);

          log.info("Started recording {} for debug session {}", recordingId, sessionId);
        } catch (Exception e) {
          String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
          if (recordingStartedByOperation) {
            // A failure after startRecording() is not a degraded recording start: persistence
            // cannot safely continue without its recording association. Undo the exact recorder
            // side effect and fail the transaction.
            try {
              stopRecordingForRollback(recordingId);
            } catch (SessionStartRollbackException rollbackFailure) {
              rollbackFailure.addSuppressed(e);
              throw rollbackFailure;
            }
            throw new SessionStartRollbackException(
                "Failed to persist started recording for debug session", e);
          }
          // Capture/start failure before ownership was acquired degrades to tick-only. The debug
          // session remains operational rather than becoming FAILED with no end time.
          recordingFailureReason = errorMsg;
          recordingEnabled = false;
          log.warn(
              "Recording unavailable for debug session {}; continuing tick-only: {}",
              sessionId,
              errorMsg);
        }
      }
    } else if (Boolean.TRUE.equals(recordingEnabled)) {
      recordingFailureReason = recordingBlockedReason(triggerSource);
      log.warn(
          "Auto-recording blocked: source={}, reason={}", triggerSource, recordingFailureReason);
      recordingEnabled = false;
    }

    if (contextSnapshot != null && recordingId == null) {
      // A canonical capture may have tick evidence without a video recorder. Persist its
      // identity regardless; the video catalog explicitly shows the absent video.
      recordingId = "capture_" + UUID.randomUUID().toString();
      var row =
          new RecordingSession(
              recordingId,
              session.getAccountName(),
              session.getScriptName(),
              session.getStartTime(),
              toSessionTrigger(triggerSource),
              null);
      row.captureProvenance(provenance);
      row.bindRunContext(contexts.bind(contextSnapshot, recordingId, sessionId), "CAPTURED");
      recordingSessionRepository.save(row);
      session.setRecordingId(recordingId);
      recordingEnabled = false;
    }
    // Transition to RECORDING state on success
    session.transitionTo(DebugSessionLifecycleState.RECORDING);
    try {
      session = sessionRepository.save(session);
    } catch (Exception e) {
      if (recordingStartedByOperation) {
        stopRecordingForRollback(recordingId);
      }
      throw new SessionStartRollbackException("Failed to persist debug session start", e);
    }

    log.info(
        "Debug session transitioned to RECORDING: sessionId={}, state={}, recording={}",
        sessionId,
        session.getLifecycleState(),
        recordingId);

    SessionStartRollbackToken token =
        new SessionStartRollbackToken(
            sessionId,
            createdNewSession,
            recordingStartedByOperation ? recordingId : null,
            previousRecordingId,
            previousStartTime,
            previousLifecycleState);
    SessionStartResponse response =
        new SessionStartResponse(
            sessionId, recordingEnabled, recordingId, startTick, recordingFailureReason);
    response.setSelectedTickBatchVersion(contextSnapshot == null ? 2 : 3);
    response.setSelectedAckVersion(1);
    if (contextSnapshot != null && recordingId != null)
      response.setRunContext(contexts.context(recordingId));
    return new SessionStartOutcome(
        response, token, createdNewSession, reusedPreferredSession, recordingStartedByOperation);
  }

  /**
   * Compensates a session-start operation described by {@code rollbackToken}, idempotently.
   *
   * <p>For a newly created session, stops only the recording this operation started (if any),
   * finalizes the corresponding {@link RecordingSession} row, and finalizes the debug session. For
   * a reused session, never finalizes the pre-existing session; it stops only the operation-owned
   * recording and restores the prior recording id, start time, and lifecycle state, refusing to
   * overwrite newer state via a compare-and-set on the recording id.
   *
   * @throws SessionStartRollbackException when compensation is incomplete (e.g. the recording
   *     association changed or recording stop failed); the caller must retain retry ownership.
   */
  @Transactional
  public void rollbackSessionStart(SessionStartRollbackToken rollbackToken, String reason) {
    if (rollbackToken == null
        || rollbackToken.sessionId() == null
        || rollbackToken.sessionId().isBlank()) {
      return;
    }
    Optional<DebugSession> sessionOpt =
        sessionRepository.findBySessionId(rollbackToken.sessionId());
    if (sessionOpt.isEmpty()) {
      // Nothing to compensate; idempotent no-op.
      log.warn("Start rollback found no debug session for token; nothing to compensate");
      return;
    }
    DebugSession session = sessionOpt.get();

    if (rollbackToken.createdNewSession()) {
      rollbackNewSession(session, rollbackToken, reason);
    } else {
      rollbackReusedSession(session, rollbackToken, reason);
    }
  }

  /**
   * Compensates a newly created session: stop the operation-owned recording, finalize its {@link
   * RecordingSession} row, and finalize the debug session idempotently.
   */
  private void rollbackNewSession(
      DebugSession session, SessionStartRollbackToken token, String reason) {
    String operationRecordingId = token.recordingIdStartedByOperation();
    if (operationRecordingId != null && !operationRecordingId.isBlank()) {
      stopRecordingForRollback(operationRecordingId);
      finalizeRecordingSessionForRollback(operationRecordingId);
    }
    // Finalize the newly created session (idempotent: skip when already terminal).
    if (session.getLifecycleState() != DebugSessionLifecycleState.FINALIZED
        && session.getLifecycleState() != DebugSessionLifecycleState.FAILED) {
      session.setEndTime(LocalDateTime.now());
      snapshotRepository
          .findMaxTickBySessionId(session.getSessionId())
          .ifPresent(session::setEndTick);
      session.transitionTo(DebugSessionLifecycleState.FINALIZED);
      sessionRepository.save(session);
      log.info("Start rollback finalized newly created session: reason={}", reason);
    }
  }

  /**
   * Compensates a reused session: stop only the operation-owned recording and restore the prior
   * recording id, start time, and lifecycle state, refusing to overwrite newer state.
   */
  private void rollbackReusedSession(
      DebugSession session, SessionStartRollbackToken token, String reason) {
    String operationRecordingId = token.recordingIdStartedByOperation();
    String currentRecordingId = session.getRecordingId();

    if (operationRecordingId != null && !operationRecordingId.isBlank()) {
      if (operationRecordingId.equals(currentRecordingId)) {
        if (session.getLifecycleState() != DebugSessionLifecycleState.RECORDING) {
          throw new SessionStartRollbackException(
              "Session lifecycle changed; refusing to overwrite newer state");
        }
        // Still owned by this operation: stop and restore.
        stopRecordingForRollback(operationRecordingId);
        finalizeRecordingSessionForRollback(operationRecordingId);
        session.setRecordingId(token.previousRecordingId());
        session.setStartTime(token.previousStartTime());
        session.setLifecycleState(token.previousLifecycleState());
        sessionRepository.save(session);
        log.info(
            "Start rollback restored reused session recording/lifecycle state: reason={}", reason);
      } else if (Objects.equals(token.previousRecordingId(), currentRecordingId)) {
        // A prior rollback already restored the state; idempotent no-op.
        log.debug("Start rollback already applied for reused session; nothing to do");
      } else {
        // Newer state replaced the operation-owned recording; refuse to overwrite it.
        throw new SessionStartRollbackException(
            "Recording association changed; refusing to overwrite newer state");
      }
    } else {
      // No operation-owned recording: the only mutation was the lifecycle transition. Restore the
      // prior lifecycle state only while the operation's expected RECORDING state is still present.
      // A concurrent stop/failure must never be turned back into an active session.
      if (session.getLifecycleState() != DebugSessionLifecycleState.RECORDING
          && session.getLifecycleState() != token.previousLifecycleState()) {
        throw new SessionStartRollbackException(
            "Session lifecycle changed; refusing to overwrite newer state");
      }
      session.setLifecycleState(token.previousLifecycleState());
      sessionRepository.save(session);
      log.debug("Start rollback restored reused session lifecycle state: reason={}", reason);
    }
  }

  /** Stops the operation-owned recording, treating an already-stopped recording as a no-op. */
  private void stopRecordingForRollback(String recordingId) {
    try {
      String currentRecordingId = recordingService.getCurrentRecordingId();
      if (currentRecordingId == null) {
        return;
      }
      if (!recordingId.equals(currentRecordingId)
          || !recordingService.stopRecordingIfCurrent(recordingId)) {
        throw new SessionStartRollbackException(
            "Operation-owned recording is no longer the current recording");
      }
    } catch (Exception e) {
      if (e instanceof SessionStartRollbackException rollbackException) {
        throw rollbackException;
      }
      log.error("Failed to stop recording during rollback: {}", e.getMessage(), e);
      throw new SessionStartRollbackException("Failed to stop recording during rollback", e);
    }
  }

  /** Finalizes the {@link RecordingSession} row for an operation-owned recording, idempotently. */
  private void finalizeRecordingSessionForRollback(String recordingId) {
    if (recordingId == null || recordingId.isBlank()) {
      return;
    }
    recordingSessionRepository
        .findByRecordingId(recordingId)
        .ifPresent(
            rec -> {
              if (rec.getEndTime() == null) {
                rec.setEndTime(LocalDateTime.now());
                recordingSessionRepository.save(rec);
              }
            });
  }

  /** Authoritative result of a session start. Internal only; never serialized. */
  public record SessionStartOutcome(
      SessionStartResponse response,
      SessionStartRollbackToken rollbackToken,
      boolean createdNewSession,
      boolean reusedPreferredSession,
      boolean recordingStartedByOperation) {}

  /** Immutable description of the mutations a session start performed, for idempotent rollback. */
  public record SessionStartRollbackToken(
      String sessionId,
      boolean createdNewSession,
      String recordingIdStartedByOperation,
      String previousRecordingId,
      LocalDateTime previousStartTime,
      DebugSessionLifecycleState previousLifecycleState) {}

  /** Result of resolving (or creating) the canonical debug session for a start. */
  private record ResolveResult(
      DebugSession session, boolean createdNewSession, boolean reusedPreferredSession) {}

  /** Capture-outbox entry point that keeps recorder I/O outside the dispatcher's transaction. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SessionStartResponse startCaptureSession(
      String accountName,
      String scriptName,
      Boolean recordingEnabled,
      RecordingTriggerSource triggerSource,
      String preferredSessionId) {
    return startSessionWithOutcome(
            accountName,
            scriptName,
            recordingEnabled,
            triggerSource,
            preferredSessionId,
            scriptName != null && scriptName.matches("[A-Z][A-Z0-9_]{0,159}") ? scriptName : null,
            null,
            null)
        .response();
  }

  /**
   * Stop a debug session.
   *
   * @param sessionId the session ID to stop
   * @param endTick the final tick number
   */
  @Transactional
  public void stopSession(String sessionId, Long endTick) {
    stopSession(sessionId, endTick, true);
  }

  /**
   * Stop a debug session with optional recording stop.
   *
   * @param sessionId the session ID to stop
   * @param endTick the final tick number
   * @param stopRecording whether to stop the video recording
   */
  @Transactional
  public void stopSession(String sessionId, Long endTick, boolean stopRecording) {
    if (canonicalFrames != null) canonicalFrames.close(sessionId, "EXPLICIT_STOP");
    Optional<DebugSession> sessionOpt = sessionRepository.findBySessionId(sessionId);

    if (sessionOpt.isEmpty()) {
      log.warn("Attempted to stop non-existent session: {}", sessionId);
      return;
    }

    DebugSession session = sessionOpt.get();

    // Idempotency: if already finalized or failed, still attempt recording metadata backfill
    // (handles edge case where SESSION_STOP ran but recording metadata update failed)
    if (session.getLifecycleState() == DebugSessionLifecycleState.FINALIZED
        || session.getLifecycleState() == DebugSessionLifecycleState.FAILED) {
      log.info(
          "Session {} already in terminal state {} — skipping lifecycle transition, "
              + "attempting recording metadata backfill",
          sessionId,
          session.getLifecycleState());
      backfillRecordingMetadataIfMissing(session);
      return;
    }
    boolean hasFailure = false;

    // Wrap early DB operations in try-catch with SESSION_STOP_FAILED
    try {
      session.setEndTime(LocalDateTime.now());

      if (endTick != null) {
        session.setEndTick(endTick);
      } else {
        // If no end tick provided, try to get the last tick from snapshots
        snapshotRepository.findMaxTickBySessionId(sessionId).ifPresent(session::setEndTick);
      }
    } catch (Exception e) {
      hasFailure = true;
      String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      session.markFailed(
          DebugSessionFailureCode.SESSION_STOP_FAILED,
          "Failed to set session end time/tick: " + errorMsg,
          getStackTraceString(e));
      log.error("Session {} early stop failed: {}", sessionId, errorMsg);

      // Try to save the failure state
      try {
        sessionRepository.save(session);
      } catch (Exception saveEx) {
        log.error("Failed to save session {} failure state: {}", sessionId, saveEx.getMessage());
      }
      return; // Exit early - don't continue with recording stop
    }

    // Stop video recording if active and requested
    if (stopRecording
        && session.getRecordingId() != null
        && !session.getRecordingId().startsWith("capture_")) {
      try {
        RecordingMetadata metadata = recordingService.stopRecording();
        if (metadata != null) {
          // Update RecordingSession with video path and metadata
          try {
            persistRecordingMetadata(session, metadata);
            log.info(
                "Stopped recording {} for debug session {}", session.getRecordingId(), sessionId);
          } catch (Exception e) {
            hasFailure = true;
            String errorMsg =
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            session.markFailed(
                DebugSessionFailureCode.REC_METADATA_UPDATE_FAILED,
                "Failed to update recording metadata: " + errorMsg,
                getStackTraceString(e));
            log.error(
                "Recording metadata update failed for session {}: code={}, reason={}",
                sessionId,
                session.getFailureCode(),
                errorMsg);
          }
        }
      } catch (Exception e) {
        hasFailure = true;
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        session.markFailed(
            DebugSessionFailureCode.REC_STOP_FAILED,
            "Failed to stop recording: " + errorMsg,
            getStackTraceString(e));
        log.error(
            "Recording stop failed for session {}: code={}, reason={}",
            sessionId,
            session.getFailureCode(),
            errorMsg);
      }
    }

    // Final session finalization with try-catch
    try {
      if (session.getRecordingId() != null) {
        maybeRelinkRecordingToTickBearingSession(session);
      }

      if (!hasFailure) {
        session.transitionTo(DebugSessionLifecycleState.FINALIZED);
        log.info("Session {} transition: RECORDING -> FINALIZED", sessionId);
      }

      sessionRepository.save(session);

    } catch (Exception e) {
      String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      session.markFailed(
          DebugSessionFailureCode.SESSION_FINALIZE_FAILED,
          "Failed to finalize session: " + errorMsg,
          getStackTraceString(e));
      sessionRepository.save(session);

      log.error("Session {} finalization failed: {}", sessionId, errorMsg);
    }

    long duration =
        java.time.Duration.between(session.getStartTime(), session.getEndTime()).toSeconds();
    long tickCount =
        session.getEndTick() != null ? session.getEndTick() - session.getStartTick() : 0;

    log.info(
        "Stopped debug session: sessionId={}, state={}, duration={}s, tickCount={}",
        sessionId,
        session.getLifecycleState(),
        duration,
        tickCount);

    if (session.getRecordingId() != null && !session.getRecordingId().isBlank()) {
      long snapshotCount = snapshotRepository.countBySessionId(sessionId);
      if (snapshotCount == 0) {
        log.warn(
            "Recording {} finalized with zero tick snapshots (sessionId={})",
            session.getRecordingId(),
            sessionId);
      }
    }
  }

  /** Capture-outbox stop entry point that keeps recorder stop outside a database transaction. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void stopCaptureSession(String sessionId, Long endTick, boolean stopRecording) {
    stopSession(sessionId, endTick, stopRecording);
  }

  /**
   * Implicitly finalize a debug session when Micro6 disconnects without sending SESSION_STOP.
   *
   * <p>This is the fallback path triggered by WebSocket disconnect or transport error. It mirrors
   * {@link #stopSession(String, Long, boolean)} but uses distinct logging to differentiate implicit
   * finalization from explicit SESSION_STOP.
   *
   * @param sessionId the debug session ID to finalize
   * @param cause human-readable cause ("disconnect" or "transport error") for logging
   */
  @Transactional
  public void finalizeSessionOnDisconnect(String sessionId, String cause) {
    if (canonicalFrames != null) canonicalFrames.close(sessionId, "DISCONNECT");
    Optional<DebugSession> sessionOpt = sessionRepository.findBySessionId(sessionId);

    if (sessionOpt.isEmpty()) {
      log.warn("Attempted to finalize non-existent session on {}: {}", cause, sessionId);
      return;
    }

    DebugSession session = sessionOpt.get();

    // Idempotency: if already finalized or failed, still attempt recording metadata backfill
    if (session.getLifecycleState() == DebugSessionLifecycleState.FINALIZED
        || session.getLifecycleState() == DebugSessionLifecycleState.FAILED) {
      log.info(
          "Session {} already in terminal state {} on {} — skipping lifecycle transition, "
              + "attempting recording metadata backfill",
          sessionId,
          session.getLifecycleState(),
          cause);
      backfillRecordingMetadataIfMissing(session);
      return;
    }

    log.info(
        "Implicit session stop on {}: sessionId={}, currentState={}",
        cause,
        sessionId,
        session.getLifecycleStateOrDefault());

    try {
      session.setEndTime(LocalDateTime.now());
      // Try to get the last tick from snapshots
      snapshotRepository.findMaxTickBySessionId(sessionId).ifPresent(session::setEndTick);

      // Stop recording if active
      if (session.getRecordingId() != null && !session.getRecordingId().startsWith("capture_")) {
        try {
          RecordingMetadata metadata = recordingService.stopRecording();
          if (metadata != null) {
            persistRecordingMetadata(session, metadata);
            log.info(
                "Implicit stop on {}: finalized recording {} for session {}",
                cause,
                session.getRecordingId(),
                sessionId);
          }
        } catch (Exception e) {
          String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
          session.markFailed(
              DebugSessionFailureCode.REC_STOP_FAILED,
              "Failed to stop recording on " + cause + ": " + errorMsg,
              getStackTraceString(e));
          log.error("Recording stop failed on {} for session {}: {}", cause, sessionId, errorMsg);
        }
      }

      if (session.getRecordingId() != null) {
        maybeRelinkRecordingToTickBearingSession(session);
      }

      if (session.getLifecycleState() != DebugSessionLifecycleState.FAILED) {
        session.transitionTo(DebugSessionLifecycleState.FINALIZED);
        log.info("Session {} transitioned to FINALIZED via implicit stop on {}", sessionId, cause);
      }

      sessionRepository.save(session);

    } catch (Exception e) {
      String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      session.markFailed(
          DebugSessionFailureCode.SESSION_DISCONNECT_FINALIZE_FAILED,
          "Failed to finalize session on " + cause + ": " + errorMsg,
          getStackTraceString(e));
      sessionRepository.save(session);
      log.error("Session {} implicit finalization on {} failed: {}", sessionId, cause, errorMsg);
    }

    long duration =
        session.getStartTime() != null && session.getEndTime() != null
            ? java.time.Duration.between(session.getStartTime(), session.getEndTime()).toSeconds()
            : 0;
    long tickCount =
        session.getEndTick() != null ? session.getEndTick() - session.getStartTick() : 0;

    log.info(
        "Implicitly finalized session on {}: sessionId={}, state={}, duration={}s, tickCount={}",
        cause,
        sessionId,
        session.getLifecycleState(),
        duration,
        tickCount);
  }

  /** Get a debug session by ID. */
  public Optional<DebugSession> getSession(String sessionId) {
    return sessionRepository.findBySessionId(sessionId);
  }

  private boolean shouldStartRecording(RecordingTriggerSource triggerSource) {
    return switch (triggerSource) {
      case MANUAL -> true;
      case TASK_AUTO -> recordingService.isTaskAutoRecordEnabled();
      case MICRO6_AUTO -> recordingService.isAutoRecordEnabled();
    };
  }

  private String recordingBlockedReason(RecordingTriggerSource triggerSource) {
    return switch (triggerSource) {
      case TASK_AUTO -> "Task auto-recording is disabled";
      case MICRO6_AUTO -> "Generic Micro6 auto-recording is disabled";
      case MANUAL -> "Recording was not permitted";
    };
  }

  /** Get all active sessions. */
  public List<DebugSession> getActiveSessions() {
    return sessionRepository.findByEndTimeIsNullOrderByStartTimeDesc();
  }

  /** Get all sessions for an account. */
  public List<DebugSession> getSessionsByAccount(String accountName) {
    return sessionRepository.findByAccountNameOrderByStartTimeDesc(accountName);
  }

  /** Get all sessions. */
  public List<DebugSession> getAllSessions() {
    return sessionRepository.findAllByOrderByStartTimeDesc();
  }

  /**
   * Attempts to repair broken recording links by moving recordingId from a no-tick session to a
   * tick-bearing sibling session in the same time window.
   *
   * @return true when relink was applied
   */
  @Transactional
  public boolean repairRecordingLink(String recordingId) {
    Optional<DebugSession> currentOpt = sessionRepository.findByRecordingId(recordingId);
    if (currentOpt.isEmpty()) {
      return false;
    }
    DebugSession current = currentOpt.get();
    return maybeRelinkRecordingToTickBearingSession(current);
  }

  /**
   * Returns true when a debug session with the given id exists and is still active (has no end
   * time). Used by the tick-stream WebSocket handler to decide whether a SESSION_START transaction
   * is reusing an already-active preferred session (and must therefore not finalize it on rollback)
   * or is creating a fresh session.
   */
  public boolean isSessionActive(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return false;
    }
    return sessionRepository.findBySessionId(sessionId).map(DebugSession::isActive).orElse(false);
  }

  private ResolveResult resolveOrCreateSession(
      String accountName, String scriptName, String preferredSessionId) {
    if (preferredSessionId != null && !preferredSessionId.isBlank()) {
      Optional<DebugSession> preferred = sessionRepository.findBySessionId(preferredSessionId);
      if (preferred.isPresent()
          && preferred.get().isActive()
          && preferred.get().getLifecycleState() != DebugSessionLifecycleState.FAILED
          && preferred.get().getLifecycleState() != DebugSessionLifecycleState.FINALIZED) {
        DebugSession active = preferred.get();
        log.info("Binding recording to preferred active session {}", active.getSessionId());
        return new ResolveResult(active, false, true);
      }
      log.warn("Preferred session {} not active/found, falling back", preferredSessionId);
    }

    String sessionId = "debug_" + UUID.randomUUID().toString().replace("-", "");
    DebugSession created =
        new DebugSession(sessionId, accountName, scriptName, LocalDateTime.now(), 0L);
    created = sessionRepository.save(created);
    log.info(
        "Created debug session in STARTING state: sessionId={}, account={}, script={}",
        sessionId,
        accountName,
        scriptName);
    return new ResolveResult(created, true, false);
  }

  private boolean maybeRelinkRecordingToTickBearingSession(DebugSession current) {
    String recordingId = current.getRecordingId();
    if (recordingId == null || recordingId.isBlank()) {
      return false;
    }

    long currentTickCount = snapshotRepository.countBySessionId(current.getSessionId());
    if (currentTickCount > 0) {
      return false;
    }

    LocalDateTime start =
        current.getStartTime() != null
            ? current.getStartTime().minusMinutes(5)
            : LocalDateTime.now().minusMinutes(5);
    LocalDateTime end =
        current.getEndTime() != null
            ? current.getEndTime().plusMinutes(5)
            : LocalDateTime.now().plusMinutes(1);

    DebugSession best = null;
    long bestTickCount = 0;
    for (DebugSession candidate :
        sessionRepository.findByStartTimeBetweenOrderByStartTimeDesc(start, end)) {
      if (candidate.getId().equals(current.getId())) {
        continue;
      }
      if (candidate.getRecordingId() != null && !candidate.getRecordingId().isBlank()) {
        continue;
      }
      long ticks = snapshotRepository.countBySessionId(candidate.getSessionId());
      if (ticks > bestTickCount) {
        bestTickCount = ticks;
        best = candidate;
      }
    }

    if (best == null || bestTickCount == 0) {
      return false;
    }

    current.setRecordingId(null);
    best.setRecordingId(recordingId);
    sessionRepository.save(current);
    sessionRepository.save(best);
    log.warn(
        "Relinked recording {} from session {} (0 ticks) to session {} ({} ticks)",
        recordingId,
        current.getSessionId(),
        best.getSessionId(),
        bestTickCount);
    return true;
  }

  /**
   * Convert a DebugSession entity to a summary DTO.
   *
   * <p>NOTE: This method runs 3 separate count queries (N+1 problem). For bulk operations, use
   * {@link #toSummaries(List)} instead.
   *
   * @param session the debug session
   * @return session summary with counts
   */
  public DebugSessionSummary toSummary(DebugSession session) {
    Long tickCount = snapshotRepository.countBySessionId(session.getSessionId());
    Long eventCount = tickLogRepository.countBySessionId(session.getSessionId());
    Long failureMarkerCount =
        timelineMarkerRepository.countFailuresBySessionId(session.getSessionId());
    Boolean hasEvents = eventCount > 0;

    return new DebugSessionSummary(
        session.getSessionId(),
        session.getAccountName(),
        session.getScriptName(),
        session.getStartTime(),
        session.getEndTime(),
        session.getStartTick(),
        session.getEndTick(),
        session.getRecordingId(),
        session.isActive(),
        tickCount,
        eventCount,
        failureMarkerCount,
        hasEvents,
        session.getLifecycleStateOrDefault(),
        session.getFailureCode(),
        session.getFailureReason());
  }

  /**
   * Convert multiple DebugSession entities to summary DTOs using grouped count queries.
   *
   * <p>This method fetches snapshot, log, and failure marker counts in three separate grouped
   * queries, avoiding the large row explosion of a multi-table LEFT JOIN aggregate.
   *
   * @param sessions the debug sessions to convert
   * @return list of session summaries with counts
   */
  public List<DebugSessionSummary> toSummaries(List<DebugSession> sessions) {
    if (sessions.isEmpty()) {
      return List.of();
    }

    List<String> sessionIds = sessions.stream().map(DebugSession::getSessionId).toList();

    Map<String, Long> tickCounts =
        snapshotRepository.countBySessionIdIn(sessionIds).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    SessionCountProjection::getSessionId,
                    SessionCountProjection::getCount,
                    (a, b) -> a));
    Map<String, Long> eventCounts =
        tickLogRepository.countBySessionIdIn(sessionIds).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    SessionCountProjection::getSessionId,
                    SessionCountProjection::getCount,
                    (a, b) -> a));
    Map<String, Long> failureMarkerCounts =
        timelineMarkerRepository.countFailuresBySessionIdIn(sessionIds).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    SessionCountProjection::getSessionId,
                    SessionCountProjection::getCount,
                    (a, b) -> a));

    return sessions.stream()
        .map(
            session -> {
              String sessionId = session.getSessionId();
              Long tickCount = tickCounts.getOrDefault(sessionId, 0L);
              Long eventCount = eventCounts.getOrDefault(sessionId, 0L);
              Long failureCount = failureMarkerCounts.getOrDefault(sessionId, 0L);
              return new DebugSessionSummary(
                  sessionId,
                  session.getAccountName(),
                  session.getScriptName(),
                  session.getStartTime(),
                  session.getEndTime(),
                  session.getStartTick(),
                  session.getEndTick(),
                  session.getRecordingId(),
                  session.isActive(),
                  tickCount,
                  eventCount,
                  failureCount,
                  eventCount > 0,
                  session.getLifecycleStateOrDefault(),
                  session.getFailureCode(),
                  session.getFailureReason());
            })
        .toList();
  }

  /**
   * Map a {@link RecordingTriggerSource} to the corresponding {@link
   * RecordingSession.SessionTrigger}.
   */
  private RecordingSession.SessionTrigger toSessionTrigger(RecordingTriggerSource src) {
    return switch (src) {
      case MICRO6_AUTO -> RecordingSession.SessionTrigger.MICRO6_AUTO;
      case TASK_AUTO -> RecordingSession.SessionTrigger.AUTO_START;
      default -> RecordingSession.SessionTrigger.MANUAL;
    };
  }

  /**
   * Delete old sessions for retention cleanup.
   *
   * @param cutoffTime sessions ending before this time will be deleted
   */
  @Transactional
  public void deleteOldSessions(LocalDateTime cutoffTime) {
    List<DebugSession> oldSessions =
        sessionRepository.findByEndTimeBeforeOrderByEndTimeDesc(cutoffTime);

    for (DebugSession session : oldSessions) {
      // Delete related data (cascade should handle this with proper entity setup)
      sessionRepository.delete(session);
    }

    log.info("Deleted {} old debug sessions (ended before {})", oldSessions.size(), cutoffTime);
  }

  /**
   * Update session tick bounds (startTick and endTick).
   *
   * @param sessionId the session ID to update
   * @param tick the tick number to update bounds with
   */
  @Transactional
  public void updateSessionTickBounds(String sessionId, long tick) {
    Optional<DebugSession> sessionOpt = sessionRepository.findBySessionId(sessionId);

    if (sessionOpt.isEmpty()) {
      log.warn("Attempted to update tick bounds for non-existent session: {}", sessionId);
      return;
    }

    DebugSession session = sessionOpt.get();
    boolean updated = false;

    // Set startTick if it's null or 0 (only on first tick)
    if (session.getStartTick() == null || session.getStartTick() == 0) {
      session.setStartTick(tick);
      updated = true;
      log.debug("Set startTick for session {}: {}", sessionId, tick);
    }

    // Update endTick to max(existingEndTick, tick)
    Long currentEndTick = session.getEndTick();
    if (currentEndTick == null || tick > currentEndTick) {
      session.setEndTick(tick);
      updated = true;
      log.debug("Updated endTick for session {}: {}", sessionId, tick);
    }

    if (updated) {
      sessionRepository.save(session);
    }
  }

  /**
   * Persist recording metadata (video path, duration, frames, fps) onto the linked {@link
   * RecordingSession} row.
   *
   * <p>Extracted as a shared helper used by both explicit {@link #stopSession} and implicit {@link
   * #finalizeSessionOnDisconnect} paths.
   */
  private void persistRecordingMetadata(DebugSession session, RecordingMetadata metadata) {
    if (metadata == null || session.getRecordingId() == null) {
      return;
    }
    recordingSessionRepository
        .findByRecordingId(session.getRecordingId())
        .ifPresent(
            recSession -> {
              recSession.setVideoPath(metadata.getFilePath());
              recSession.setEndTime(LocalDateTime.now());
              recSession.setDurationMs(metadata.getDuration());
              recSession.setFrameCount(metadata.getFrameCount());
              recSession.setFps(metadata.getFps());
              recordingSessionRepository.save(recSession);
            });
  }

  /**
   * Attempt to backfill missing recording metadata when a session is already in a terminal state
   * but the linked {@link RecordingSession} row has null video_path or end_time.
   *
   * <p>This handles the edge case where SESSION_STOP ran but the recording metadata update failed
   * (e.g., recording stop threw, or the DB update was lost). It checks disk for the .mp4 file and
   * backfills what it can.
   */
  private void backfillRecordingMetadataIfMissing(DebugSession session) {
    String recordingId = session.getRecordingId();
    if (recordingId == null || recordingId.isBlank()) {
      return;
    }

    Optional<RecordingSession> recOpt = recordingSessionRepository.findByRecordingId(recordingId);
    if (recOpt.isEmpty()) {
      log.debug("No RecordingSession row for recordingId={} — nothing to backfill", recordingId);
      return;
    }

    RecordingSession rec = recOpt.get();
    boolean needsBackfill =
        rec.getVideoPath() == null || rec.getVideoPath().isBlank() || rec.getEndTime() == null;

    if (!needsBackfill) {
      log.debug(
          "Recording metadata already complete for recordingId={}, sessionId={}",
          recordingId,
          session.getSessionId());
      return;
    }

    // Check if the .mp4 file exists on disk
    Path videoFile = Paths.get(recordingConfig.getOutputDirectory(), recordingId + ".mp4");

    if (!Files.exists(videoFile)) {
      log.warn(
          "Orphan-repair: recording file not found for recordingId={}, sessionId={} — "
              + "expected at {} — cannot backfill",
          recordingId,
          session.getSessionId(),
          videoFile.toAbsolutePath());
      return;
    }

    try {
      long fileSize = Files.size(videoFile);
      if (fileSize == 0) {
        log.warn(
            "Orphan-repair: recording file is empty for recordingId={}, sessionId={} — "
                + "skipping backfill",
            recordingId,
            session.getSessionId());
        return;
      }

      // Backfill what we can from the file and session metadata
      if (rec.getVideoPath() == null || rec.getVideoPath().isBlank()) {
        rec.setVideoPath(videoFile.toAbsolutePath().toString());
      }
      if (rec.getEndTime() == null) {
        rec.setEndTime(session.getEndTime() != null ? session.getEndTime() : LocalDateTime.now());
      }
      if (rec.getDurationMs() == null
          && session.getStartTime() != null
          && rec.getEndTime() != null) {
        rec.setDurationMs(
            java.time.Duration.between(session.getStartTime(), rec.getEndTime()).toMillis());
      }

      recordingSessionRepository.save(rec);

      log.info(
          "Orphan-repair: backfilled recording metadata for recordingId={}, "
              + "sessionId={}, videoPath={}, endTime={}, durationMs={}",
          recordingId,
          session.getSessionId(),
          rec.getVideoPath(),
          rec.getEndTime(),
          rec.getDurationMs());

    } catch (Exception e) {
      log.error(
          "Orphan-repair: failed to backfill recording metadata for recordingId={}, "
              + "sessionId={}: {}",
          recordingId,
          session.getSessionId(),
          e.getMessage(),
          e);
    }
  }

  /**
   * Scan for and repair orphaned recording sessions where the .mp4 exists on disk but {@code
   * recording_sessions.video_path} and {@code end_time} remain null.
   *
   * <p>Intended as an admin repair path for sessions orphaned before disconnect finalization was
   * implemented. Scans recent incomplete rows and backfills metadata from on-disk files.
   *
   * @return number of sessions repaired
   */
  @Transactional
  public int repairOrphanedRecordingSessions() {
    // Find RecordingSession rows with null video_path or null end_time, limited to recent ones
    List<RecordingSession> incomplete =
        recordingSessionRepository.findAllByOrderByStartTimeDesc().stream()
            .filter(r -> r.getVideoPath() == null || r.getEndTime() == null)
            .limit(50) // Safety: don't scan entire history
            .toList();

    if (incomplete.isEmpty()) {
      log.info("Orphan-repair: no incomplete RecordingSession rows found");
      return 0;
    }

    int repaired = 0;
    for (RecordingSession rec : incomplete) {
      String recordingId = rec.getRecordingId();
      if (recordingId == null || recordingId.isBlank()) {
        continue;
      }

      // Check if the .mp4 file exists on disk
      Path videoFile = Paths.get(recordingConfig.getOutputDirectory(), recordingId + ".mp4");

      if (!Files.exists(videoFile)) {
        log.debug("Orphan-repair scan: no .mp4 for recordingId={}, skipping", recordingId);
        continue;
      }

      try {
        long fileSize = Files.size(videoFile);
        if (fileSize == 0) {
          log.warn("Orphan-repair scan: empty .mp4 for recordingId={}, skipping", recordingId);
          continue;
        }

        // Find the debug session linked to this recording
        Optional<DebugSession> sessionOpt = sessionRepository.findByRecordingId(recordingId);

        // Backfill RecordingSession metadata
        if (rec.getVideoPath() == null || rec.getVideoPath().isBlank()) {
          rec.setVideoPath(videoFile.toAbsolutePath().toString());
        }
        if (rec.getEndTime() == null) {
          rec.setEndTime(sessionOpt.map(DebugSession::getEndTime).orElse(LocalDateTime.now()));
        }
        if (rec.getDurationMs() == null && rec.getStartTime() != null && rec.getEndTime() != null) {
          rec.setDurationMs(
              java.time.Duration.between(rec.getStartTime(), rec.getEndTime()).toMillis());
        }
        recordingSessionRepository.save(rec);

        // If debug session exists, mark it finalized if it was stuck in RECORDING
        sessionOpt.ifPresent(
            session -> {
              if (session.isActive()
                  || session.getLifecycleState() == DebugSessionLifecycleState.RECORDING) {
                session.setEndTime(rec.getEndTime());
                if (session.getLifecycleState() != DebugSessionLifecycleState.FAILED) {
                  session.transitionTo(DebugSessionLifecycleState.FINALIZED);
                }
                sessionRepository.save(session);
                log.info(
                    "Orphan-repair: marked session {} as FINALIZED after backfill",
                    session.getSessionId());
              }
            });

        repaired++;
        log.info(
            "Orphan-repair: backfilled recordingId={}, videoPath={}, endTime={}",
            recordingId,
            rec.getVideoPath(),
            rec.getEndTime());

      } catch (Exception e) {
        log.error("Orphan-repair: failed for recordingId={}: {}", recordingId, e.getMessage(), e);
      }
    }

    log.info("Orphan-repair complete: {}/{} incomplete rows repaired", repaired, incomplete.size());
    return repaired;
  }

  /**
   * Repair a single orphaned recording session by its recording ID.
   *
   * @param recordingId the recording ID to repair
   * @return true if the session was repaired, false if no repair was needed or possible
   */
  @Transactional
  public boolean repairOrphanedRecordingSession(String recordingId) {
    if (recordingId == null || recordingId.isBlank()) {
      return false;
    }

    Optional<RecordingSession> recOpt = recordingSessionRepository.findByRecordingId(recordingId);
    if (recOpt.isEmpty()) {
      log.warn("Orphan-repair: no RecordingSession row for recordingId={}", recordingId);
      return false;
    }

    RecordingSession rec = recOpt.get();

    // Check if the .mp4 file exists on disk
    Path videoFile = Paths.get(recordingConfig.getOutputDirectory(), recordingId + ".mp4");

    if (!Files.exists(videoFile)) {
      log.warn(
          "Orphan-repair: no .mp4 for recordingId={} at {} — cannot repair",
          recordingId,
          videoFile.toAbsolutePath());
      return false;
    }

    try {
      long fileSize = Files.size(videoFile);
      if (fileSize == 0) {
        log.warn("Orphan-repair: empty .mp4 for recordingId={} — cannot repair", recordingId);
        return false;
      }

      Optional<DebugSession> sessionOpt = sessionRepository.findByRecordingId(recordingId);

      if (rec.getVideoPath() == null || rec.getVideoPath().isBlank()) {
        rec.setVideoPath(videoFile.toAbsolutePath().toString());
      }
      if (rec.getEndTime() == null) {
        rec.setEndTime(sessionOpt.map(DebugSession::getEndTime).orElse(LocalDateTime.now()));
      }
      if (rec.getDurationMs() == null && rec.getStartTime() != null && rec.getEndTime() != null) {
        rec.setDurationMs(
            java.time.Duration.between(rec.getStartTime(), rec.getEndTime()).toMillis());
      }
      recordingSessionRepository.save(rec);

      sessionOpt.ifPresent(
          session -> {
            if (session.isActive()
                || session.getLifecycleState() == DebugSessionLifecycleState.RECORDING) {
              session.setEndTime(rec.getEndTime());
              if (session.getLifecycleState() != DebugSessionLifecycleState.FAILED) {
                session.transitionTo(DebugSessionLifecycleState.FINALIZED);
              }
              sessionRepository.save(session);
            }
          });

      log.info(
          "Orphan-repair: repaired recordingId={}, videoPath={}, endTime={}",
          recordingId,
          rec.getVideoPath(),
          rec.getEndTime());
      return true;

    } catch (Exception e) {
      log.error("Orphan-repair: failed for recordingId={}: {}", recordingId, e.getMessage(), e);
      return false;
    }
  }

  /**
   * Extract a stack trace string from an exception, limited to 1500 characters.
   *
   * @param e the exception
   * @return stack trace string (limited to 1500 chars)
   */
  private String getStackTraceString(Exception e) {
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    String full = sw.toString();
    return full.length() > 1500 ? full.substring(0, 1500) + "..." : full;
  }
}

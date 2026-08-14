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
import com.chromascape.web.recording.exception.FrameCaptureException;
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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
    return startSession(
        accountName, scriptName, recordingEnabled, RecordingTriggerSource.MICRO6_AUTO, null);
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
    return startSession(accountName, scriptName, recordingEnabled, triggerSource, null);
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

    DebugSession session = resolveOrCreateSession(accountName, scriptName, preferredSessionId);
    String sessionId = session.getSessionId();
    Long startTick = session.getStartTick() != null ? session.getStartTick() : 0L;
    String recordingId = null;
    String recordingFailureReason = null;
    LocalDateTime sessionStartTime;

    // Optionally start video recording
    boolean shouldStartRecording =
        Boolean.TRUE.equals(recordingEnabled) && shouldStartRecording(triggerSource);

    if (shouldStartRecording) {
      try {
        recordingId = recordingService.startRecording(triggerSource);
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
                accountName,
                scriptName,
                sessionStartTime,
                toSessionTrigger(triggerSource),
                null);
        recordingSessionRepository.save(recordingSession);

        log.info("Started recording {} for debug session {}", recordingId, sessionId);
      } catch (Exception e) {
        // Recording start failed - degrade to a tick-only session instead of failing the session.
        // The session stays in RECORDING so tick ingestion and playback continue; only video is
        // lost. Failure diagnostics are recorded for later inspection.
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String failureContext =
            e instanceof FrameCaptureException ? e.getMessage() : getStackTraceString(e);
        session.recordFailureDiagnostic(
            DebugSessionFailureCode.REC_START_FAILED,
            "Recording failed to start; continuing debug session without video: " + errorMsg,
            failureContext);

        // Save the diagnostic state in the current transaction (will commit when method returns)
        try {
          sessionRepository.save(session);
          log.error(
              "Session {} recording start degraded: code={}, reason={}",
              sessionId,
              session.getFailureCode(),
              errorMsg);
        } catch (Exception saveEx) {
          log.error(
              "Failed to persist recording-start diagnostic for session {}: {}",
              sessionId,
              saveEx.getMessage());
        }

        // Return partial success - session continues tick-only (no recording linked)
        recordingEnabled = false;
        recordingId = null;
        recordingFailureReason = "Recording failed to start: " + errorMsg;
        log.warn(
            "Degrading session {} to tick-only mode after recording start failure: {}",
            sessionId,
            errorMsg);
      }
    } else if (Boolean.TRUE.equals(recordingEnabled)) {
      recordingFailureReason = recordingBlockedReason(triggerSource);
      log.warn(
          "Auto-recording blocked: source={}, reason={}", triggerSource, recordingFailureReason);
      recordingEnabled = false;
    }

    // Transition to RECORDING state on success
    session.transitionTo(DebugSessionLifecycleState.RECORDING);
    session = sessionRepository.save(session);

    log.info(
        "Debug session transitioned to RECORDING: sessionId={}, state={}, recording={}",
        sessionId,
        session.getLifecycleState(),
        recordingId);

    return new SessionStartResponse(
        sessionId, recordingEnabled, recordingId, startTick, recordingFailureReason);
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
    if (stopRecording && session.getRecordingId() != null) {
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
      if (session.getRecordingId() != null) {
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

  private DebugSession resolveOrCreateSession(
      String accountName, String scriptName, String preferredSessionId) {
    if (preferredSessionId != null && !preferredSessionId.isBlank()) {
      Optional<DebugSession> preferred = sessionRepository.findBySessionId(preferredSessionId);
      if (preferred.isPresent() && preferred.get().isActive()) {
        DebugSession active = preferred.get();
        log.info("Binding recording to preferred active session {}", active.getSessionId());
        return active;
      }
      log.warn("Preferred session {} not active/found, falling back", preferredSessionId);
    }

    String sessionId = "debug_" + System.currentTimeMillis();
    DebugSession created =
        new DebugSession(sessionId, accountName, scriptName, LocalDateTime.now(), 0L);
    created = sessionRepository.save(created);
    log.info(
        "Created debug session in STARTING state: sessionId={}, account={}, script={}",
        sessionId,
        accountName,
        scriptName);
    return created;
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

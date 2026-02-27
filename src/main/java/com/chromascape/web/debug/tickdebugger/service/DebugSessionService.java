package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.SessionCounts;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionFailureCode;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingService.RecordingMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
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
  private final ObjectMapper objectMapper;

  public DebugSessionService(
      DebugSessionRepository sessionRepository,
      TickSnapshotRepository snapshotRepository,
      TickLogRepository tickLogRepository,
      TimelineMarkerRepository timelineMarkerRepository,
      RecordingService recordingService,
      RecordingSessionRepository recordingSessionRepository,
      ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.snapshotRepository = snapshotRepository;
    this.tickLogRepository = tickLogRepository;
    this.timelineMarkerRepository = timelineMarkerRepository;
    this.recordingService = recordingService;
    this.recordingSessionRepository = recordingSessionRepository;
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
        accountName, scriptName, recordingEnabled, RecordingTriggerSource.MICRO6_AUTO);
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

    String sessionId = "debug_" + System.currentTimeMillis();
    Long startTick = 0L; // Will be updated by first tick batch
    String recordingId = null;
    LocalDateTime sessionStartTime;

    // Create and persist the session in STARTING state first
    DebugSession session =
        new DebugSession(sessionId, accountName, scriptName, LocalDateTime.now(), startTick);
    session = sessionRepository.save(session);

    log.info(
        "Created debug session in STARTING state: sessionId={}, account={}, script={}",
        sessionId,
        accountName,
        scriptName);

    // Optionally start video recording
    if (Boolean.TRUE.equals(recordingEnabled)
        && (triggerSource == RecordingTriggerSource.MANUAL
            || recordingService.isAutoRecordEnabled())) {
      try {
        recordingId = recordingService.startRecording(triggerSource);
        sessionStartTime = recordingService.getRecordingStartTimeAsLocalDateTime();

        if (sessionStartTime == null) {
          sessionStartTime = LocalDateTime.now();
          log.warn("Recording started but time unavailable, using current time");
        }

        // Update session with recording ID and aligned start time
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
        // Recording start failed - mark session as FAILED and persist it
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        session.markFailed(
            DebugSessionFailureCode.REC_START_FAILED,
            "Failed to start recording: " + errorMsg,
            getStackTraceString(e));

        // Save failure state in the current transaction (will commit when method returns)
        try {
          sessionRepository.save(session);
          log.error(
              "Session {} marked as FAILED and persisted: code={}, reason={}",
              sessionId,
              session.getFailureCode(),
              errorMsg);
        } catch (Exception saveEx) {
          log.error(
              "Failed to persist failure state for session {}: {}", sessionId, saveEx.getMessage());
        }

        // Return partial success - session created but recording failed
        // Set recordingEnabled to false to indicate failure
        log.warn(
            "Returning partial success for session {}: "
                + "session created in FAILED state, recording not started",
            sessionId);
        return new SessionStartResponse(sessionId, false, null, startTick);
      }
    } else if (Boolean.TRUE.equals(recordingEnabled)) {
      log.warn("Auto-recording blocked (recording.autoRecord=false): source={}", triggerSource);
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

    return new SessionStartResponse(sessionId, recordingEnabled, recordingId, startTick);
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
  }

  /** Get a debug session by ID. */
  public Optional<DebugSession> getSession(String sessionId) {
    return sessionRepository.findBySessionId(sessionId);
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
   * Convert multiple DebugSession entities to summary DTOs using a bulk count query.
   *
   * <p>This method reduces N+1 query problems by fetching all counts in a single JOIN query. Prefer
   * this over calling {@link #toSummary(DebugSession)} in a loop.
   *
   * @param sessions the debug sessions to convert
   * @return list of session summaries with counts
   */
  public List<DebugSessionSummary> toSummaries(List<DebugSession> sessions) {
    if (sessions.isEmpty()) {
      return List.of();
    }

    // Single query for all counts
    List<String> sessionIds = sessions.stream().map(DebugSession::getSessionId).toList();
    List<SessionCounts> countsList = sessionRepository.findCountsBySessionIds(sessionIds);
    Map<String, SessionCounts> countsMap =
        countsList.stream()
            .collect(
                java.util.stream.Collectors.toMap(SessionCounts::getSessionId, counts -> counts));

    // Map to summaries
    return sessions.stream()
        .map(
            session -> {
              SessionCounts counts =
                  countsMap.getOrDefault(
                      session.getSessionId(),
                      new SessionCounts(session.getSessionId(), 0L, 0L, 0L));
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
                  counts.getTickCount(),
                  counts.getEventCount(),
                  counts.getFailureMarkerCount(),
                  counts.getEventCount() > 0,
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

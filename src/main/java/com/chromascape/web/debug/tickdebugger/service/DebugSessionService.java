package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingService.RecordingMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
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
  private final RecordingService recordingService;
  private final RecordingSessionRepository recordingSessionRepository;
  private final ObjectMapper objectMapper;

  public DebugSessionService(
      DebugSessionRepository sessionRepository,
      TickSnapshotRepository snapshotRepository,
      RecordingService recordingService,
      RecordingSessionRepository recordingSessionRepository,
      ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.snapshotRepository = snapshotRepository;
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
    return startSession(accountName, scriptName, recordingEnabled, RecordingTriggerSource.MICRO6_AUTO);
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
      String accountName, String scriptName, Boolean recordingEnabled,
      RecordingTriggerSource triggerSource) {

    String sessionId = "debug_" + System.currentTimeMillis();
    Long startTick = 0L; // Will be updated by first tick batch
    String recordingId = null;
    LocalDateTime sessionStartTime;

    // Optionally start video recording
    if (Boolean.TRUE.equals(recordingEnabled)) {
      try {
        recordingId = recordingService.startRecording(triggerSource);
        sessionStartTime = recordingService.getRecordingStartTimeAsLocalDateTime();

        if (sessionStartTime == null) {
          sessionStartTime = LocalDateTime.now();
          log.warn("Recording started but time unavailable, using current time");
        }

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
        sessionStartTime = LocalDateTime.now();
        log.warn("Failed to start recording for debug session {}: {}", sessionId, e.getMessage());
      }
    } else {
      sessionStartTime = LocalDateTime.now();
    }

    // Create and save the debug session with aligned start time
    DebugSession session =
        new DebugSession(sessionId, accountName, scriptName, sessionStartTime, startTick);

    session.setRecordingId(recordingId);
    session = sessionRepository.save(session);

    log.info(
        "Started debug session: sessionId={}, account={}, script={}, recording={}",
        sessionId,
        accountName,
        scriptName,
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
    session.setEndTime(LocalDateTime.now());

    if (endTick != null) {
      session.setEndTick(endTick);
    } else {
      // If no end tick provided, try to get the last tick from snapshots
      snapshotRepository.findMaxTickBySessionId(sessionId).ifPresent(session::setEndTick);
    }

    // Stop video recording if active and requested
    if (stopRecording && session.getRecordingId() != null) {
      try {
        RecordingMetadata metadata = recordingService.stopRecording();
        if (metadata != null) {
          // Update RecordingSession with video path and metadata
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
        log.info("Stopped recording {} for debug session {}", session.getRecordingId(), sessionId);
      } catch (Exception e) {
        log.warn(
            "Failed to stop recording {} for debug session {}: {}",
            session.getRecordingId(),
            sessionId,
            e.getMessage());
      }
    }

    sessionRepository.save(session);

    long duration =
        java.time.Duration.between(session.getStartTime(), session.getEndTime()).toSeconds();
    long tickCount =
        session.getEndTick() != null ? session.getEndTick() - session.getStartTick() : 0;

    log.info(
        "Stopped debug session: sessionId={}, duration={}s, tickCount={}",
        sessionId,
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

  /** Convert a DebugSession entity to a summary DTO. */
  public DebugSessionSummary toSummary(DebugSession session) {
    Long tickCount = snapshotRepository.countBySessionId(session.getSessionId());

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
        tickCount);
  }

  /** Map a {@link RecordingTriggerSource} to the corresponding {@link RecordingSession.SessionTrigger}. */
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
}

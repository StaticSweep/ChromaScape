package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.recording.service.RecordingService;
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
  private final ObjectMapper objectMapper;

  public DebugSessionService(
      DebugSessionRepository sessionRepository,
      TickSnapshotRepository snapshotRepository,
      RecordingService recordingService,
      ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.snapshotRepository = snapshotRepository;
    this.recordingService = recordingService;
    this.objectMapper = objectMapper;
  }

  /**
   * Start a new debug session.
   *
   * @param accountName the account name
   * @param scriptName the script being run
   * @param recordingEnabled whether to start video recording
   * @return response with session ID and optional recording ID
   */
  @Transactional
  public SessionStartResponse startSession(
      String accountName, String scriptName, Boolean recordingEnabled) {

    String sessionId = "debug_" + System.currentTimeMillis();
    Long startTick = 0L; // Will be updated by first tick batch
    String recordingId = null;

    // Optionally start video recording
    if (Boolean.TRUE.equals(recordingEnabled)) {
      try {
        recordingId = recordingService.startRecording();
        log.info("Started recording {} for debug session {}", recordingId, sessionId);
      } catch (Exception e) {
        log.warn("Failed to start recording for debug session {}: {}", sessionId, e.getMessage());
      }
    }

    // Create and save the debug session
    DebugSession session =
        new DebugSession(sessionId, accountName, scriptName, LocalDateTime.now(), startTick);

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

    // Stop video recording if active
    if (session.getRecordingId() != null) {
      try {
        recordingService.stopRecording();
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
}

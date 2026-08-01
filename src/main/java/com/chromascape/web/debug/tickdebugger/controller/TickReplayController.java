package com.chromascape.web.debug.tickdebugger.controller;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.dto.TickCardData;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlay;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarker;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.TickVideoSyncService;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for tick replay data queries.
 *
 * <p>Provides endpoints for fetching tick data (snapshots, logs, overlays) for replay
 * functionality.
 */
@RestController
@RequestMapping("/api/debug/replay")
public class TickReplayController {

  private static final Logger log = LoggerFactory.getLogger(TickReplayController.class);

  private final DebugSessionRepository sessionRepository;
  private final TickSnapshotRepository snapshotRepository;
  private final TickLogRepository logRepository;
  private final TickOverlayRepository overlayRepository;
  private final TimelineMarkerRepository markerRepository;
  private final TickVideoSyncService tickVideoSyncService;
  private final RecordingSessionRepository recordingSessionRepository;
  private final LogRepository standardLogRepository;
  private final DebugSessionService debugSessionService;
  private final ObjectMapper objectMapper;

  public TickReplayController(
      DebugSessionRepository sessionRepository,
      TickSnapshotRepository snapshotRepository,
      TickLogRepository logRepository,
      TickOverlayRepository overlayRepository,
      TimelineMarkerRepository markerRepository,
      TickVideoSyncService tickVideoSyncService,
      RecordingSessionRepository recordingSessionRepository,
      LogRepository standardLogRepository,
      DebugSessionService debugSessionService,
      ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.snapshotRepository = snapshotRepository;
    this.logRepository = logRepository;
    this.overlayRepository = overlayRepository;
    this.markerRepository = markerRepository;
    this.tickVideoSyncService = tickVideoSyncService;
    this.recordingSessionRepository = recordingSessionRepository;
    this.standardLogRepository = standardLogRepository;
    this.debugSessionService = debugSessionService;
    this.objectMapper = objectMapper;
  }

  /**
   * Get tick range data for a session.
   *
   * @param sessionId the session ID
   * @param start optional start tick
   * @param end optional end tick
   * @return tick data for the range
   */
  @GetMapping("/ticks/{sessionId}")
  public ResponseEntity<TickBatchResponse> getTickRange(
      @PathVariable String sessionId,
      @RequestParam(required = false) Long start,
      @RequestParam(required = false) Long end) {

    try {
      // Verify session exists
      if (sessionRepository.findBySessionId(sessionId).isEmpty()) {
        return ResponseEntity.notFound().build();
      }

      List<TickSnapshot> snapshots;
      List<TickLog> logs;
      List<TickOverlay> overlays;

      if (start != null && end != null) {
        snapshots =
            snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(sessionId, start, end);
        logs = logRepository.findBySessionIdAndTickBetweenOrderByTickAsc(sessionId, start, end);
        overlays =
            overlayRepository.findBySessionIdAndTickBetweenOrderByTickAsc(sessionId, start, end);
      } else {
        snapshots = snapshotRepository.findBySessionIdOrderByTick(sessionId);
        logs = logRepository.findBySessionIdOrderByTickAsc(sessionId);
        overlays = overlayRepository.findBySessionIdOrderByTickAsc(sessionId);
      }

      TickBatchResponse response = new TickBatchResponse();
      response.setSnapshots(snapshots);
      response.setLogs(logs);
      response.setOverlays(overlays);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error getting tick range: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get data for a specific tick.
   *
   * @param sessionId the session ID
   * @param tick the tick number
   * @return tick data for the specified tick
   */
  @GetMapping("/tick/{sessionId}/{tick}")
  public ResponseEntity<TickDataResponse> getTick(
      @PathVariable String sessionId, @PathVariable Long tick) {

    try {
      // Verify session exists
      if (sessionRepository.findBySessionId(sessionId).isEmpty()) {
        return ResponseEntity.notFound().build();
      }

      TickDataResponse response = new TickDataResponse();
      response.setTick(tick);

      snapshotRepository.findBySessionIdAndTick(sessionId, tick).ifPresent(response::setSnapshot);

      response.setLogs(logRepository.findBySessionIdAndTick(sessionId, tick));
      response.setOverlays(overlayRepository.findBySessionIdAndTick(sessionId, tick));

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error getting tick data: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Search logs with filters.
   *
   * @param sessionId the session ID
   * @param subsystem optional subsystem filter
   * @param level optional severity level filter
   * @param search optional search term
   * @return filtered log entries
   */
  @GetMapping("/logs/{sessionId}")
  public ResponseEntity<List<TickLog>> searchLogs(
      @PathVariable String sessionId,
      @RequestParam(required = false) String subsystem,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String search) {

    try {
      List<TickLog> logs;

      if (subsystem != null && !subsystem.isEmpty()) {
        logs =
            logRepository.findBySessionIdAndSubsystemIgnoreCaseOrderByTickAsc(sessionId, subsystem);
      } else if (level != null && !level.isEmpty()) {
        logs = logRepository.findBySessionIdAndSeverityIgnoreCaseOrderByTickAsc(sessionId, level);
      } else if (search != null && !search.isEmpty()) {
        logs =
            logRepository.findBySessionIdAndMessageContainingIgnoreCaseOrderByTickAsc(
                sessionId, search);
      } else {
        logs = logRepository.findBySessionIdOrderByTickAsc(sessionId);
      }

      return ResponseEntity.ok(logs);

    } catch (Exception e) {
      log.error("Error searching logs: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get timeline markers for a session.
   *
   * @param sessionId the session ID
   * @return list of timeline markers
   */
  @GetMapping("/timeline-markers/{sessionId}")
  public ResponseEntity<List<TimelineMarker>> getTimelineMarkers(@PathVariable String sessionId) {
    try {
      List<TimelineMarker> markers = markerRepository.findBySessionIdOrderByTickAsc(sessionId);
      return ResponseEntity.ok(markers);

    } catch (Exception e) {
      log.error("Error getting timeline markers: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get session info for replay (tick range, etc).
   *
   * @param sessionId the session ID
   * @return session info
   */
  @GetMapping("/session-info/{sessionId}")
  public ResponseEntity<SessionInfo> getSessionInfo(@PathVariable String sessionId) {
    try {
      return sessionRepository
          .findBySessionId(sessionId)
          .map(
              session -> {
                SessionInfo info = new SessionInfo();
                info.sessionId = session.getSessionId();
                info.startTick = session.getStartTick();
                info.endTick = session.getEndTick();
                info.scriptName = session.getScriptName();
                info.lifecycleState = session.getLifecycleStateOrDefault().name();
                info.failureCode = session.getFailureCode();
                info.failureReason = session.getFailureReason();

                snapshotRepository
                    .findMinTickBySessionId(sessionId)
                    .ifPresent(min -> info.minTick = min);
                snapshotRepository
                    .findMaxTickBySessionId(sessionId)
                    .ifPresent(max -> info.maxTick = max);

                return ResponseEntity.ok(info);
              })
          .orElse(ResponseEntity.notFound().build());

    } catch (Exception e) {
      log.error("Error getting session info: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get unified logs (from both logs and tick_logs tables) for video timeline replay.
   *
   * <p>This endpoint merges logs from both the standard logs table and tick-specific tick_logs
   * table, providing a unified view for video replay with proper tick grouping.
   *
   * @param recordingId The recording ID to sync against
   * @param videoTimeMs The video position in milliseconds
   * @param windowSeconds Optional time window in seconds (default: 10)
   * @return Map containing video timestamp and merged logs with tick information
   */
  @GetMapping("/unified")
  public ResponseEntity<Map<String, Object>> getUnifiedLogs(
      @RequestParam String recordingId,
      @RequestParam Long videoTimeMs,
      @RequestParam(defaultValue = "10") Integer windowSeconds) {

    try {
      // Get recording session to find start time
      RecordingSession recordingSession =
          recordingSessionRepository.findByRecordingId(recordingId).orElse(null);

      if (recordingSession == null) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Recording session not found: " + recordingId);
        return ResponseEntity.notFound().build();
      }

      if (recordingSession.getStartTime() == null) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Recording session has no start time");
        return ResponseEntity.badRequest().body(error);
      }

      // Calculate the real-world timestamp for this video position
      LocalDateTime videoTimestamp =
          recordingSession.getStartTime().plus(videoTimeMs, ChronoUnit.MILLIS);

      // Define the time window for logs
      LocalDateTime windowStart = videoTimestamp.minusSeconds(windowSeconds);
      LocalDateTime windowEnd = videoTimestamp.plusSeconds(windowSeconds);
      LocalDateTime recordingStart = recordingSession.getStartTime();
      if (windowStart.isBefore(recordingStart)) {
        windowStart = recordingStart;
      }

      // Fetch logs from standard logs table
      List<LogEntity> standardLogs =
          standardLogRepository.findByTimestampBetween(windowStart, windowEnd);

      // Try to find associated debug session for tick logs and current tick calculation
      List<TickLog> tickLogs = new ArrayList<>();
      Long currentTick = null;
      DebugSession debugSession = sessionRepository.findByRecordingId(recordingId).orElse(null);

      if (debugSession != null) {
        // Fetch tick logs within the time window
        tickLogs.addAll(
            logRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
                debugSession.getSessionId(), windowStart, windowEnd));

        // Primary: use TickVideoSyncService for deterministic tick calculation
        try {
          currentTick =
              tickVideoSyncService.getTickForVideoTime(debugSession.getSessionId(), videoTimeMs);
        } catch (Exception e) {
          log.debug("Could not get tick from sync service: {}", e.getMessage());
        }
      }

      // Fallback: extract from tick logs if primary method failed
      if (currentTick == null && !tickLogs.isEmpty()) {
        currentTick =
            tickLogs.stream()
                .filter(t -> !t.getTimestamp().isAfter(videoTimestamp))
                .reduce((a, b) -> b)
                .map(TickLog::getTick)
                .orElse(null);
      }

      // Second fallback: derive from standard logs that have tick data
      if (currentTick == null) {
        currentTick =
            standardLogs.stream()
                .filter(l -> l.getTick() != null)
                .filter(l -> !l.getTimestamp().isAfter(videoTimestamp))
                .reduce((a, b) -> b)
                .map(l -> l.getTick().longValue())
                .orElse(null);
      }

      // Ultimate fallback: session-relative time-based calculation
      if (currentTick == null) {
        if (debugSession != null && debugSession.getStartTick() != null) {
          // Session-relative: startTick + elapsed ticks (600ms per tick)
          currentTick = debugSession.getStartTick() + (videoTimeMs / 600);
        } else {
          // Pure time-based fallback (assumes tick 0 at video start, 600ms per tick)
          currentTick = videoTimeMs / 600;
        }
      }

      // Merge logs into unified format
      List<UnifiedLogEntry> unifiedLogs = new ArrayList<>();

      // Add standard logs
      for (LogEntity log : standardLogs) {
        UnifiedLogEntry entry = new UnifiedLogEntry();
        entry.setId(log.getId());
        entry.setTimestamp(log.getTimestamp());
        entry.setLevel(log.getLevel());
        entry.setMessage(log.getMessage());
        entry.setSource(log.getSource());
        entry.setTick(log.getTick() != null ? log.getTick().longValue() : null);
        entry.setLogType(log.getLogType());
        entry.setStructuredData(log.getStructuredData());
        // Set origin based on source - external/micro6 sources are external, others are standard
        String source = log.getSource();
        if ("external".equalsIgnoreCase(source) || "micro6".equalsIgnoreCase(source)) {
          entry.setOrigin("external");
        } else {
          entry.setOrigin("standard");
        }
        unifiedLogs.add(entry);
      }

      // Add tick logs (convert to unified format)
      for (TickLog tickLog : tickLogs) {
        UnifiedLogEntry entry = new UnifiedLogEntry();
        entry.setId(tickLog.getId());
        entry.setTimestamp(tickLog.getTimestamp());
        entry.setLevel(tickLog.getSeverity());
        entry.setMessage(tickLog.getMessage());
        entry.setSource(tickLog.getSubsystem());
        entry.setTick(tickLog.getTick());
        entry.setLogType(tickLog.getEventType());
        entry.setStructuredData(
            tickLog.getDetails() != null ? tickLog.getDetails().toString() : null);
        entry.setOrigin("tick"); // Mark as coming from tick_logs table
        unifiedLogs.add(entry);
      }

      // Sort by timestamp
      unifiedLogs.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));

      // Build response
      Map<String, Object> response = new HashMap<>();
      response.put("videoTimeMs", videoTimeMs);
      response.put("videoTimestamp", videoTimestamp);
      response.put(
          "videoTimestampEpochMs",
          videoTimestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
      response.put("windowStart", windowStart);
      response.put("windowEnd", windowEnd);
      response.put("logs", unifiedLogs);
      response.put("currentTick", currentTick);

      log.debug(
          "Unified timeline sync for {} at {}ms: returned {} standard logs + {} tick logs = {} total",
          recordingId,
          videoTimeMs,
          standardLogs.size(),
          tickLogs.size(),
          unifiedLogs.size());

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Failed to get unified logs for video position: {}", e.getMessage(), e);
      Map<String, Object> error = new HashMap<>();
      error.put("error", e.getMessage());
      return ResponseEntity.internalServerError().body(error);
    }
  }

  /**
   * Get tick-to-video synchronization info for a session.
   *
   * @param sessionId the debug session ID
   * @return sync information including video timestamp and frame number
   */
  @GetMapping("/{sessionId}/sync")
  public ResponseEntity<TickVideoSyncService.SyncInfo> getTickSyncInfo(
      @PathVariable String sessionId) {

    try {
      TickVideoSyncService.SyncInfo syncInfo = tickVideoSyncService.getSyncInfo(sessionId);
      return ResponseEntity.ok(syncInfo);
    } catch (Exception e) {
      log.error("Failed to get sync info for session {}: {}", sessionId, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Start a new recording with tick data tracking.
   *
   * <p>This endpoint creates both a debug session and a recording session linked together, ensuring
   * recordings have associated tick data for replay.
   *
   * @param request the recording start request
   * @return session start response with sessionId and recordingId
   */
  @PostMapping("/start-recording")
  public ResponseEntity<SessionStartResponse> startRecording(
      @RequestBody RecordingStartRequest request) {

    try {
      String accountName = request.getAccountName();
      String scriptName = request.getScriptName();
      Boolean recordingEnabled = request.getRecordingEnabled();

      if (accountName == null || accountName.isEmpty()) {
        accountName = "Unknown";
      }
      if (scriptName == null || scriptName.isEmpty()) {
        scriptName = "Manual Recording";
      }
      if (recordingEnabled == null) {
        recordingEnabled = true;
      }

      SessionStartResponse response =
          debugSessionService.startSession(
              accountName,
              scriptName,
              recordingEnabled,
              RecordingTriggerSource.MANUAL,
              request.getSessionId());

      log.info(
          "Started recording via replay page: sessionId={}, recordingId={}",
          response.getSessionId(),
          response.getRecordingId());

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Failed to start recording: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Stop a running recording.
   *
   * @param request the recording stop request
   * @return success response
   */
  @PostMapping("/stop-recording")
  public ResponseEntity<Map<String, Object>> stopRecording(
      @RequestBody RecordingStopRequest request) {

    try {
      String sessionId = request.getSessionId();
      Long endTick = request.getEndTick();

      if (sessionId == null || sessionId.isEmpty()) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Session ID is required");
        return ResponseEntity.badRequest().body(error);
      }

      debugSessionService.stopSession(sessionId, endTick);
      DebugSession stoppedSession = sessionRepository.findBySessionId(sessionId).orElse(null);
      String recordingId = stoppedSession != null ? stoppedSession.getRecordingId() : null;

      boolean linkRepaired = false;
      if (recordingId != null && !recordingId.isBlank()) {
        linkRepaired = debugSessionService.repairRecordingLink(recordingId);
      }

      DebugSession linkedSession =
          (recordingId != null && !recordingId.isBlank())
              ? sessionRepository.findByRecordingId(recordingId).orElse(stoppedSession)
              : stoppedSession;
      long tickCount =
          linkedSession != null ? snapshotRepository.countBySessionId(linkedSession.getSessionId()) : 0L;
      long positionFrameCount =
          linkedSession != null
              ? snapshotRepository.countWithPositionTelemetry(linkedSession.getSessionId())
              : 0L;
      boolean replayable = tickCount > 0 && positionFrameCount > 0;
      String replayabilityReason =
          replayable
              ? "Replayable"
              : (tickCount == 0
                  ? "No tick snapshots stored for this recording"
                  : "No position telemetry in tick snapshots");

      Map<String, Object> response = new HashMap<>();
      response.put("status", "stopped");
      response.put("sessionId", sessionId);
      response.put("recordingId", recordingId);
      response.put("tickCount", tickCount);
      response.put("positionFrameCount", positionFrameCount);
      response.put("replayable", replayable);
      response.put("replayabilityReason", replayabilityReason);
      response.put("linkRepaired", linkRepaired);

      log.info("Stopped recording via replay page: sessionId={}", sessionId);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Failed to stop recording: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Start a debug session without recording.
   *
   * <p>This endpoint creates a debug session for live tick data viewing without video recording.
   * Used for debugging without the overhead of video capture.
   *
   * @param request the session start request
   * @return session start response with sessionId and startTick
   */
  @PostMapping("/start-session")
  public ResponseEntity<SessionStartResponse> startSession(
      @RequestBody RecordingStartRequest request) {

    try {
      String accountName = request.getAccountName();
      String scriptName = request.getScriptName();

      if (accountName == null || accountName.isEmpty()) {
        accountName = "Unknown";
      }
      if (scriptName == null || scriptName.isEmpty()) {
        scriptName = "Live Debug Session";
      }

      // Create debug session without recording (recordingEnabled = false)
      SessionStartResponse response =
          debugSessionService.startSession(accountName, scriptName, false);

      log.info(
          "Started debug session without recording: sessionId={}, account={}, script={}",
          response.getSessionId(),
          accountName,
          scriptName);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Failed to start debug session: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get active debug session by recording ID.
   *
   * <p>This endpoint allows the frontend to look up a debug session that's associated with an
   * active recording. Used to restore session ID after page refresh when auto-recording is active.
   *
   * @param recordingId the recording ID to look up
   * @return the active debug session if found
   */
  @GetMapping("/sessions/active")
  public ResponseEntity<DebugSessionSummary> getActiveSessionByRecordingId(
      @RequestParam String recordingId) {

    try {
      List<DebugSession> activeSessions = debugSessionService.getActiveSessions();

      for (DebugSession session : activeSessions) {
        if (recordingId.equals(session.getRecordingId())) {
          return ResponseEntity.ok(debugSessionService.toSummary(session));
        }
      }

      return ResponseEntity.notFound().build();

    } catch (Exception e) {
      log.error("Failed to get active session by recording ID: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get debug session by recording ID (for replay snapshot loading).
   *
   * <p>This endpoint looks up any debug session (active or completed) associated with a recording
   * ID. Used during replay to fetch tick snapshots for completed recordings.
   *
   * @param recordingId the recording ID to look up
   * @return the debug session summary if found, 404 if not found
   */
  @GetMapping("/sessions/by-recording")
  public ResponseEntity<DebugSessionSummary> getSessionByRecordingId(
      @RequestParam String recordingId) {

    try {
      Optional<DebugSession> sessionOpt = sessionRepository.findByRecordingId(recordingId);

      if (sessionOpt.isEmpty()) {
        return ResponseEntity.notFound().build();
      }

      DebugSession session = sessionOpt.get();
      return ResponseEntity.ok(debugSessionService.toSummary(session));

    } catch (Exception e) {
      log.error("Failed to get session by recording ID: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get compact health summary for all sessions that have a recording.
   *
   * <p>Returns {@link DebugSessionSummary} for every debug session with a recording ID, suitable
   * for populating the replay selector with event/failure counts.
   *
   * <p>Only sessions linked to a recording are queried, and counts are fetched via three grouped
   * queries per table to avoid the row explosion of a multi-table join aggregate.
   *
   * @return list of session summaries with health metadata
  */
  @GetMapping("/recording-health")
  public ResponseEntity<List<DebugSessionSummary>> getRecordingHealth(
      @RequestParam(required = false, defaultValue = "200") Integer limit) {
    try {
      int boundedLimit = Math.max(1, Math.min(limit != null ? limit : 200, 500));
      // Only sessions linked to a recording; counts are fetched via grouped per-table queries
      List<DebugSession> sessions =
          sessionRepository.findByRecordingIdIsNotNullOrderByStartTimeDesc().stream()
              .limit(boundedLimit)
              .toList();

      List<DebugSessionSummary> summaries = debugSessionService.toSummaries(sessions);

      return ResponseEntity.ok(summaries);
    } catch (Exception e) {
      log.error("Failed to get recording health: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  @GetMapping("/log-parity")
  public ResponseEntity<Map<String, Object>> getLogParity(@RequestParam String recordingId) {
    try {
      boolean linkRepaired = debugSessionService.repairRecordingLink(recordingId);
      DebugSession session = sessionRepository.findByRecordingId(recordingId).orElse(null);

      Map<String, Object> response = new HashMap<>();
      response.put("recordingId", recordingId);
      response.put("linkRepaired", linkRepaired);

      if (session == null) {
        response.put("hasSession", false);
        response.put("hasTickLogs", false);
        response.put("tickSnapshotCount", 0L);
        response.put("tickLogCount", 0L);
        return ResponseEntity.ok(response);
      }

      Long tickSnapshotCount = snapshotRepository.countBySessionId(session.getSessionId());
      Long tickLogCount = logRepository.countBySessionId(session.getSessionId());

      response.put("hasSession", true);
      response.put("sessionId", session.getSessionId());
      response.put("recordingStartTime", session.getStartTime());
      response.put("tickSnapshotCount", tickSnapshotCount);
      response.put("tickLogCount", tickLogCount);
      response.put("hasTickLogs", tickLogCount != null && tickLogCount > 0);
      snapshotRepository.findMinTickBySessionId(session.getSessionId()).ifPresent(t -> response.put("minTick", t));
      snapshotRepository.findMaxTickBySessionId(session.getSessionId()).ifPresent(t -> response.put("maxTick", t));

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Failed to compute replay log parity for recording {}: {}", recordingId, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/tick-logs")
  public ResponseEntity<Map<String, Object>> getTickLogs(
      @RequestParam String recordingId,
      @RequestParam Long tick,
      @RequestParam(defaultValue = "3") Integer windowTicks) {
    try {
      debugSessionService.repairRecordingLink(recordingId);
      DebugSession session = sessionRepository.findByRecordingId(recordingId).orElse(null);
      if (session == null) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "No debug session found for recordingId: " + recordingId));
      }

      long startTick = Math.max(0L, tick - Math.max(0, windowTicks));
      long endTick = tick + Math.max(0, windowTicks);
      List<TickLog> tickLogs =
          logRepository.findBySessionIdAndTickBetweenOrderByTickAsc(session.getSessionId(), startTick, endTick);

      List<UnifiedLogEntry> logs = new ArrayList<>();
      for (TickLog tl : tickLogs) {
        UnifiedLogEntry entry = new UnifiedLogEntry();
        entry.setId(tl.getId());
        entry.setTimestamp(tl.getTimestamp());
        entry.setLevel(tl.getSeverity());
        entry.setMessage(tl.getMessage());
        entry.setSource(tl.getSubsystem());
        entry.setTick(tl.getTick());
        entry.setLogType(tl.getEventType());
        entry.setStructuredData(tl.getDetails() != null ? tl.getDetails().toString() : null);
        entry.setOrigin("tick");
        logs.add(entry);
      }

      if (logs.isEmpty()) {
        long ms = tick * 600L;
        RecordingSession recordingSession = recordingSessionRepository.findByRecordingId(recordingId).orElse(null);
        if (recordingSession != null && recordingSession.getStartTime() != null) {
          LocalDateTime target = recordingSession.getStartTime().plus(ms, ChronoUnit.MILLIS);
          LocalDateTime windowStart = target.minusSeconds(2);
          LocalDateTime windowEnd = target.plusSeconds(2);
          for (LogEntity log : standardLogRepository.findByTimestampBetween(windowStart, windowEnd)) {
            UnifiedLogEntry entry = new UnifiedLogEntry();
            entry.setId(log.getId());
            entry.setTimestamp(log.getTimestamp());
            entry.setLevel(log.getLevel());
            entry.setMessage(log.getMessage());
            entry.setSource(log.getSource());
            entry.setTick(log.getTick() != null ? log.getTick().longValue() : null);
            entry.setLogType(log.getLogType());
            entry.setStructuredData(log.getStructuredData());
            entry.setOrigin("standard");
            logs.add(entry);
          }
        }
      }

      Map<String, Object> response = new HashMap<>();
      response.put("recordingId", recordingId);
      response.put("sessionId", session.getSessionId());
      response.put("tick", tick);
      response.put("windowTicks", windowTicks);
      response.put("logs", logs);
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Failed to load replay tick logs for recording {}: {}", recordingId, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }
  }

  /**
   * Repairs a recording->session link when recording was attached to a no-tick session.
   *
   * @param recordingId recording ID to repair
   * @return result map with repaired flag
   */
  @PostMapping("/repair-recording-link")
  public ResponseEntity<Map<String, Object>> repairRecordingLink(@RequestParam String recordingId) {
    try {
      if (recordingId == null || recordingId.isBlank()) {
        return ResponseEntity.badRequest()
            .body(Map.of("success", false, "error", "recordingId is required"));
      }
      boolean repaired = debugSessionService.repairRecordingLink(recordingId);
      return ResponseEntity.ok(Map.of("success", true, "repaired", repaired));
    } catch (Exception e) {
      log.error("Failed to repair recording link {}: {}", recordingId, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("success", false, "error", e.getMessage()));
    }
  }

  /**
   * Repair a single orphaned recording whose .mp4 exists on disk but DB metadata is missing.
   *
   * <p>Backfills {@code video_path} and {@code end_time} for the {@link RecordingSession} row when
   * the recording file is present on disk.
   *
   * @param recordingId the recording ID to repair
   * @return result map with repaired flag
   */
  @PostMapping("/recordings/{recordingId}/repair")
  public ResponseEntity<Map<String, Object>> repairOrphanedRecording(@PathVariable String recordingId) {
    try {
      if (recordingId == null || recordingId.isBlank()) {
        return ResponseEntity.badRequest()
            .body(Map.of("success", false, "error", "recordingId is required"));
      }
      boolean repaired = debugSessionService.repairOrphanedRecordingSession(recordingId);
      return ResponseEntity.ok(Map.of("success", true, "repaired", repaired));
    } catch (Exception e) {
      log.error("Failed to repair orphaned recording {}: {}", recordingId, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("success", false, "error", e.getMessage()));
    }
  }

  /**
   * Scan recent recording sessions and repair any orphans where the .mp4 exists on disk but DB
   * metadata is missing.
   *
   * @return result map with count of repaired sessions
   */
  @PostMapping("/recordings/repair-orphans")
  public ResponseEntity<Map<String, Object>> repairOrphanedRecordings() {
    try {
      int repaired = debugSessionService.repairOrphanedRecordingSessions();
      return ResponseEntity.ok(Map.of("success", true, "repaired", repaired));
    } catch (Exception e) {
      log.error("Failed to repair orphaned recordings: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("success", false, "error", e.getMessage()));
    }
  }

  /**
   * Resolve a sessionId to its associated recordingId.
   *
   * <p>Used for deep-link navigation via {@code ?sessionId=} URL parameter.
   *
   * @param sessionId the debug session ID
   * @return map containing {@code recordingId}, or 404 if not found / no recording attached
   */
  @GetMapping("/recording-by-session")
  public ResponseEntity<Map<String, String>> getRecordingBySession(@RequestParam String sessionId) {
    try {
      return debugSessionService
          .getSession(sessionId)
          .filter(s -> s.getRecordingId() != null)
          .map(s -> ResponseEntity.ok(Map.of("recordingId", s.getRecordingId())))
          .orElse(ResponseEntity.notFound().build());
    } catch (Exception e) {
      log.error("Failed to resolve sessionId {} to recordingId: {}", sessionId, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get tick cards for a recording.
   *
   * <p>Returns tick-organized data with section counts and player summaries for replay page
   * rendering.
   *
   * @param recordingId the recording ID
   * @param start optional start tick (default: 0)
   * @param end optional end tick (default: start + 100)
   * @return list of tick card data
   */
  @GetMapping("/tick-cards/{recordingId}")
  public ResponseEntity<?> getTickCards(
      @PathVariable String recordingId,
      @RequestParam(required = false, defaultValue = "0") Long start,
      @RequestParam(required = false) Long end) {

    try {
      // Look up debug session by recording ID
      Optional<DebugSession> sessionOpt = sessionRepository.findByRecordingId(recordingId);

      if (sessionOpt.isEmpty()) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "No tick data available for this recording");
        error.put(
            "message",
            "This recording was made before tick tracking was enabled. Only video playback is available.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
      }

      DebugSession session = sessionOpt.get();
      String sessionId = session.getSessionId();

      // Default end to start + 100 if not provided
      if (end == null) {
        end = start + 100;
      }

      // Fetch tick data in range
      List<TickSnapshot> snapshots =
          snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(sessionId, start, end);
      List<TickLog> logs =
          logRepository.findBySessionIdAndTickBetweenOrderByTickAsc(sessionId, start, end);

      // Build tick cards
      Map<Long, TickCardData> tickCardsMap = new LinkedHashMap<>();

      // Process snapshots
      for (TickSnapshot snapshot : snapshots) {
        Long tick = snapshot.getTick();
        TickCardData card = tickCardsMap.computeIfAbsent(tick, this::createEmptyTickCard);

        card.setPlayerSummary(buildPlayerSummary(snapshot));

        // Calculate section counts and data from snapshot
        Map<String, Integer> sectionCounts = card.getSectionCounts();
        Map<String, Object> sectionData = card.getSectionData();

        // Quest section
        if (snapshot.getQuest() != null && !snapshot.getQuest().toString().isEmpty()) {
          sectionCounts.put("quest", 1);
          sectionData.put("quest", snapshot.getQuest());
        }
        if (snapshot.getQuestHelper() != null && !snapshot.getQuestHelper().isNull()) {
          sectionCounts.put("questHelper", 1);
          sectionData.put("questHelper", snapshot.getQuestHelper());
        }

        // Inventory section
        if (snapshot.getInventory() != null) {
          JsonNode inventoryNode = snapshot.getInventory();
          int itemCount = inventoryNode.isArray() ? inventoryNode.size() : 0;
          if (itemCount > 0) {
            sectionCounts.put("inventory", 1);
            sectionData.put("inventory", snapshot.getInventory());
          }
        }

        // NPCs section (prefer full NPC list if available)
        if (snapshot.getNpcs() != null) {
          JsonNode npcNode = snapshot.getNpcs();
          int npcCount = npcNode.isArray() ? npcNode.size() : 0;
          sectionCounts.put("npcs", npcCount);
          sectionData.put("npcs", snapshot.getNpcs());
        } else if (snapshot.getThreats() != null) {
          JsonNode threatsNode = snapshot.getThreats();
          int threatCount = threatsNode.isArray() ? threatsNode.size() : 0;
          sectionCounts.put("npcs", threatCount);
          sectionData.put("npcs", snapshot.getThreats());
        }

        // Projectiles section
        if (snapshot.getProjectiles() != null) {
          JsonNode projectilesNode = snapshot.getProjectiles();
          int projCount = projectilesNode.isArray() ? projectilesNode.size() : 0;
          sectionCounts.put("projectiles", projCount);
          sectionData.put("projectiles", snapshot.getProjectiles());
        }

        // Objects section
        if (snapshot.getGameObjects() != null) {
          JsonNode objectsNode = snapshot.getGameObjects();
          int objectCount = objectsNode.isArray() ? objectsNode.size() : 0;
          sectionCounts.put("objects", objectCount);
          sectionData.put("objects", snapshot.getGameObjects());
        }

        // Ground objects section
        if (snapshot.getGroundObjects() != null) {
          JsonNode groundObjectsNode = snapshot.getGroundObjects();
          int groundObjectCount = groundObjectsNode.isArray() ? groundObjectsNode.size() : 0;
          sectionCounts.put("groundObjects", groundObjectCount);
          sectionData.put("groundObjects", snapshot.getGroundObjects());
        }

        // Ground items section
        if (snapshot.getGroundItems() != null) {
          JsonNode groundItemsNode = snapshot.getGroundItems();
          int groundItemCount = groundItemsNode.isArray() ? groundItemsNode.size() : 0;
          sectionCounts.put("groundItems", groundItemCount);
          sectionData.put("groundItems", snapshot.getGroundItems());
        }

        // Player section (always present)
        sectionCounts.put("player", 1);
        sectionData.put("player", snapshot.getPlayerState());
      }

      // Process logs
      Map<Long, List<TickLog>> logsByTick = new HashMap<>();
      for (TickLog log : logs) {
        logsByTick.computeIfAbsent(log.getTick(), k -> new ArrayList<>()).add(log);
      }

      for (Map.Entry<Long, List<TickLog>> entry : logsByTick.entrySet()) {
        Long tick = entry.getKey();
        List<TickLog> tickLogs = entry.getValue();

        TickCardData card = tickCardsMap.computeIfAbsent(tick, this::createEmptyTickCard);

        Map<String, Integer> sectionCounts = card.getSectionCounts();
        Map<String, Object> sectionData = card.getSectionData();

        // Actions section (all logs)
        sectionCounts.put("actions", tickLogs.size());
        sectionData.put("actions", tickLogs);

        // Dialogue section
        List<TickLog> dialogueLogs =
            tickLogs.stream()
                .filter(
                    l ->
                        (l.getEventType() != null
                                && l.getEventType().toLowerCase().contains("dialogue"))
                            || (l.getSubsystem() != null
                                && l.getSubsystem().equalsIgnoreCase("dialogue")))
                .toList();
        if (!dialogueLogs.isEmpty()) {
          sectionCounts.put("dialogue", dialogueLogs.size());
          sectionData.put("dialogue", dialogueLogs);
        }

        // Varbit section
        List<TickLog> varbitLogs =
            tickLogs.stream()
                .filter(
                    l ->
                        (l.getEventType() != null
                                && l.getEventType().toLowerCase().contains("varbit"))
                            || (l.getSubsystem() != null
                                && l.getSubsystem().equalsIgnoreCase("varbit")))
                .toList();
        if (!varbitLogs.isEmpty()) {
          sectionCounts.put("varbits", varbitLogs.size());
          sectionData.put("varbits", varbitLogs);
        }
      }

      List<TickCardData> tickCards = new ArrayList<>(tickCardsMap.values());

      log.debug(
          "Loaded {} tick cards for recording {} (ticks {}-{})",
          tickCards.size(),
          recordingId,
          start,
          end);

      return ResponseEntity.ok(tickCards);

    } catch (Exception e) {
      log.error("Failed to get tick cards for recording {}: {}", recordingId, e.getMessage(), e);
      Map<String, Object> error = new HashMap<>();
      error.put("error", e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
  }

  /**
   * Create an empty tick card with initialized maps.
   *
   * @param tick the tick number
   * @return empty tick card
   */
  private TickCardData createEmptyTickCard(Long tick) {
    TickCardData card = new TickCardData();
    card.setTick(tick);
    card.setSectionCounts(new HashMap<>());
    card.setSectionData(new HashMap<>());
    return card;
  }

  /**
   * Build a player state summary string from a snapshot.
   *
   * @param snapshot the tick snapshot
   * @return player summary string
   */
  private String buildPlayerSummary(TickSnapshot snapshot) {
    JsonNode playerState = snapshot.getPlayerState();
    if (playerState == null) {
      return "No player data";
    }

    StringBuilder summary = new StringBuilder();

    // HP
    String hpDisplay = formatHp(playerState);
    if (!hpDisplay.isEmpty()) {
      summary.append(hpDisplay);
    }

    // Prayer
    String prayerDisplay = formatPrayer(playerState);
    if (!prayerDisplay.isEmpty()) {
      if (summary.length() > 0) summary.append(" · ");
      summary.append(prayerDisplay);
    }

    // Energy
    JsonNode energy = nestedOrFallback(playerState, "energy", "runEnergy");
    if (energy != null) {
      if (summary.length() > 0) summary.append(" · ");
      summary.append(String.format("Energy %d%%", energy.asInt()));
    }

    // Animation
    JsonNode animation = nestedOrFallback(playerState, "animation", "animationId");
    if (animation != null) {
      if (summary.length() > 0) summary.append(" · ");
      summary.append(String.format("Anim %d", animation.asInt()));
    }

    // Position
    JsonNode position = nestedOrFlatPosition(playerState);
    if (position != null) {
      Integer x = getPositionX(position);
      Integer y = getPositionY(position);
      Integer parsedPlane = intValue(position.get("plane"));
      int plane = parsedPlane != null ? parsedPlane : 0;
      if (summary.length() > 0) summary.append(" · ");
      summary.append(String.format("Pos (%d,%d,%d)", x, y, plane));
    }

    return summary.length() > 0 ? summary.toString() : "No player data";
  }

  /**
   * Resolve position from playerState using nested position, then flat fields.
   */
  private JsonNode nestedOrFlatPosition(JsonNode playerState) {
    JsonNode nestedPosition = playerState.get("position");
    if (nestedPosition != null
        && nestedPosition.isObject()
        && firstInt(nestedPosition, "x") != null
        && firstInt(nestedPosition, "y", "z") != null) {
      return nestedPosition;
    }
    // Check for flat x/y, x/z, or x/worldY.
    if (firstInt(playerState, "x") != null
        && firstInt(playerState, "y", "z", "worldY") != null) {
      return playerState; // Contains x/y or x/z directly
    }
    // Check for worldX/worldY, worldX/y, or worldX/z.
    if (firstInt(playerState, "worldX") != null
        && firstInt(playerState, "worldY", "y", "z") != null) {
      return playerState;
    }
    return null;
  }

  /** Get position X, preferring the canonical x field and then the legacy worldX alias. */
  private Integer getPositionX(JsonNode position) {
    return firstInt(position, "x", "worldX");
  }

  /**
   * Get position Y value with fallback to z, then worldY.
   */
  private Integer getPositionY(JsonNode position) {
    return firstInt(position, "y", "z", "worldY");
  }

  private Integer firstInt(JsonNode node, String... keys) {
    if (node == null) {
      return null;
    }
    for (String key : keys) {
      Integer value = intValue(node.get(key));
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private Integer intValue(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isNumber()) {
      return node.asInt();
    }
    if (node.isTextual()) {
      String text = node.asText().trim();
      if (text.isEmpty()) {
        return null;
      }
      try {
        return Integer.parseInt(text);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  /**
   * Format HP display from nested or flat fields.
   */
  private String formatHp(JsonNode playerState) {
    JsonNode hp = playerState.get("hp");
    if (hp != null && hp.isObject() && hp.has("current")) {
      int cur = hp.get("current").asInt();
      int max = hp.has("max") ? hp.get("max").asInt() : 0;
      return String.format("HP %d/%d", cur, max);
    }
    if (playerState.has("currentHp")) {
      int cur = playerState.get("currentHp").asInt();
      int max = playerState.has("maxHp") ? playerState.get("maxHp").asInt() : 0;
      return String.format("HP %d/%d", cur, max);
    }
    return "";
  }

  /**
   * Format Prayer display from nested or flat fields.
   */
  private String formatPrayer(JsonNode playerState) {
    JsonNode prayer = playerState.get("prayer");
    if (prayer != null && prayer.isObject() && prayer.has("current")) {
      int cur = prayer.get("current").asInt();
      int max = prayer.has("max") ? prayer.get("max").asInt() : 0;
      return String.format("Pray %d/%d", cur, max);
    }
    if (playerState.has("currentPrayer")) {
      int cur = playerState.get("currentPrayer").asInt();
      int max = playerState.has("maxPrayer") ? playerState.get("maxPrayer").asInt() : 0;
      return String.format("Pray %d/%d", cur, max);
    }
    return "";
  }

  /**
   * Get a value from playerState trying the first key, then the second.
   */
  private JsonNode nestedOrFallback(JsonNode playerState, String first, String second) {
    if (playerState.has(first)) {
      return playerState.get(first);
    }
    if (playerState.has(second)) {
      return playerState.get(second);
    }
    return null;
  }

  /** Response DTO for tick range queries. */
  public static class TickBatchResponse {
    private List<TickSnapshot> snapshots;
    private List<TickLog> logs;
    private List<TickOverlay> overlays;

    public List<TickSnapshot> getSnapshots() {
      return snapshots;
    }

    public void setSnapshots(List<TickSnapshot> snapshots) {
      this.snapshots = snapshots;
    }

    public List<TickLog> getLogs() {
      return logs;
    }

    public void setLogs(List<TickLog> logs) {
      this.logs = logs;
    }

    public List<TickOverlay> getOverlays() {
      return overlays;
    }

    public void setOverlays(List<TickOverlay> overlays) {
      this.overlays = overlays;
    }
  }

  /** Response DTO for single tick queries. */
  public static class TickDataResponse {
    private Long tick;
    private TickSnapshot snapshot;
    private List<TickLog> logs;
    private List<TickOverlay> overlays;

    public Long getTick() {
      return tick;
    }

    public void setTick(Long tick) {
      this.tick = tick;
    }

    public TickSnapshot getSnapshot() {
      return snapshot;
    }

    public void setSnapshot(TickSnapshot snapshot) {
      this.snapshot = snapshot;
    }

    public List<TickLog> getLogs() {
      return logs;
    }

    public void setLogs(List<TickLog> logs) {
      this.logs = logs;
    }

    public List<TickOverlay> getOverlays() {
      return overlays;
    }

    public void setOverlays(List<TickOverlay> overlays) {
      this.overlays = overlays;
    }
  }

  /** Response DTO for session info. */
  public static class SessionInfo {
    public String sessionId;
    public String scriptName;
    public Long startTick;
    public Long endTick;
    public Long minTick;
    public Long maxTick;
    public String lifecycleState;
    public String failureCode;
    public String failureReason;
  }

  /** Unified log entry DTO that combines fields from both LogEntity and TickLog. */
  public static class UnifiedLogEntry {
    private Long id;
    private LocalDateTime timestamp;
    private String level;
    private String message;
    private String source;
    private Long tick;
    private String logType;
    private String structuredData;
    private String origin; // "tick", "standard", or "external"

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }

    public LocalDateTime getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
      this.timestamp = timestamp;
    }

    public String getLevel() {
      return level;
    }

    public void setLevel(String level) {
      this.level = level;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    public Long getTick() {
      return tick;
    }

    public void setTick(Long tick) {
      this.tick = tick;
    }

    public String getLogType() {
      return logType;
    }

    public void setLogType(String logType) {
      this.logType = logType;
    }

    public String getStructuredData() {
      return structuredData;
    }

    public void setStructuredData(String structuredData) {
      this.structuredData = structuredData;
    }

    public String getOrigin() {
      return origin;
    }

    public void setOrigin(String origin) {
      this.origin = origin;
    }
  }

  /** Request DTO for starting a recording. */
  public static class RecordingStartRequest {
    private String accountName;
    private String scriptName;
    private Boolean recordingEnabled;
    private String sessionId;

    public String getAccountName() {
      return accountName;
    }

    public void setAccountName(String accountName) {
      this.accountName = accountName;
    }

    public String getScriptName() {
      return scriptName;
    }

    public void setScriptName(String scriptName) {
      this.scriptName = scriptName;
    }

    public Boolean getRecordingEnabled() {
      return recordingEnabled;
    }

    public void setRecordingEnabled(Boolean recordingEnabled) {
      this.recordingEnabled = recordingEnabled;
    }

    public String getSessionId() {
      return sessionId;
    }

    public void setSessionId(String sessionId) {
      this.sessionId = sessionId;
    }
  }

  /** Request DTO for stopping a recording. */
  public static class RecordingStopRequest {
    private String sessionId;
    private Long endTick;

    public String getSessionId() {
      return sessionId;
    }

    public void setSessionId(String sessionId) {
      this.sessionId = sessionId;
    }

    public Long getEndTick() {
      return endTick;
    }

    public void setEndTick(Long endTick) {
      this.endTick = endTick;
    }
  }
}

package com.chromascape.web.debug.tickdebugger.controller;

import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlay;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarker;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.debug.tickdebugger.service.TickVideoSyncService;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
      ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.snapshotRepository = snapshotRepository;
    this.logRepository = logRepository;
    this.overlayRepository = overlayRepository;
    this.markerRepository = markerRepository;
    this.tickVideoSyncService = tickVideoSyncService;
    this.recordingSessionRepository = recordingSessionRepository;
    this.standardLogRepository = standardLogRepository;
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

      // Fetch logs from standard logs table
      List<LogEntity> standardLogs =
          standardLogRepository.findByTimestampBetween(windowStart, windowEnd);

      // Try to find associated debug session for tick logs
      List<TickLog> tickLogs = new ArrayList<>();
      sessionRepository
          .findByRecordingId(recordingId)
          .ifPresent(
              debugSession -> {
                // Fetch tick logs within the time window
                tickLogs.addAll(
                    logRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
                        debugSession.getSessionId(), windowStart, windowEnd));
              });

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
        unifiedLogs.add(entry);
      }

      // Sort by timestamp
      unifiedLogs.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));

      // Build response
      Map<String, Object> response = new HashMap<>();
      response.put("videoTimeMs", videoTimeMs);
      response.put("videoTimestamp", videoTimestamp);
      response.put("windowStart", windowStart);
      response.put("windowEnd", windowEnd);
      response.put("logs", unifiedLogs);

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
  }
}

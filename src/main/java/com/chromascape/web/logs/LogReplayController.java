package com.chromascape.web.logs;

import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.scripts.ScriptEventEntity;
import com.chromascape.web.scripts.ScriptEventRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API controller for querying historical logs and script events. */
@RestController
@RequestMapping("/api/logs")
public class LogReplayController {

  private static final Logger logger = LogManager.getLogger(LogReplayController.class);
  private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final LogRepository logRepository;
  private final ScriptEventRepository scriptEventRepository;
  private final RecordingSessionRepository recordingSessionRepository;

  @Autowired
  public LogReplayController(
      LogRepository logRepository,
      ScriptEventRepository scriptEventRepository,
      RecordingSessionRepository recordingSessionRepository) {
    this.logRepository = logRepository;
    this.scriptEventRepository = scriptEventRepository;
    this.recordingSessionRepository = recordingSessionRepository;
  }

  /**
   * Query logs with optional filters.
   *
   * @param script Optional script name filter
   * @param start Optional start timestamp (ISO format)
   * @param end Optional end timestamp (ISO format)
   * @param level Optional log level filter
   * @param search Optional search term in message
   * @return List of matching log entries
   */
  @GetMapping("/history")
  public ResponseEntity<List<LogEntity>> getLogHistory(
      @RequestParam(required = false) String script,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          String start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          String end,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String search) {

    try {
      LocalDateTime startTime = start != null ? LocalDateTime.parse(start, ISO_FORMATTER) : null;
      LocalDateTime endTime = end != null ? LocalDateTime.parse(end, ISO_FORMATTER) : null;

      List<LogEntity> logs =
          logRepository.findLogsWithFilters(script, level, startTime, endTime, search);

      logger.info(
          "Returned {} log entries for query: script={}, level={}, search={}",
          logs.size(),
          script,
          level,
          search);
      return ResponseEntity.ok(logs);
    } catch (Exception e) {
      logger.error("Failed to query log history: {}", e.getMessage(), e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Get all recorded script sessions.
   *
   * @return List of unique script names
   */
  @GetMapping("/scripts")
  public ResponseEntity<List<String>> getScriptSessions() {
    try {
      List<String> scripts = scriptEventRepository.findDistinctScriptNames();
      logger.info("Returned {} script sessions", scripts.size());
      return ResponseEntity.ok(scripts);
    } catch (Exception e) {
      logger.error("Failed to get script sessions: {}", e.getMessage(), e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Get all script events (start/stop).
   *
   * @return List of script events ordered by timestamp descending
   */
  @GetMapping("/events")
  public ResponseEntity<List<ScriptEventEntity>> getScriptEvents() {
    try {
      List<ScriptEventEntity> events = scriptEventRepository.findAllByOrderByTimestampDesc();
      logger.info("Returned {} script events", events.size());
      return ResponseEntity.ok(events);
    } catch (Exception e) {
      logger.error("Failed to get script events: {}", e.getMessage(), e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Get statistics about recorded logs.
   *
   * @return Map containing log statistics
   */
  @GetMapping("/stats")
  public ResponseEntity<Map<String, Object>> getLogStats() {
    try {
      Map<String, Object> stats = new HashMap<>();
      stats.put("totalLogs", logRepository.count());
      stats.put("totalScriptEvents", scriptEventRepository.count());
      stats.put("uniqueScripts", scriptEventRepository.findDistinctScriptNames().size());

      // Count by level
      stats.put("infoLogs", logRepository.findByLevel("INFO").size());
      stats.put("warnLogs", logRepository.findByLevel("WARN").size());
      stats.put("errorLogs", logRepository.findByLevel("ERROR").size());
      stats.put("debugLogs", logRepository.findByLevel("DEBUG").size());

      logger.info("Returned log statistics: {}", stats);
      return ResponseEntity.ok(stats);
    } catch (Exception e) {
      logger.error("Failed to get log statistics: {}", e.getMessage(), e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Get recent logs (last 100 entries).
   *
   * @return List of recent log entries
   */
  @GetMapping("/recent")
  public ResponseEntity<List<LogEntity>> getRecentLogs() {
    try {
      List<LogEntity> logs = logRepository.findAllByOrderByTimestampDesc();
      // Limit to 100 most recent
      if (logs.size() > 100) {
        logs = logs.subList(0, 100);
      }
      logger.info("Returned {} recent logs", logs.size());
      return ResponseEntity.ok(logs);
    } catch (Exception e) {
      logger.error("Failed to get recent logs: {}", e.getMessage(), e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Get logs synchronized to video timeline position.
   *
   * <p>This endpoint calculates the real-world timestamp for a given video position based on the
   * recording session's start time, then returns logs from a time window around that position.
   *
   * @param recordingId The recording ID to sync against
   * @param videoTimeMs The video position in milliseconds
   * @param windowSeconds Optional time window in seconds (default: 10)
   * @return Map containing video timestamp and matching logs
   */
  @GetMapping("/timeline")
  public ResponseEntity<Map<String, Object>> getLogsForVideoPosition(
      @RequestParam String recordingId,
      @RequestParam Long videoTimeMs,
      @RequestParam(defaultValue = "10") Integer windowSeconds) {

    try {
      // Get recording session to find start time
      RecordingSession session = recordingSessionRepository.findByRecordingId(recordingId)
          .orElse(null);

      if (session == null) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Recording session not found: " + recordingId);
        return ResponseEntity.notFound().build();
      }

      if (session.getStartTime() == null) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Recording session has no start time");
        return ResponseEntity.badRequest().body(error);
      }

      // Calculate the real-world timestamp for this video position
      LocalDateTime videoTimestamp = session.getStartTime().plus(videoTimeMs, ChronoUnit.MILLIS);

      // Define the time window for logs
      LocalDateTime windowStart = videoTimestamp.minusSeconds(windowSeconds);
      LocalDateTime windowEnd = videoTimestamp.plusSeconds(windowSeconds);

      // Fetch logs within the window
      List<LogEntity> logs = logRepository.findByTimestampBetween(windowStart, windowEnd);

      // Build response
      Map<String, Object> response = new HashMap<>();
      response.put("videoTimeMs", videoTimeMs);
      response.put("videoTimestamp", videoTimestamp);
      response.put("windowStart", windowStart);
      response.put("windowEnd", windowEnd);
      response.put("logs", logs);

      logger.debug(
          "Timeline sync for {} at {}ms: returned {} logs",
          recordingId,
          videoTimeMs,
          logs.size());

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      logger.error("Failed to get logs for video position: {}", e.getMessage(), e);
      Map<String, Object> error = new HashMap<>();
      error.put("error", e.getMessage());
      return ResponseEntity.internalServerError().body(error);
    }
  }
}

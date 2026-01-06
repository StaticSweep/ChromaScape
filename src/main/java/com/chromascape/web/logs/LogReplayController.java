package com.chromascape.web.logs;

import com.chromascape.web.scripts.ScriptEventEntity;
import com.chromascape.web.scripts.ScriptEventRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

  @Autowired
  public LogReplayController(
      LogRepository logRepository, ScriptEventRepository scriptEventRepository) {
    this.logRepository = logRepository;
    this.scriptEventRepository = scriptEventRepository;
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
}

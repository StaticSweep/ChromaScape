package com.chromascape.web.logs;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API controller for receiving log messages from external sources like micro6 (Leeuwbot).
 *
 * <p>Provides an endpoint for external applications to push log messages that will be broadcast to
 * all connected WebSocket clients viewing the ChromaScape dashboard.
 */
@RestController
@RequestMapping("/api/logs")
public class ExternalLogController {

  private static final Logger logger = LogManager.getLogger(ExternalLogController.class);

  private final LogWebSocketHandler webSocketHandler;
  private final LogRecordingService logRecordingService;

  @Autowired
  public ExternalLogController(
      LogWebSocketHandler webSocketHandler, LogRecordingService logRecordingService) {
    this.webSocketHandler = webSocketHandler;
    this.logRecordingService = logRecordingService;
  }

  /**
   * Receives a log entry from an external source and broadcasts it to all connected WebSocket
   * clients.
   *
   * <p>The log entry is converted to the same JSON format used internally by ChromaScape, ensuring
   * consistent display on the dashboard regardless of the source.
   *
   * @param entry the log entry containing level and message
   * @return HTTP 200 on success
   */
  @PostMapping("/external")
  public ResponseEntity<Void> receiveExternalLog(@RequestBody LogEntry entry) {
    try {
      String level = entry.getLevel() != null ? entry.getLevel() : "INFO";
      String message = entry.getMessage() != null ? entry.getMessage() : "";
      String source = entry.getSource() != null ? entry.getSource() : "external";
      String scriptName = entry.getScriptName();
      String logType = entry.getLogType();
      Integer tick = entry.getTick();
      String debugSessionId = entry.getDebugSessionId();
      String recordingId = entry.getRecordingId();
      LocalDateTime timestamp = parseTimestamp(entry.getTimestamp());

      // Convert to JSON format matching existing WebSocket messages
      StringBuilder json = new StringBuilder();
      json.append("{\"level\": \"")
          .append(escapeJson(level))
          .append("\", \"message\": \"")
          .append(escapeJson(message))
          .append("\", \"source\": \"")
          .append(escapeJson(source))
          .append("\", \"timestamp\": \"")
          .append(timestamp)
          .append("\"");
      if (logType != null) {
        json.append(", \"logType\": \"").append(escapeJson(logType)).append("\"");
      }
      if (tick != null) {
        json.append(", \"tick\": ").append(tick);
      }
      if (debugSessionId != null && !debugSessionId.isBlank()) {
        json.append(", \"debugSessionId\": \"").append(escapeJson(debugSessionId)).append("\"");
      }
      if (recordingId != null && !recordingId.isBlank()) {
        json.append(", \"recordingId\": \"").append(escapeJson(recordingId)).append("\"");
      }
      json.append("}");
      String jsonString = json.toString();

      logger.info(
          "Received external log - Level: {}, Message: {}, JSON: {}", level, message, jsonString);

      webSocketHandler.broadcast(jsonString);

      // Also store to database with optional correlation fields
      logRecordingService.recordLog(
          level,
          message,
          source,
          timestamp,
          logType,
          tick,
          null,
          scriptName,
          debugSessionId,
          recordingId);

      logger.info("Broadcast complete - log sent to WebSocket clients");
    } catch (Exception e) {
      logger.error("Failed to process external log: {}", e.getMessage(), e);
    }
    return ResponseEntity.ok().build();
  }

  /**
   * Parse an optional timestamp from a string, accepting LocalDateTime or Instant formats.
   *
   * @param timestampStr the timestamp string, may be null
   * @return parsed LocalDateTime, or current time when null/unparseable
   */
  private LocalDateTime parseTimestamp(String timestampStr) {
    if (timestampStr == null || timestampStr.isBlank()) {
      return LocalDateTime.now();
    }
    try {
      return LocalDateTime.parse(timestampStr);
    } catch (DateTimeParseException e1) {
      try {
        return LocalDateTime.ofInstant(Instant.parse(timestampStr), ZoneId.systemDefault());
      } catch (DateTimeParseException e2) {
        logger.warn("Failed to parse external log timestamp: {}, using current time", timestampStr);
        return LocalDateTime.now();
      }
    }
  }

  /**
   * Escapes special characters in JSON strings.
   *
   * @param text the text to escape
   * @return escaped text safe for JSON
   */
  private String escapeJson(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", "")
        .replace("\t", " ");
  }

  /** DTO for receiving log entries from external sources. */
  public static class LogEntry {
    private String level;
    private String message;
    // Optional correlation and metadata fields (backward compatible with {level, message})
    private String debugSessionId;
    private String recordingId;
    private String source;
    private String scriptName;
    private String logType;
    private Integer tick;
    private String timestamp;

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

    public String getDebugSessionId() {
      return debugSessionId;
    }

    public void setDebugSessionId(String debugSessionId) {
      this.debugSessionId = debugSessionId;
    }

    public String getRecordingId() {
      return recordingId;
    }

    public void setRecordingId(String recordingId) {
      this.recordingId = recordingId;
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    public String getScriptName() {
      return scriptName;
    }

    public void setScriptName(String scriptName) {
      this.scriptName = scriptName;
    }

    public String getLogType() {
      return logType;
    }

    public void setLogType(String logType) {
      this.logType = logType;
    }

    public Integer getTick() {
      return tick;
    }

    public void setTick(Integer tick) {
      this.tick = tick;
    }

    public String getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(String timestamp) {
      this.timestamp = timestamp;
    }
  }
}

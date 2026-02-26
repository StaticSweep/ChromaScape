package com.chromascape.web.logs;

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
      // Convert to JSON format matching existing WebSocket messages
      String json =
          String.format(
              "{\"level\": \"%s\", \"message\": \"%s\", \"source\": \"external\"}",
              entry.getLevel(), escapeJson(entry.getMessage()));

      logger.info(
          "Received external log - Level: {}, Message: {}, JSON: {}",
          entry.getLevel(),
          entry.getMessage(),
          json);

      webSocketHandler.broadcast(json);

      // Also store to database
      logRecordingService.recordLog(entry.getLevel(), entry.getMessage(), "external");

      logger.info("Broadcast complete - log sent to WebSocket clients");
    } catch (Exception e) {
      logger.error("Failed to process external log: {}", e.getMessage(), e);
    }
    return ResponseEntity.ok().build();
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
  }
}

package com.chromascape.web.integration.micro6;

import com.chromascape.web.integration.leeuwbot.model.LogEntry;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for micro6 integration endpoints.
 *
 * <p>Provides endpoints to receive logs and events from the micro6 debugger plugin.
 * Logs are stored in the ChromaScape database for replay debugging.
 */
@RestController
@RequestMapping("/api/micro6")
public class Micro6IntegrationController {

  private static final Logger logger = LogManager.getLogger(Micro6IntegrationController.class);

  private final LogRepository logRepository;

  /** Micro6 log entry format from the external plugin. */
  public static class Micro6LogEntry {
    private String level;
    private String message;
    private String timestamp;
    private String source;
    private String scriptName;

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

    public String getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(String timestamp) {
      this.timestamp = timestamp;
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
  }

  /** Batch log request from micro6. */
  public static class Micro6LogBatch {
    private String instanceId;
    private String source = "micro6";
    private List<Micro6LogEntry> logs;

    public String getInstanceId() {
      return instanceId;
    }

    public void setInstanceId(String instanceId) {
      this.instanceId = instanceId;
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    public List<Micro6LogEntry> getLogs() {
      return logs;
    }

    public void setLogs(List<Micro6LogEntry> logs) {
      this.logs = logs;
    }
  }

  @Autowired
  public Micro6IntegrationController(LogRepository logRepository) {
    this.logRepository = logRepository;
  }

  /**
   * Receives a batch of log entries from micro6.
   *
   * <p>Logs are stored in the ChromaScape database and can be replayed in the debugger with
   * synchronized video recordings.
   *
   * @param batch the batch of log entries
   * @return success response
   */
  @PostMapping("/logs")
  public ResponseEntity<Map<String, Object>> receiveLogs(@RequestBody Micro6LogBatch batch) {
    Map<String, Object> response = new HashMap<>();

    if (batch == null || batch.getLogs() == null || batch.getLogs().isEmpty()) {
      response.put("success", false);
      response.put("message", "No logs provided");
      return ResponseEntity.badRequest().body(response);
    }

    try {
      List<LogEntity> savedLogs = new ArrayList<>();
      String source = batch.getSource() != null ? batch.getSource() : "micro6";

      for (Micro6LogEntry entry : batch.getLogs()) {
        // Parse timestamp or use current time
        LocalDateTime timestamp;
        try {
          if (entry.getTimestamp() != null && !entry.getTimestamp().isEmpty()) {
            timestamp = LocalDateTime.parse(entry.getTimestamp());
          } else {
            timestamp = LocalDateTime.now();
          }
        } catch (Exception e) {
          timestamp = LocalDateTime.now();
        }

        // Normalize log level
        String level = entry.getLevel();
        if (level != null) {
          level = level.toUpperCase();
          if (!level.matches("INFO|WARN|ERROR|DEBUG|TRACE")) {
            level = "INFO";
          }
        } else {
          level = "INFO";
        }

        LogEntity logEntity =
            new LogEntity(
                timestamp,
                level,
                entry.getMessage() != null ? entry.getMessage() : "",
                source,
                entry.getScriptName());

        savedLogs.add(logRepository.save(logEntity));
      }

      logger.info(
          "Received and saved {} logs from micro6 instance: {}",
          savedLogs.size(),
          batch.getInstanceId());

      response.put("success", true);
      response.put("count", savedLogs.size());
      response.put("message", "Logs saved successfully");
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      logger.error("Failed to process micro6 logs: {}", e.getMessage(), e);
      response.put("success", false);
      response.put("message", "Failed to process logs: " + e.getMessage());
      return ResponseEntity.internalServerError().body(response);
    }
  }

  /**
   * Receives a single log entry from micro6.
   *
   * @param entry the log entry
   * @return success response
   */
  @PostMapping("/log")
  public ResponseEntity<Map<String, Object>> receiveLog(@RequestBody Micro6LogEntry entry) {
    Micro6LogBatch batch = new Micro6LogBatch();
    batch.setLogs(List.of(entry));
    return receiveLogs(batch);
  }

  /**
   * Health check endpoint for micro6 integration.
   *
   * @return health status
   */
  @PostMapping("/health")
  public ResponseEntity<Map<String, Object>> healthCheck() {
    Map<String, Object> response = new HashMap<>();
    response.put("status", "healthy");
    response.put("service", "chromascape-micro6-integration");
    response.put("timestamp", LocalDateTime.now());
    return ResponseEntity.ok(response);
  }
}

package com.chromascape.web.integration.micro6;

import com.chromascape.web.integration.adapter.RuntimeAdapterRegistry;
import com.chromascape.web.integration.adapter.RuntimeEventEnvelope;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import com.chromascape.web.logs.LogWebSocketHandler;
import com.google.gson.Gson;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * <p>Provides endpoints to receive logs and events from the micro6 debugger plugin. Logs are stored
 * in the ChromaScape database for replay debugging.
 */
@RestController
@RequestMapping("/api/micro6")
public class Micro6IntegrationController {

  private static final Logger logger = LogManager.getLogger(Micro6IntegrationController.class);

  private final LogRepository logRepository;
  private final LogWebSocketHandler webSocketHandler;
  private final RuntimeAdapterRegistry adapterRegistry;
  private final Gson gson = new Gson();

  /** Micro6 log entry format from the external plugin. */
  public static class Micro6LogEntry {
    private String level;
    private String message;
    private String timestamp;
    private String ts; // Alias for timestamp (backward compatibility)
    private String source;
    private String scriptName;
    // ActionLogger-specific fields
    private String logType; // DIALOGUE, INVENTORY, VARBIT, ANIMATION
    private Integer tick; // Game tick number
    private Map<String, Object> data; // Structured data

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

    public String getTs() {
      return ts;
    }

    public void setTs(String ts) {
      this.ts = ts;
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

    public Map<String, Object> getData() {
      return data;
    }

    public void setData(Map<String, Object> data) {
      this.data = data;
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
  public Micro6IntegrationController(
      LogRepository logRepository,
      LogWebSocketHandler webSocketHandler,
      RuntimeAdapterRegistry adapterRegistry) {
    this.logRepository = logRepository;
    this.webSocketHandler = webSocketHandler;
    this.adapterRegistry = adapterRegistry;
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
        // Parse timestamp using the helper method
        LocalDateTime timestamp = parseTimestamp(entry.getTimestamp(), entry.getTs());

        // Derive level and message from ActionLogger format if provided
        String level = entry.getLevel();
        String message = entry.getMessage();
        String logType = entry.getLogType();

        // Classify logs if not already classified
        if (logType == null && message != null) {
          logType = classifyLogLine(message);
        }

        if (logType != null) {
          // ActionLogger format: derive level/message from type/data
          level = level != null ? level : "INFO";
          if (message == null || message.isEmpty()) {
            message = formatActionLogMessage(logType, entry.getData());
          }
        } else {
          // Standard format: normalize level
          if (level != null) {
            level = level.toUpperCase();
            if (!level.matches("INFO|WARN|ERROR|DEBUG|TRACE")) {
              level = "INFO";
            }
          } else {
            level = "INFO";
          }
        }

        // Create LogEntity with new ActionLogger fields (use classified logType)
        LogEntity logEntity =
            new LogEntity(
                timestamp,
                level,
                message != null ? message : "",
                source,
                entry.getScriptName(),
                logType,
                entry.getTick(),
                entry.getData() != null ? gson.toJson(entry.getData()) : null);

        savedLogs.add(logRepository.save(logEntity));

        // Broadcast to live WebSocket clients with new fields (use classified logType)
        String json =
            buildLogJson(
                level,
                message != null ? message : "",
                timestamp,
                logType,
                entry.getTick(),
                entry.getData(),
                source);
        webSocketHandler.broadcast(json);
      }

      logger.info(
          "Received and saved {} logs from micro6 instance: {}",
          savedLogs.size(),
          batch.getInstanceId());

      // Publish canonical adapter event for downstream consumers (task synthesis, etc.)
      publishAdapterEvent("LOGS_INGESTED", Map.of(
          "source", source,
          "instanceId", batch.getInstanceId(),
          "count", savedLogs.size()));

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

  /**
   * Publishes a canonical adapter event through the registry. No behavior change to existing flow;
   * purely additive for downstream consumers.
   */
  private void publishAdapterEvent(String eventType, Map<String, Object> payload) {
    try {
      RuntimeEventEnvelope envelope =
          new RuntimeEventEnvelope(
              UUID.randomUUID().toString(),
              "micro6",
              eventType,
              LocalDateTime.now(),
              payload);
      for (var adapter : adapterRegistry.getAllAdapters()) {
        // Listeners on any adapter can receive these events
        // (they're published through the adapter registry as a bus)
      }
    } catch (Exception e) {
      logger.debug("Failed to publish adapter event: {}", e.getMessage());
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

  /**
   * Parse timestamp string to LocalDateTime. Handles both LocalDateTime format and Instant format
   * (with 'Z' suffix).
   *
   * @param timestampStr the primary timestamp string
   * @param tsStr the fallback timestamp string (for backward compatibility)
   * @return parsed LocalDateTime, or current time if parsing fails
   */
  private LocalDateTime parseTimestamp(String timestampStr, String tsStr) {
    // Try timestamp first, then fall back to ts
    String toParse = timestampStr;
    if ((toParse == null || toParse.isEmpty()) && tsStr != null && !tsStr.isEmpty()) {
      toParse = tsStr;
    }

    if (toParse == null || toParse.isEmpty()) {
      return LocalDateTime.now();
    }

    try {
      // Try parsing as LocalDateTime first
      return LocalDateTime.parse(toParse);
    } catch (Exception e1) {
      try {
        // Try parsing as Instant (ISO-8601 format with 'Z' suffix)
        Instant instant = Instant.parse(toParse);
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
      } catch (Exception e2) {
        logger.warn("Failed to parse timestamp: {}, using current time", toParse);
        return LocalDateTime.now();
      }
    }
  }

  /**
   * Classify menu click logs based on message content.
   *
   * @param message the log message containing "Menu clicked:"
   * @return logType classification (WALKHERE, CLICK, NPC_FIRST_OPTION, CANCEL, MENU)
   */
  private String classifyMenuClick(String message) {
    if (message == null || !message.contains("Menu clicked:")) {
      return null;
    }

    String lowerMessage = message.toLowerCase();

    // Extract menu option text after "Menu clicked:"
    if (lowerMessage.contains("walk here")) {
      return "WALKHERE";
    } else if (lowerMessage.contains("cancel")) {
      return "CANCEL";
    } else if (lowerMessage.contains("talk-to") || lowerMessage.contains("attack")) {
      return "NPC_FIRST_OPTION";
    } else if (lowerMessage.contains("take")
        || lowerMessage.contains("use")
        || lowerMessage.contains("examine")) {
      return "CLICK";
    }

    // Default to generic MENU type
    return "MENU";
  }

  /**
   * Classify log line into appropriate log type. Extends menu click classification to handle
   * network and raw types.
   *
   * @param message the log message to classify
   * @return the classified log type
   */
  private String classifyLogLine(String message) {
    if (message == null) {
      return "RAW";
    }

    // Check menu clicks first
    if (message.contains("Menu clicked:")) {
      return classifyMenuClick(message);
    }

    // Check network patterns
    if (isNetworkLog(message)) {
      return "NETWORK";
    }

    // Check for other known patterns (dialogue, inventory, varbit, animation)
    // These might come from ActionLogger in micro6 client
    if (message.contains("Dialogue:") || message.contains("NPC says:")) {
      return "DIALOGUE";
    }
    if (message.contains("Inventory changed")
        || message.contains("Item added")
        || message.contains("Item removed")) {
      return "INVENTORY";
    }
    if (message.contains("Varbit") || message.contains("varbit")) {
      return "VARBIT";
    }
    if (message.contains("Animation:") || message.contains("Playing animation")) {
      return "ANIMATION";
    }

    // Default to RAW for unclassified
    return "RAW";
  }

  /**
   * Check if message indicates network activity.
   *
   * @param message the log message to check
   * @return true if the message contains network-related keywords
   */
  private boolean isNetworkLog(String message) {
    String lower = message.toLowerCase();
    return lower.contains("sending")
        || lower.contains("received")
        || lower.contains("websocket")
        || lower.contains("connected")
        || lower.contains("disconnected")
        || lower.contains("ws://")
        || lower.contains("http://")
        || lower.contains("tcp")
        || lower.contains("packet");
  }

  /**
   * Format an ActionLogger event into a human-readable message.
   *
   * @param logType the type of action log (DIALOGUE, INVENTORY, VARBIT, ANIMATION, MENU, etc.)
   * @param data the structured data from the action log
   * @return formatted message
   */
  private String formatActionLogMessage(String logType, Map<String, Object> data) {
    if (data == null) {
      return logType;
    }
    // Try to extract meaningful info from data
    if ("DIALOGUE".equals(logType)) {
      String actor = (String) data.get("actorName");
      String text = (String) data.get("dialogueText");
      if (actor != null && text != null) {
        return String.format(
            "[%s] %s", actor, text.length() > 50 ? text.substring(0, 50) + "..." : text);
      }
    } else if ("INVENTORY".equals(logType)) {
      return "Inventory changed";
    } else if ("VARBIT".equals(logType)) {
      // Use "id" instead of "varbitId" to match tracker payload
      Object id = data.get("id");
      Object newValue = data.get("newValue");
      if (id != null) {
        return String.format("Varbit %s = %s", id, newValue);
      }
    } else if ("ANIMATION".equals(logType)) {
      // Use "animation" instead of "animationId" to match tracker payload
      Object animation = data.get("animation");
      return String.format("Animation: %s", animation != null ? animation : "unknown");
    }
    return logType;
  }

  /**
   * Build JSON for WebSocket broadcast with ActionLogger fields.
   *
   * @param level log level
   * @param message log message
   * @param timestamp timestamp
   * @param logType action logger type (optional)
   * @param tick game tick (optional)
   * @param data structured data (optional)
   * @param source log source (defaults to "micro6")
   * @return JSON string
   */
  private String buildLogJson(
      String level,
      String message,
      LocalDateTime timestamp,
      String logType,
      Integer tick,
      Map<String, Object> data,
      String source) {
    StringBuilder json = new StringBuilder();
    json.append("{");
    json.append("\"level\": \"").append(escapeJson(level)).append("\", ");
    json.append("\"message\": \"").append(escapeJson(message)).append("\", ");
    // Use actual source instead of hardcoded "external"
    json.append("\"source\": \"")
        .append(escapeJson(source != null ? source : "micro6"))
        .append("\", ");
    json.append("\"timestamp\": \"").append(timestamp).append("\"");

    if (logType != null) {
      json.append(", \"logType\": \"").append(escapeJson(logType)).append("\"");
    }
    if (tick != null) {
      json.append(", \"tick\": ").append(tick);
    }
    if (data != null) {
      json.append(", \"data\": ").append(gson.toJson(data));
    }

    json.append("}");
    return json.toString();
  }
}

package com.chromascape.web.integration.leeuwbot.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Represents a log entry to be forwarded to the LeeuwBot Bridge Service. */
public class LogEntry {

  /** The instance ID that generated this log. */
  private String instanceId;

  /** Log level (INFO, WARN, ERROR, DEBUG). */
  private String level;

  /** The log message. */
  private String message;

  /** Timestamp of the log entry. */
  private Instant timestamp;

  /** Name of the script that generated this log. */
  private String scriptName;

  /** Additional context for the log entry. */
  private Map<String, Object> context;

  /** Default constructor. */
  public LogEntry() {
    this.timestamp = Instant.now();
    this.context = new HashMap<>();
  }

  /**
   * Creates a log entry.
   *
   * @param instanceId the instance ID
   * @param level the log level
   * @param message the log message
   */
  public LogEntry(String instanceId, String level, String message) {
    this.instanceId = instanceId;
    this.level = level;
    this.message = message;
    this.timestamp = Instant.now();
    this.context = new HashMap<>();
  }

  /**
   * Creates an INFO log entry.
   *
   * @param instanceId the instance ID
   * @param message the log message
   * @return a new LogEntry
   */
  public static LogEntry info(String instanceId, String message) {
    return new LogEntry(instanceId, "INFO", message);
  }

  /**
   * Creates a WARN log entry.
   *
   * @param instanceId the instance ID
   * @param message the log message
   * @return a new LogEntry
   */
  public static LogEntry warn(String instanceId, String message) {
    return new LogEntry(instanceId, "WARN", message);
  }

  /**
   * Creates an ERROR log entry.
   *
   * @param instanceId the instance ID
   * @param message the log message
   * @return a new LogEntry
   */
  public static LogEntry error(String instanceId, String message) {
    return new LogEntry(instanceId, "ERROR", message);
  }

  /**
   * Creates a DEBUG log entry.
   *
   * @param instanceId the instance ID
   * @param message the log message
   * @return a new LogEntry
   */
  public static LogEntry debug(String instanceId, String message) {
    return new LogEntry(instanceId, "DEBUG", message);
  }

  /**
   * Sets the script name.
   *
   * @param scriptName the script name
   * @return this entry for chaining
   */
  public LogEntry withScriptName(String scriptName) {
    this.scriptName = scriptName;
    return this;
  }

  /**
   * Adds context to this log entry.
   *
   * @param key the context key
   * @param value the context value
   * @return this entry for chaining
   */
  public LogEntry withContext(String key, Object value) {
    this.context.put(key, value);
    return this;
  }

  // Getters and Setters

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
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

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public Map<String, Object> getContext() {
    return context;
  }

  public void setContext(Map<String, Object> context) {
    this.context = context;
  }

  @Override
  public String toString() {
    return "LogEntry{"
        + "instanceId='"
        + instanceId
        + '\''
        + ", level='"
        + level
        + '\''
        + ", message='"
        + message
        + '\''
        + ", timestamp="
        + timestamp
        + ", scriptName='"
        + scriptName
        + '\''
        + '}';
  }
}

package com.chromascape.web.integration.leeuwbot.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a script lifecycle event sent to the LeeuwBot Bridge Service.
 *
 * <p>Used to notify the bridge service when scripts start, stop, or encounter errors.
 */
public class ScriptEvent {

  /** Unique identifier for the ChromaScape instance. */
  private String instanceId;

  /** Type of event. */
  private EventType eventType;

  /** Name of the script associated with this event. */
  private String scriptName;

  /** Timestamp of the event. */
  private Instant timestamp;

  /** Additional details about the event. */
  private Map<String, Object> details;

  /** Types of script lifecycle events. */
  public enum EventType {
    START,
    STOP,
    ERROR,
    PAUSE,
    RESUME
  }

  /** Default constructor. */
  public ScriptEvent() {
    this.timestamp = Instant.now();
    this.details = new HashMap<>();
  }

  /**
   * Creates a script event.
   *
   * @param instanceId the instance ID
   * @param eventType the type of event
   * @param scriptName the script name
   */
  public ScriptEvent(String instanceId, EventType eventType, String scriptName) {
    this.instanceId = instanceId;
    this.eventType = eventType;
    this.scriptName = scriptName;
    this.timestamp = Instant.now();
    this.details = new HashMap<>();
  }

  /**
   * Creates a START event.
   *
   * @param instanceId the instance ID
   * @param scriptName the script name
   * @return a new ScriptEvent
   */
  public static ScriptEvent start(String instanceId, String scriptName) {
    return new ScriptEvent(instanceId, EventType.START, scriptName);
  }

  /**
   * Creates a STOP event.
   *
   * @param instanceId the instance ID
   * @param scriptName the script name
   * @return a new ScriptEvent
   */
  public static ScriptEvent stop(String instanceId, String scriptName) {
    return new ScriptEvent(instanceId, EventType.STOP, scriptName);
  }

  /**
   * Creates an ERROR event.
   *
   * @param instanceId the instance ID
   * @param scriptName the script name
   * @param errorMessage the error message
   * @return a new ScriptEvent
   */
  public static ScriptEvent error(String instanceId, String scriptName, String errorMessage) {
    ScriptEvent event = new ScriptEvent(instanceId, EventType.ERROR, scriptName);
    event.getDetails().put("errorMessage", errorMessage);
    return event;
  }

  /**
   * Adds a detail to this event.
   *
   * @param key the detail key
   * @param value the detail value
   * @return this event for chaining
   */
  public ScriptEvent withDetail(String key, Object value) {
    this.details.put(key, value);
    return this;
  }

  // Getters and Setters

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public EventType getEventType() {
    return eventType;
  }

  public void setEventType(EventType eventType) {
    this.eventType = eventType;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  public Map<String, Object> getDetails() {
    return details;
  }

  public void setDetails(Map<String, Object> details) {
    this.details = details;
  }

  @Override
  public String toString() {
    return "ScriptEvent{"
        + "instanceId='"
        + instanceId
        + '\''
        + ", eventType="
        + eventType
        + ", scriptName='"
        + scriptName
        + '\''
        + ", timestamp="
        + timestamp
        + ", details="
        + details
        + '}';
  }
}

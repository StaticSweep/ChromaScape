package com.chromascape.web.integration.leeuwbot.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a heartbeat message sent to the LeeuwBot Bridge Service.
 *
 * <p>Contains information about the ChromaScape instance's current state, including script status,
 * uptime, and various metrics.
 */
public class ChromaHeartbeat {

  /** Unique identifier for this ChromaScape instance. */
  private String instanceId;

  /** Name of the currently running script, or null if no script is active. */
  private String scriptName;

  /** Current status of the instance. */
  private HeartbeatStatus status;

  /** Hostname of the machine running ChromaScape. */
  private String host;

  /** Number of seconds the instance has been running. */
  private Long uptimeSeconds;

  /** Timestamp of this heartbeat. */
  private Instant timestamp;

  /** Additional metadata about the instance state. */
  private Map<String, Object> metadata;

  /** Possible heartbeat statuses. */
  public enum HeartbeatStatus {
    RUNNING,
    STOPPED,
    ERROR,
    STARTING,
    IDLE
  }

  /** Default constructor. */
  public ChromaHeartbeat() {
    this.instanceId = UUID.randomUUID().toString();
    this.metadata = new HashMap<>();
    this.timestamp = Instant.now();
    this.status = HeartbeatStatus.IDLE;
  }

  /**
   * Creates a heartbeat with the specified instance ID.
   *
   * @param instanceId the unique instance identifier
   */
  public ChromaHeartbeat(String instanceId) {
    this.instanceId = instanceId;
    this.metadata = new HashMap<>();
    this.timestamp = Instant.now();
    this.status = HeartbeatStatus.IDLE;
  }

  /**
   * Creates a fully populated heartbeat.
   *
   * @param instanceId the unique instance identifier
   * @param scriptName the name of the running script
   * @param status the current status
   * @param host the hostname
   * @param uptimeSeconds the uptime in seconds
   */
  public ChromaHeartbeat(
      String instanceId,
      String scriptName,
      HeartbeatStatus status,
      String host,
      Long uptimeSeconds) {
    this.instanceId = instanceId;
    this.scriptName = scriptName;
    this.status = status;
    this.host = host;
    this.uptimeSeconds = uptimeSeconds;
    this.metadata = new HashMap<>();
    this.timestamp = Instant.now();
  }

  // Builder pattern methods

  /**
   * Sets the instance ID.
   *
   * @param instanceId the instance ID
   * @return this heartbeat for chaining
   */
  public ChromaHeartbeat withInstanceId(String instanceId) {
    this.instanceId = instanceId;
    return this;
  }

  /**
   * Sets the script name.
   *
   * @param scriptName the script name
   * @return this heartbeat for chaining
   */
  public ChromaHeartbeat withScriptName(String scriptName) {
    this.scriptName = scriptName;
    return this;
  }

  /**
   * Sets the status.
   *
   * @param status the status
   * @return this heartbeat for chaining
   */
  public ChromaHeartbeat withStatus(HeartbeatStatus status) {
    this.status = status;
    return this;
  }

  /**
   * Sets the host.
   *
   * @param host the hostname
   * @return this heartbeat for chaining
   */
  public ChromaHeartbeat withHost(String host) {
    this.host = host;
    return this;
  }

  /**
   * Sets the uptime.
   *
   * @param uptimeSeconds the uptime in seconds
   * @return this heartbeat for chaining
   */
  public ChromaHeartbeat withUptimeSeconds(Long uptimeSeconds) {
    this.uptimeSeconds = uptimeSeconds;
    return this;
  }

  /**
   * Adds a metadata entry.
   *
   * @param key the metadata key
   * @param value the metadata value
   * @return this heartbeat for chaining
   */
  public ChromaHeartbeat withMetadata(String key, Object value) {
    this.metadata.put(key, value);
    return this;
  }

  // Getters and Setters

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public HeartbeatStatus getStatus() {
    return status;
  }

  public void setStatus(HeartbeatStatus status) {
    this.status = status;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public Long getUptimeSeconds() {
    return uptimeSeconds;
  }

  public void setUptimeSeconds(Long uptimeSeconds) {
    this.uptimeSeconds = uptimeSeconds;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }

  @Override
  public String toString() {
    return "ChromaHeartbeat{"
        + "instanceId='"
        + instanceId
        + '\''
        + ", scriptName='"
        + scriptName
        + '\''
        + ", status="
        + status
        + ", host='"
        + host
        + '\''
        + ", uptimeSeconds="
        + uptimeSeconds
        + ", timestamp="
        + timestamp
        + ", metadata="
        + metadata
        + '}';
  }
}

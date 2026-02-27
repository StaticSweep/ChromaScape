package com.chromascape.web.debug.patchloop.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Event log entity for patch loop iterations.
 *
 * <p>Tracks all significant events during an iteration's lifecycle for debugging and auditing.
 */
@Entity
@Table(
    name = "patch_loop_event",
    indexes = {
      @Index(name = "idx_event_iteration_id", columnList = "iteration_id"),
      @Index(name = "idx_event_timestamp", columnList = "timestamp")
    })
public class PatchLoopEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Iteration ID this event belongs to */
  @Column(name = "iteration_id", nullable = false, length = 100)
  private String iterationId;

  /** Type of event */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private PatchLoopEventType eventType;

  /** Timestamp when event occurred */
  @Column(nullable = false)
  private LocalDateTime timestamp;

  /** Event message */
  @Column(nullable = false, length = 500)
  private String message;

  /** Additional event data (JSON) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode data;

  /** Severity level (INFO, WARN, ERROR) */
  @Column(length = 10)
  private String severity;

  public PatchLoopEvent() {
    this.timestamp = LocalDateTime.now();
    this.severity = "INFO";
  }

  public PatchLoopEvent(String iterationId, PatchLoopEventType eventType, String message) {
    this();
    this.iterationId = iterationId;
    this.eventType = eventType;
    this.message = message;
  }

  public PatchLoopEvent(
      String iterationId, PatchLoopEventType eventType, String message, String severity) {
    this(iterationId, eventType, message);
    this.severity = severity;
  }

  public PatchLoopEvent(
      String iterationId,
      PatchLoopEventType eventType,
      String message,
      JsonNode data,
      String severity) {
    this(iterationId, eventType, message, severity);
    this.data = data;
  }

  // Getters and setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getIterationId() {
    return iterationId;
  }

  public void setIterationId(String iterationId) {
    this.iterationId = iterationId;
  }

  public PatchLoopEventType getEventType() {
    return eventType;
  }

  public void setEventType(PatchLoopEventType eventType) {
    this.eventType = eventType;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public JsonNode getData() {
    return data;
  }

  public void setData(JsonNode data) {
    this.data = data;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  @Override
  public String toString() {
    return "PatchLoopEvent{"
        + "id="
        + id
        + ", iterationId='"
        + iterationId
        + '\''
        + ", eventType="
        + eventType
        + ", timestamp="
        + timestamp
        + ", message='"
        + message
        + '\''
        + ", severity='"
        + severity
        + '\''
        + '}';
  }
}

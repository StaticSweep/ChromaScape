package com.chromascape.web.debug.tickdebugger.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for storing tick-correlated structured logs.
 *
 * <p>Each log entry is associated with a specific game tick and includes subsystem categorization,
 * severity levels, event types, and optional JSON details for structured data.
 */
@Entity
@Table(
    name = "tick_logs",
    indexes = {
      @Index(name = "idx_tick_logs_session_tick", columnList = "session_id, tick"),
      @Index(name = "idx_tick_logs_severity", columnList = "session_id, severity")
    })
public class TickLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The debug session this log belongs to. */
  @Column(name = "session_id", nullable = false, length = 100)
  private String sessionId;

  /** The game tick number this log is associated with. */
  @Column(nullable = false)
  private Long tick;

  /** When this log entry was created. */
  @Column(nullable = false)
  private LocalDateTime timestamp;

  /**
   * The subsystem that generated this log (e.g., "Combat", "Movement", "Prayer", "Perception",
   * "System").
   */
  @Column(nullable = false, length = 50)
  private String subsystem;

  /** Severity level (e.g., "Info", "Warn", "Error", "Debug", "Trace"). */
  @Column(nullable = false, length = 20)
  private String severity;

  /** Type of event (e.g., "ProjectileDetected", "PrayerSwitch", "DamageTaken", "Kill"). */
  @Column(length = 100)
  private String eventType;

  /** The log message. */
  @Column(nullable = false, columnDefinition = "TEXT")
  private String message;

  /** Additional structured details as JSON (optional). */
  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode details;

  /**
   * Optional correlation ID for linking related events across ticks (e.g., a projectile from
   * detection to impact).
   */
  @Column private UUID correlationId;

  /** Default constructor required by JPA. */
  public TickLog() {}

  /** Constructor for creating log entries. */
  public TickLog(
      String sessionId,
      Long tick,
      LocalDateTime timestamp,
      String subsystem,
      String severity,
      String message) {
    this.sessionId = sessionId;
    this.tick = tick;
    this.timestamp = timestamp;
    this.subsystem = subsystem;
    this.severity = severity;
    this.message = message;
  }

  // Getters and Setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Long getTick() {
    return tick;
  }

  public void setTick(Long tick) {
    this.tick = tick;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getSubsystem() {
    return subsystem;
  }

  public void setSubsystem(String subsystem) {
    this.subsystem = subsystem;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public JsonNode getDetails() {
    return details;
  }

  public void setDetails(JsonNode details) {
    this.details = details;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(UUID correlationId) {
    this.correlationId = correlationId;
  }

  /** Returns true if this log has ERROR severity. */
  public boolean isError() {
    return "Error".equalsIgnoreCase(severity);
  }

  /** Returns true if this log has WARN severity or higher. */
  public boolean isWarningOrHigher() {
    return "Error".equalsIgnoreCase(severity) || "Warn".equalsIgnoreCase(severity);
  }
}

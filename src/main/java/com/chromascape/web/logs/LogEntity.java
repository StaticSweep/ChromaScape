package com.chromascape.web.logs;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** JPA entity for storing log entries in the database. */
@Entity
@Table(
    name = "logs",
    indexes = {
      @Index(
          name = "idx_logs_debug_session_id_timestamp",
          columnList = "debug_session_id, timestamp"),
      @Index(name = "idx_logs_recording_id_timestamp", columnList = "recording_id, timestamp")
    })
public class LogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private LocalDateTime timestamp;

  @Column(nullable = false, length = 20)
  private String level;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String message;

  @Column(length = 100)
  private String source;

  @Column(length = 100)
  private String scriptName;

  // ActionLogger-specific fields for structured event data
  @Column(name = "log_type", length = 50)
  private String logType; // DIALOGUE, INVENTORY, VARBIT, ANIMATION, etc.

  @Column(name = "tick")
  private Integer tick; // Game tick number for action logs

  @Column(name = "structured_data", columnDefinition = "TEXT")
  private String structuredData; // JSON string of structured data

  @Column(name = "debug_session_id", length = 100)
  private String debugSessionId;

  @Column(name = "recording_id", length = 100)
  private String recordingId;

  /** Default constructor required by JPA. */
  public LogEntity() {}

  /** Constructor for creating log entries. */
  public LogEntity(
      LocalDateTime timestamp, String level, String message, String source, String scriptName) {
    this.timestamp = timestamp;
    this.level = level;
    this.message = message;
    this.source = source;
    this.scriptName = scriptName;
  }

  /** Constructor for creating log entries with ActionLogger data. */
  public LogEntity(
      LocalDateTime timestamp,
      String level,
      String message,
      String source,
      String scriptName,
      String logType,
      Integer tick,
      String structuredData) {
    this(timestamp, level, message, source, scriptName);
    this.logType = logType;
    this.tick = tick;
    this.structuredData = structuredData;
  }

  /** Constructor for creating log entries with ActionLogger and session correlation data. */
  public LogEntity(
      LocalDateTime timestamp,
      String level,
      String message,
      String source,
      String scriptName,
      String logType,
      Integer tick,
      String structuredData,
      String debugSessionId,
      String recordingId) {
    this(timestamp, level, message, source, scriptName, logType, tick, structuredData);
    this.debugSessionId = debugSessionId;
    this.recordingId = recordingId;
  }

  // Getters and Setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
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

  public String getStructuredData() {
    return structuredData;
  }

  public void setStructuredData(String structuredData) {
    this.structuredData = structuredData;
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
}

package com.chromascape.web.debug.tickdebugger.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * JPA entity for managing debug session lifecycle with tick-based tracking.
 *
 * <p>Each debug session represents a bot run with associated tick data, logs, state snapshots, and
 * optional video recording linkage.
 */
@Entity
@Table(name = "debug_sessions")
public class DebugSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Unique session identifier (e.g., "debug_1234567890"). */
  @Column(nullable = false, unique = true, length = 100)
  private String sessionId;

  /** Account name associated with this debug session. */
  @Column(length = 100)
  private String accountName;

  /** Script name being run during this debug session. */
  @Column(length = 100)
  private String scriptName;

  /** When the debug session started. */
  @Column(nullable = false)
  private LocalDateTime startTime;

  /** When the debug session ended (null if still active). */
  private LocalDateTime endTime;

  /** The game tick number when this session started. */
  @Column(nullable = false)
  private Long startTick;

  /** The game tick number when this session ended (null if still active). */
  private Long endTick;

  /** Optional link to a video recording session. */
  @Column(length = 100)
  private String recordingId;

  /** When this record was created. */
  @Column(nullable = false)
  private LocalDateTime createdAt;

  /** Default constructor required by JPA. */
  public DebugSession() {}

  /** Constructor for creating debug sessions. */
  public DebugSession(
      String sessionId,
      String accountName,
      String scriptName,
      LocalDateTime startTime,
      Long startTick) {
    this.sessionId = sessionId;
    this.accountName = accountName;
    this.scriptName = scriptName;
    this.startTime = startTime;
    this.startTick = startTick;
    this.createdAt = LocalDateTime.now();
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

  public String getAccountName() {
    return accountName;
  }

  public void setAccountName(String accountName) {
    this.accountName = accountName;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public LocalDateTime getStartTime() {
    return startTime;
  }

  public void setStartTime(LocalDateTime startTime) {
    this.startTime = startTime;
  }

  public LocalDateTime getEndTime() {
    return endTime;
  }

  public void setEndTime(LocalDateTime endTime) {
    this.endTime = endTime;
  }

  public Long getStartTick() {
    return startTick;
  }

  public void setStartTick(Long startTick) {
    this.startTick = startTick;
  }

  public Long getEndTick() {
    return endTick;
  }

  public void setEndTick(Long endTick) {
    this.endTick = endTick;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  /** Returns true if this session is still active (no end time set). */
  public boolean isActive() {
    return endTime == null;
  }
}

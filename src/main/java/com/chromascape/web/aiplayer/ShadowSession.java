package com.chromascape.web.aiplayer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Represents a single shadow-play session where the AI observes and records actions. */
@Entity
@Table(name = "shadow_sessions")
public class ShadowSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(unique = true, nullable = false)
  private String sessionId;

  @Column private String accountName;

  @Column private String scriptName;

  @Column private LocalDateTime startedAt;

  @Column private LocalDateTime endedAt;

  @Column private Long tickCount;

  @Column private Long actionCount;

  @Column private String status; // RUNNING, COMPLETED, FAILED

  @Column(columnDefinition = "TEXT")
  private String notes;

  public ShadowSession() {}

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

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(LocalDateTime startedAt) {
    this.startedAt = startedAt;
  }

  public LocalDateTime getEndedAt() {
    return endedAt;
  }

  public void setEndedAt(LocalDateTime endedAt) {
    this.endedAt = endedAt;
  }

  public Long getTickCount() {
    return tickCount;
  }

  public void setTickCount(Long tickCount) {
    this.tickCount = tickCount;
  }

  public Long getActionCount() {
    return actionCount;
  }

  public void setActionCount(Long actionCount) {
    this.actionCount = actionCount;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}

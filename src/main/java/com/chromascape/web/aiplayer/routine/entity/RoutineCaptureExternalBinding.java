package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Durable correlation binding used to prevent a second recorder call after an uncertain crash. */
@Entity
@Table(name = "routine_capture_external_bindings")
public class RoutineCaptureExternalBinding {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Version private Long version;

  @Column(name = "correlation_id", nullable = false, unique = true, length = 160)
  private String correlationId;

  @Column(name = "session_id", nullable = false, length = 160)
  private String sessionId;

  @Column(name = "debug_session_id", nullable = false, length = 160)
  private String debugSessionId;

  @Column(name = "recording_id", length = 160)
  private String recordingId;

  @Column(name = "start_attempted", nullable = false)
  private boolean startAttempted;

  @Column(name = "completion_attempted", nullable = false)
  private boolean completionAttempted;

  @Column(name = "completed", nullable = false)
  private boolean completed;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  public Long getId() {
    return id;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(String value) {
    correlationId = value;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String value) {
    sessionId = value;
  }

  public String getDebugSessionId() {
    return debugSessionId;
  }

  public void setDebugSessionId(String value) {
    debugSessionId = value;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String value) {
    recordingId = value;
    touch();
  }

  public boolean isStartAttempted() {
    return startAttempted;
  }

  public void setStartAttempted(boolean value) {
    startAttempted = value;
    touch();
  }

  public boolean isCompletionAttempted() {
    return completionAttempted;
  }

  public void setCompletionAttempted(boolean value) {
    completionAttempted = value;
    touch();
  }

  public boolean isCompleted() {
    return completed;
  }

  public void setCompleted(boolean value) {
    completed = value;
    touch();
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void touch() {
    updatedAt = Instant.now();
  }
}

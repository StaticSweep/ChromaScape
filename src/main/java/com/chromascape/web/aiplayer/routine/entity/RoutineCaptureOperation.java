package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable, immutable-request work item for a capture recorder operation. */
@Entity
@Table(
    name = "routine_capture_operations",
    indexes = {
      @Index(
          name = "idx_routine_capture_operations_claim",
          columnList = "state,next_attempt_at,lease_expires_at,created_at"),
      @Index(
          name = "idx_routine_capture_operations_session",
          columnList = "session_id,operation,state")
    })
public class RoutineCaptureOperation {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false, length = 160)
  private String sessionId;

  @Column(nullable = false, length = 20)
  private String operation;

  @Column(name = "request_hash", nullable = false, length = 71)
  private String requestHash;

  @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
  private String requestJson;

  @Column(name = "correlation_id", nullable = false, unique = true, length = 160)
  private String correlationId;

  @Column(nullable = false, length = 20)
  private String state = "PENDING";

  @Column(name = "attempt_id", length = 80)
  private String attemptId;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt = Instant.now();

  @Column(name = "result_json", columnDefinition = "TEXT")
  private String resultJson;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  public Long getId() {
    return id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String value) {
    sessionId = value;
  }

  public String getOperation() {
    return operation;
  }

  public void setOperation(String value) {
    operation = value;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public void setRequestHash(String value) {
    requestHash = value;
  }

  public String getRequestJson() {
    return requestJson;
  }

  public void setRequestJson(String value) {
    requestJson = value;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(String value) {
    correlationId = value;
  }

  public String getState() {
    return state;
  }

  public void setState(String value) {
    state = value;
    touch();
  }

  public String getAttemptId() {
    return attemptId;
  }

  public void setAttemptId(String value) {
    attemptId = value;
    touch();
  }

  public Instant getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  public void setLeaseExpiresAt(Instant value) {
    leaseExpiresAt = value;
    touch();
  }

  public int getAttempts() {
    return attempts;
  }

  public void setAttempts(int value) {
    attempts = value;
    touch();
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public void setNextAttemptAt(Instant value) {
    nextAttemptAt = value;
    touch();
  }

  public String getResultJson() {
    return resultJson;
  }

  public void setResultJson(String value) {
    resultJson = value;
    touch();
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String value) {
    lastError = value;
    touch();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void touch() {
    updatedAt = Instant.now();
  }
}

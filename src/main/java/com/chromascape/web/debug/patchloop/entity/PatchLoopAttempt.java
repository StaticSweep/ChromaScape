package com.chromascape.web.debug.patchloop.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Entity representing a single attempt within a graph execution.
 *
 * <p>Each attempt represents one run of the script loop, tracking the session/recording IDs,
 * timing, and outcome.
 */
@Entity
@Table(name = "patch_loop_attempt")
public class PatchLoopAttempt {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Reference to the parent graph execution */
  @Column(name = "execution_id", nullable = false, length = 100)
  private String executionId;

  /** Attempt number within the execution (1-indexed) */
  @Column(name = "attempt_number", nullable = false)
  private Integer attemptNumber;

  /** Script run session ID from this attempt */
  @Column(name = "run_session_id", length = 100)
  private String runSessionId;

  /** Recording ID from this attempt */
  @Column(name = "run_recording_id", length = 100)
  private String runRecordingId;

  /** When this attempt started */
  @Column(name = "started_at")
  private LocalDateTime startedAt;

  /** When this attempt completed */
  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  /** Reason this attempt stopped */
  @Column(name = "stop_reason", length = 200)
  private String stopReason;

  /** Whether the script run succeeded in this attempt */
  @Column(name = "run_success")
  private Boolean runSuccess;

  /** Number of ticks logged during this attempt */
  @Column(name = "tick_count")
  private Integer tickCount;

  /** Number of log entries during this attempt */
  @Column(name = "log_count")
  private Integer logCount;

  /** Outcome of this attempt */
  @Enumerated(EnumType.STRING)
  @Column(length = 50)
  private AttemptOutcome outcome;

  public PatchLoopAttempt() {
    this.startedAt = LocalDateTime.now();
  }

  /** Marks this attempt as completed with the given outcome. */
  public void markCompleted(AttemptOutcome outcome, String reason) {
    this.completedAt = LocalDateTime.now();
    this.outcome = outcome;
    this.stopReason = reason;
  }

  /** Returns true if this attempt is still running (not completed). */
  public boolean isRunning() {
    return startedAt != null && completedAt == null;
  }

  /** Returns the duration of this attempt in milliseconds, or null if still running. */
  public Long getDurationMs() {
    if (startedAt == null || completedAt == null) {
      return null;
    }
    return java.time.Duration.between(startedAt, completedAt).toMillis();
  }

  // Getters and setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getExecutionId() {
    return executionId;
  }

  public void setExecutionId(String executionId) {
    this.executionId = executionId;
  }

  public Integer getAttemptNumber() {
    return attemptNumber;
  }

  public void setAttemptNumber(Integer attemptNumber) {
    this.attemptNumber = attemptNumber;
  }

  public String getRunSessionId() {
    return runSessionId;
  }

  public void setRunSessionId(String runSessionId) {
    this.runSessionId = runSessionId;
  }

  public String getRunRecordingId() {
    return runRecordingId;
  }

  public void setRunRecordingId(String runRecordingId) {
    this.runRecordingId = runRecordingId;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(LocalDateTime startedAt) {
    this.startedAt = startedAt;
  }

  public LocalDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(LocalDateTime completedAt) {
    this.completedAt = completedAt;
  }

  public String getStopReason() {
    return stopReason;
  }

  public void setStopReason(String stopReason) {
    this.stopReason = stopReason;
  }

  public Boolean getRunSuccess() {
    return runSuccess;
  }

  public void setRunSuccess(Boolean runSuccess) {
    this.runSuccess = runSuccess;
  }

  public Integer getTickCount() {
    return tickCount;
  }

  public void setTickCount(Integer tickCount) {
    this.tickCount = tickCount;
  }

  public Integer getLogCount() {
    return logCount;
  }

  public void setLogCount(Integer logCount) {
    this.logCount = logCount;
  }

  public AttemptOutcome getOutcome() {
    return outcome;
  }

  public void setOutcome(AttemptOutcome outcome) {
    this.outcome = outcome;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchLoopAttempt that = (PatchLoopAttempt) o;
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return "PatchLoopAttempt{"
        + "id="
        + id
        + ", executionId='"
        + executionId
        + '\''
        + ", attemptNumber="
        + attemptNumber
        + ", outcome="
        + outcome
        + ", startedAt="
        + startedAt
        + ", completedAt="
        + completedAt
        + '}';
  }
}

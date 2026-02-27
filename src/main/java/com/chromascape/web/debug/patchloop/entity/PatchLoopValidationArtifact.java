package com.chromascape.web.debug.patchloop.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Entity representing a validation artifact from patch loop execution.
 *
 * <p>Tracks validation results including output, exit codes, and duration.
 */
@Entity
@Table(name = "patch_loop_validation_artifact")
public class PatchLoopValidationArtifact {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Reference to the parent graph execution */
  @Column(name = "execution_id", nullable = false, length = 100)
  private String executionId;

  /** Attempt number this validation is for */
  @Column(name = "attempt_number")
  private Integer attemptNumber;

  /** Associated patch ID */
  @Column(name = "patch_id", length = 100)
  private String patchId;

  /** Whether validation passed */
  @Column(name = "passed")
  private Boolean passed;

  /** Validation output text */
  @Column(columnDefinition = "TEXT")
  private String output;

  /** Exit code from validation */
  @Column(name = "exit_code")
  private Integer exitCode;

  /** Duration in milliseconds */
  @Column(name = "duration_ms")
  private Long durationMs;

  /** When this validation was created */
  @Column(name = "timestamp", nullable = false)
  private LocalDateTime timestamp;

  /** Whether this patch was rolled back after validation failure */
  @Column(name = "rolled_back")
  private Boolean rolledBack;

  /** Reason for rollback */
  @Column(name = "rollback_reason", length = 500)
  private String rollbackReason;

  public PatchLoopValidationArtifact() {
    this.timestamp = LocalDateTime.now();
  }

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

  public String getPatchId() {
    return patchId;
  }

  public void setPatchId(String patchId) {
    this.patchId = patchId;
  }

  public Boolean getPassed() {
    return passed;
  }

  public void setPassed(Boolean passed) {
    this.passed = passed;
  }

  public String getOutput() {
    return output;
  }

  public void setOutput(String output) {
    this.output = output;
  }

  public Integer getExitCode() {
    return exitCode;
  }

  public void setExitCode(Integer exitCode) {
    this.exitCode = exitCode;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public Boolean getRolledBack() {
    return rolledBack;
  }

  public void setRolledBack(Boolean rolledBack) {
    this.rolledBack = rolledBack;
  }

  public String getRollbackReason() {
    return rollbackReason;
  }

  public void setRollbackReason(String rollbackReason) {
    this.rollbackReason = rollbackReason;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchLoopValidationArtifact that = (PatchLoopValidationArtifact) o;
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return "PatchLoopValidationArtifact{"
        + "id="
        + id
        + ", executionId='"
        + executionId
        + '\''
        + ", attemptNumber="
        + attemptNumber
        + ", patchId='"
        + patchId
        + '\''
        + ", passed="
        + passed
        + ", exitCode="
        + exitCode
        + ", timestamp="
        + timestamp
        + '}';
  }
}

package com.chromascape.web.debug.patchloop.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Entity representing a retest artifact from patch loop execution.
 *
 * <p>Tracks retest results including comparisons, improvements, and regressions.
 */
@Entity
@Table(name = "patch_loop_retest_artifact")
public class PatchLoopRetestArtifact {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Reference to the parent graph execution */
  @Column(name = "execution_id", nullable = false, length = 100)
  private String executionId;

  /** Attempt number this retest is for */
  @Column(name = "attempt_number")
  private Integer attemptNumber;

  /** Baseline attempt number for comparison */
  @Column(name = "baseline_attempt_number")
  private Integer baselineAttemptNumber;

  /** Whether the retest was successful */
  @Column(name = "success")
  private Boolean success;

  /** Summary of comparison results */
  @Column(columnDefinition = "TEXT")
  private String comparisonSummary;

  /** List of improvements detected */
  @Column(columnDefinition = "TEXT")
  private String improvements;

  /** List of regressions detected */
  @Column(columnDefinition = "TEXT")
  private String regressions;

  /** When this retest was created */
  @Column(name = "timestamp", nullable = false)
  private LocalDateTime timestamp;

  public PatchLoopRetestArtifact() {
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

  public Integer getBaselineAttemptNumber() {
    return baselineAttemptNumber;
  }

  public void setBaselineAttemptNumber(Integer baselineAttemptNumber) {
    this.baselineAttemptNumber = baselineAttemptNumber;
  }

  public Boolean getSuccess() {
    return success;
  }

  public void setSuccess(Boolean success) {
    this.success = success;
  }

  public String getComparisonSummary() {
    return comparisonSummary;
  }

  public void setComparisonSummary(String comparisonSummary) {
    this.comparisonSummary = comparisonSummary;
  }

  public String getImprovements() {
    return improvements;
  }

  public void setImprovements(String improvements) {
    this.improvements = improvements;
  }

  public String getRegressions() {
    return regressions;
  }

  public void setRegressions(String regressions) {
    this.regressions = regressions;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchLoopRetestArtifact that = (PatchLoopRetestArtifact) o;
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return "PatchLoopRetestArtifact{"
        + "id="
        + id
        + ", executionId='"
        + executionId
        + '\''
        + ", attemptNumber="
        + attemptNumber
        + ", success="
        + success
        + ", timestamp="
        + timestamp
        + '}';
  }
}

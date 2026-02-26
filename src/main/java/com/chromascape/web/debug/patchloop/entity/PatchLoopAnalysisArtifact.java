package com.chromascape.web.debug.patchloop.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Entity representing an analysis artifact from patch loop execution.
 *
 * <p>Tracks AI analysis results including diagnosis, root cause, and recommendations.
 */
@Entity
@Table(name = "patch_loop_analysis_artifact")
public class PatchLoopAnalysisArtifact {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Reference to the parent graph execution */
  @Column(name = "execution_id", nullable = false, length = 100)
  private String executionId;

  /** Attempt number this analysis is for */
  @Column(name = "attempt_number")
  private Integer attemptNumber;

  /** Unique analysis ID */
  @Column(name = "analysis_id", unique = true, length = 100)
  private String analysisId;

  /** Summary of the diagnosis */
  @Column(columnDefinition = "TEXT")
  private String diagnosisSummary;

  /** Root cause identified */
  @Column(columnDefinition = "TEXT")
  private String rootCause;

  /** Confidence level of the analysis */
  @Column(length = 50)
  private String confidence;

  /** Number of recommendations provided */
  @Column(name = "recommendation_count")
  private Integer recommendationCount;

  /** When this analysis was created */
  @Column(name = "timestamp", nullable = false)
  private LocalDateTime timestamp;

  public PatchLoopAnalysisArtifact() {
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

  public String getAnalysisId() {
    return analysisId;
  }

  public void setAnalysisId(String analysisId) {
    this.analysisId = analysisId;
  }

  public String getDiagnosisSummary() {
    return diagnosisSummary;
  }

  public void setDiagnosisSummary(String diagnosisSummary) {
    this.diagnosisSummary = diagnosisSummary;
  }

  public String getRootCause() {
    return rootCause;
  }

  public void setRootCause(String rootCause) {
    this.rootCause = rootCause;
  }

  public String getConfidence() {
    return confidence;
  }

  public void setConfidence(String confidence) {
    this.confidence = confidence;
  }

  public Integer getRecommendationCount() {
    return recommendationCount;
  }

  public void setRecommendationCount(Integer recommendationCount) {
    this.recommendationCount = recommendationCount;
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
    PatchLoopAnalysisArtifact that = (PatchLoopAnalysisArtifact) o;
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return "PatchLoopAnalysisArtifact{"
        + "id="
        + id
        + ", executionId='"
        + executionId
        + '\''
        + ", attemptNumber="
        + attemptNumber
        + ", analysisId='"
        + analysisId
        + '\''
        + ", confidence='"
        + confidence
        + '\''
        + ", timestamp="
        + timestamp
        + '}';
  }
}

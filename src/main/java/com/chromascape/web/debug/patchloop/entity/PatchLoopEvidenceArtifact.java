package com.chromascape.web.debug.patchloop.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Entity representing an evidence artifact from patch loop execution.
 *
 * <p>Tracks evidence collected during execution including type, count, and summary.
 */
@Entity
@Table(name = "patch_loop_evidence_artifact")
public class PatchLoopEvidenceArtifact {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Reference to the parent graph execution */
  @Column(name = "execution_id", nullable = false, length = 100)
  private String executionId;

  /** Attempt number this evidence is for */
  @Column(name = "attempt_number")
  private Integer attemptNumber;

  /** Type of evidence collected */
  @Column(name = "evidence_type", length = 100)
  private String evidenceType;

  /** Count of evidence items */
  @Column(name = "count")
  private Integer count;

  /** Summary of evidence */
  @Column(columnDefinition = "TEXT")
  private String summary;

  /** When this evidence was created */
  @Column(name = "timestamp", nullable = false)
  private LocalDateTime timestamp;

  public PatchLoopEvidenceArtifact() {
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

  public String getEvidenceType() {
    return evidenceType;
  }

  public void setEvidenceType(String evidenceType) {
    this.evidenceType = evidenceType;
  }

  public Integer getCount() {
    return count;
  }

  public void setCount(Integer count) {
    this.count = count;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
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
    PatchLoopEvidenceArtifact that = (PatchLoopEvidenceArtifact) o;
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return "PatchLoopEvidenceArtifact{"
        + "id="
        + id
        + ", executionId='"
        + executionId
        + '\''
        + ", attemptNumber="
        + attemptNumber
        + ", evidenceType='"
        + evidenceType
        + '\''
        + ", count="
        + count
        + ", timestamp="
        + timestamp
        + '}';
  }
}

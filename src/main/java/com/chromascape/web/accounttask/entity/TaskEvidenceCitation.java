package com.chromascape.web.accounttask.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * An evidence citation linking a task plan item to supporting telemetry data.
 *
 * <p>Citations reference specific logs, decisions, tick data, or recordings that were used during
 * task synthesis to justify the inclusion of a task item in the plan.
 */
@Entity
@Table(name = "task_evidence_citation")
public class TaskEvidenceCitation {

  /** The type of evidence being cited. */
  public enum EvidenceType {
    LOG,
    TICK_LOG,
    DECISION,
    RECORDING_SESSION,
    SNAPSHOT
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The task plan item this citation supports. */
  @Column(nullable = false, length = 100)
  private String itemId;

  /** The plan this citation belongs to (denormalized for efficient queries). */
  @Column(nullable = false, length = 100)
  private String planId;

  /** Type of evidence. */
  @Column(nullable = false, length = 50)
  private EvidenceType evidenceType;

  /** ID of the evidence record in the source table. */
  @Column(nullable = false)
  private Long evidenceId;

  /** Human-readable snippet of the evidence (first 500 chars). */
  @Column(length = 500)
  private String snippet;

  /** Timestamp of the original evidence. */
  @Column private LocalDateTime evidenceTimestamp;

  /** Relevance score (0.0-1.0) indicating how relevant this evidence is. */
  @Column private Double relevanceScore;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  public TaskEvidenceCitation() {
    this.createdAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getItemId() {
    return itemId;
  }

  public void setItemId(String itemId) {
    this.itemId = itemId;
  }

  public String getPlanId() {
    return planId;
  }

  public void setPlanId(String planId) {
    this.planId = planId;
  }

  public EvidenceType getEvidenceType() {
    return evidenceType;
  }

  public void setEvidenceType(EvidenceType evidenceType) {
    this.evidenceType = evidenceType;
  }

  public Long getEvidenceId() {
    return evidenceId;
  }

  public void setEvidenceId(Long evidenceId) {
    this.evidenceId = evidenceId;
  }

  public String getSnippet() {
    return snippet;
  }

  public void setSnippet(String snippet) {
    this.snippet = snippet;
  }

  public LocalDateTime getEvidenceTimestamp() {
    return evidenceTimestamp;
  }

  public void setEvidenceTimestamp(LocalDateTime evidenceTimestamp) {
    this.evidenceTimestamp = evidenceTimestamp;
  }

  public Double getRelevanceScore() {
    return relevanceScore;
  }

  public void setRelevanceScore(Double relevanceScore) {
    this.relevanceScore = relevanceScore;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}

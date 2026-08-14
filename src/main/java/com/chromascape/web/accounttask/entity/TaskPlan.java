package com.chromascape.web.accounttask.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A synthesized task plan for account building.
 *
 * <p>Contains an intent, evidence references, and a collection of plan items that represent
 * individual tasks (e.g., "complete quest X", "achieve level Y in skill Z").
 */
@Entity
@Table(name = "task_plan")
public class TaskPlan {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Unique plan identifier (e.g., "plan_abc123"). */
  @Column(unique = true, nullable = false, length = 100)
  private String planId;

  /** The user-supplied intent (e.g., "Get quest cape"). */
  @Column(nullable = false, columnDefinition = "TEXT")
  private String intent;

  /** Current plan status. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private TaskPlanStatus status;

  /** Optional session context used for synthesis (debugger session ID). */
  @Column(length = 100)
  private String sourceSessionId;

  /** Optional recording context used for synthesis. */
  @Column(length = 100)
  private String sourceRecordingId;

  /** Synthesis metadata (model used, timestamp ranges, etc.). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode synthesisMetadata;

  /** Account name this plan is for. */
  @Column(length = 200)
  private String accountName;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column private LocalDateTime updatedAt;

  @Column private LocalDateTime approvedAt;

  @Column private LocalDateTime completedAt;

  public TaskPlan() {
    this.createdAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
    this.status = TaskPlanStatus.GENERATED;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getPlanId() {
    return planId;
  }

  public void setPlanId(String planId) {
    this.planId = planId;
  }

  public String getIntent() {
    return intent;
  }

  public void setIntent(String intent) {
    this.intent = intent;
  }

  public TaskPlanStatus getStatus() {
    return status;
  }

  public void setStatus(TaskPlanStatus status) {
    this.status = status;
    this.updatedAt = LocalDateTime.now();
  }

  public String getSourceSessionId() {
    return sourceSessionId;
  }

  public void setSourceSessionId(String sourceSessionId) {
    this.sourceSessionId = sourceSessionId;
  }

  public String getSourceRecordingId() {
    return sourceRecordingId;
  }

  public void setSourceRecordingId(String sourceRecordingId) {
    this.sourceRecordingId = sourceRecordingId;
  }

  public JsonNode getSynthesisMetadata() {
    return synthesisMetadata;
  }

  public void setSynthesisMetadata(JsonNode synthesisMetadata) {
    this.synthesisMetadata = synthesisMetadata;
  }

  public String getAccountName() {
    return accountName;
  }

  public void setAccountName(String accountName) {
    this.accountName = accountName;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public LocalDateTime getApprovedAt() {
    return approvedAt;
  }

  public void setApprovedAt(LocalDateTime approvedAt) {
    this.approvedAt = approvedAt;
  }

  public LocalDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(LocalDateTime completedAt) {
    this.completedAt = completedAt;
  }
}

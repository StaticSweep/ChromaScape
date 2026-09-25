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
 * A single task item within a {@link TaskPlan}.
 *
 * <p>Each item represents a discrete, actionable step such as "complete Recipe for Disaster" or
 * "get 70 Agility". Items can be individually approved, rejected, and executed via patch loop.
 */
@Entity
@Table(name = "task_plan_item")
public class TaskPlanItem {

  /** Approval status for individual items. */
  public enum ItemStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXECUTING,
    COMPLETED,
    FAILED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Unique item identifier (e.g., "item_abc123"). */
  @Column(unique = true, nullable = false, length = 100)
  private String itemId;

  /** Reference to parent plan. */
  @Column(nullable = false, length = 100)
  private String planId;

  /** Display order within the plan. */
  @Column(nullable = false)
  private int sortOrder;

  /** Short title of this task item. */
  @Column(nullable = false, length = 500)
  private String title;

  /** Detailed description of what this task entails. */
  @Column(columnDefinition = "TEXT")
  private String description;

  /** The script name recommended for execution. */
  @Column(length = 200)
  private String scriptName;

  /** Estimated difficulty (EASY, MEDIUM, HARD, ELITE). */
  @Column(length = 20)
  private String difficulty;

  /** Estimated duration in minutes. */
  @Column private Integer estimatedMinutes;

  /** Requirements as JSON (items, levels, quests). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode requirements;

  /** Current item status. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private ItemStatus status;

  /** Linked patch loop iteration ID, if execution was started. */
  @Column(length = 100)
  private String patchLoopIterationId;

  /** Result summary after execution. */
  @Column(columnDefinition = "TEXT")
  private String executionResult;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column private LocalDateTime approvedAt;

  @Column private LocalDateTime executedAt;

  @Column private LocalDateTime completedAt;

  public TaskPlanItem() {
    this.createdAt = LocalDateTime.now();
    this.status = ItemStatus.PENDING;
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

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public String getDifficulty() {
    return difficulty;
  }

  public void setDifficulty(String difficulty) {
    this.difficulty = difficulty;
  }

  public Integer getEstimatedMinutes() {
    return estimatedMinutes;
  }

  public void setEstimatedMinutes(Integer estimatedMinutes) {
    this.estimatedMinutes = estimatedMinutes;
  }

  public JsonNode getRequirements() {
    return requirements;
  }

  public void setRequirements(JsonNode requirements) {
    this.requirements = requirements;
  }

  public ItemStatus getStatus() {
    return status;
  }

  public void setStatus(ItemStatus status) {
    this.status = status;
  }

  public String getPatchLoopIterationId() {
    return patchLoopIterationId;
  }

  public void setPatchLoopIterationId(String patchLoopIterationId) {
    this.patchLoopIterationId = patchLoopIterationId;
  }

  public String getExecutionResult() {
    return executionResult;
  }

  public void setExecutionResult(String executionResult) {
    this.executionResult = executionResult;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getApprovedAt() {
    return approvedAt;
  }

  public void setApprovedAt(LocalDateTime approvedAt) {
    this.approvedAt = approvedAt;
  }

  public LocalDateTime getExecutedAt() {
    return executedAt;
  }

  public void setExecutedAt(LocalDateTime executedAt) {
    this.executedAt = executedAt;
  }

  public LocalDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(LocalDateTime completedAt) {
    this.completedAt = completedAt;
  }
}

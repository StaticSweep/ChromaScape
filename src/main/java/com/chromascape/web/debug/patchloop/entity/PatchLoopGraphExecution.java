package com.chromascape.web.debug.patchloop.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Entity representing the execution of a Mermaid-defined patch loop graph.
 *
 * <p>Tracks the metadata and current execution state of a graph-based workflow, including the
 * compiled graph structure, current node pointer, and execution status.
 */
@Entity
@Table(name = "patch_loop_graph_execution")
public class PatchLoopGraphExecution {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Unique execution ID (e.g., "graph_exec_xyz123") */
  @Column(unique = true, nullable = false, length = 100)
  private String executionId;

  /** Reference to the parent iteration */
  @Column(name = "iteration_id", length = 100)
  private String iterationId;

  /** Original Mermaid source code */
  @Column(columnDefinition = "TEXT")
  private String mermaidSource;

  /** Compiled graph structure (JSON) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode graphJson;

  /** ID of the currently executing node */
  @Column(name = "current_node_id", length = 100)
  private String currentNodeId;

  /** Current execution status */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private GraphExecutionStatus executionStatus;

  /** Number of attempts made so far */
  @Column(name = "attempt_count")
  private Integer attemptCount;

  /** Timestamp when execution was created */
  @Column(nullable = false)
  private LocalDateTime createdAt;

  /** Timestamp of last state change */
  @Column(nullable = false)
  private LocalDateTime updatedAt;

  /** Timestamp when execution completed (terminal state) */
  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  /** Reason for stopping (when terminal) */
  @Column(name = "stop_reason", length = 200)
  private String stopReason;

  /** Final status (SUCCESS, FAILED, CANCELLED, etc.) */
  @Column(name = "final_status", length = 50)
  private String finalStatus;

  /** Execution policy configuration (JSON) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode policyJson;

  public PatchLoopGraphExecution() {
    this.createdAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
    this.executionStatus = GraphExecutionStatus.CREATED;
    this.attemptCount = 0;
  }

  /** Updates the current node and timestamp. */
  public void setCurrentNode(String nodeId) {
    this.currentNodeId = nodeId;
    this.updatedAt = LocalDateTime.now();
  }

  /** Transitions to a new status and updates timestamp. */
  public void transitionTo(GraphExecutionStatus newStatus) {
    this.executionStatus = newStatus;
    this.updatedAt = LocalDateTime.now();

    if (newStatus.isTerminal()) {
      this.completedAt = LocalDateTime.now();
    }
  }

  /** Increments the attempt counter. */
  public void incrementAttempt() {
    this.attemptCount = (this.attemptCount == null) ? 1 : this.attemptCount + 1;
    this.updatedAt = LocalDateTime.now();
  }

  /** Returns true if this execution is in a terminal state. */
  public boolean isTerminal() {
    return executionStatus != null && executionStatus.isTerminal();
  }

  /** Returns true if this execution is active (not terminal). */
  public boolean isActive() {
    return executionStatus != null && executionStatus.isActive();
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

  public String getIterationId() {
    return iterationId;
  }

  public void setIterationId(String iterationId) {
    this.iterationId = iterationId;
  }

  public String getMermaidSource() {
    return mermaidSource;
  }

  public void setMermaidSource(String mermaidSource) {
    this.mermaidSource = mermaidSource;
  }

  public JsonNode getGraphJson() {
    return graphJson;
  }

  public void setGraphJson(JsonNode graphJson) {
    this.graphJson = graphJson;
  }

  public String getCurrentNodeId() {
    return currentNodeId;
  }

  public void setCurrentNodeId(String currentNodeId) {
    this.currentNodeId = currentNodeId;
  }

  public GraphExecutionStatus getExecutionStatus() {
    return executionStatus;
  }

  public void setExecutionStatus(GraphExecutionStatus executionStatus) {
    this.executionStatus = executionStatus;
  }

  public Integer getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(Integer attemptCount) {
    this.attemptCount = attemptCount;
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

  public String getFinalStatus() {
    return finalStatus;
  }

  public void setFinalStatus(String finalStatus) {
    this.finalStatus = finalStatus;
  }

  public JsonNode getPolicyJson() {
    return policyJson;
  }

  public void setPolicyJson(JsonNode policyJson) {
    this.policyJson = policyJson;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchLoopGraphExecution that = (PatchLoopGraphExecution) o;
    return Objects.equals(executionId, that.executionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(executionId);
  }

  @Override
  public String toString() {
    return "PatchLoopGraphExecution{"
        + "id="
        + id
        + ", executionId='"
        + executionId
        + '\''
        + ", iterationId='"
        + iterationId
        + '\''
        + ", executionStatus="
        + executionStatus
        + ", currentNodeId='"
        + currentNodeId
        + '\''
        + ", attemptCount="
        + attemptCount
        + ", createdAt="
        + createdAt
        + '}';
  }
}

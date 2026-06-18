package com.chromascape.web.debug.patchloop.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Entity representing the runtime state of a single graph node execution.
 *
 * <p>Tracks the status, retry count, timing, and output of each node as it executes within a graph
 * workflow.
 */
@Entity
@Table(name = "patch_loop_graph_node")
public class PatchLoopGraphNode {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Reference to the parent graph execution */
  @Column(name = "execution_id", nullable = false, length = 100)
  private String executionId;

  /** Node ID from the graph definition */
  @Column(name = "node_id", nullable = false, length = 100)
  private String nodeId;

  /** Node type (RUN_SCRIPT, COLLECT_EVIDENCE, etc.) */
  @Enumerated(EnumType.STRING)
  @Column(name = "node_type", nullable = false, length = 50)
  private NodeType nodeType;

  /** Current execution status of this node */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private NodeExecutionStatus status;

  /** Number of times this node has been retried */
  @Column(name = "retry_count")
  private Integer retryCount;

  /** When this node started executing */
  @Column(name = "started_at")
  private LocalDateTime startedAt;

  /** When this node completed execution */
  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  /** Error message if node failed */
  @Column(columnDefinition = "TEXT")
  private String errorMessage;

  /** Brief summary of node output */
  @Column(name = "output_summary", columnDefinition = "TEXT")
  private String outputSummary;

  /** Full output data (JSON) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode outputData;

  /** Attempt number when this node executed */
  @Column(name = "attempt_number")
  private Integer attemptNumber;

  /** Execution duration in milliseconds */
  @Column(name = "duration_ms")
  private Long durationMs;

  public PatchLoopGraphNode() {
    this.status = NodeExecutionStatus.PENDING;
    this.retryCount = 0;
  }

  /** Marks this node as started. */
  public void markStarted() {
    this.status = NodeExecutionStatus.RUNNING;
    this.startedAt = LocalDateTime.now();
  }

  /** Marks this node as completed successfully. */
  public void markCompleted(String outputSummary, JsonNode outputData) {
    this.status = NodeExecutionStatus.COMPLETED;
    this.completedAt = LocalDateTime.now();
    this.outputSummary = outputSummary;
    this.outputData = outputData;
    if (this.startedAt != null) {
      this.durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();
    }
  }

  /** Marks this node as failed. */
  public void markFailed(String errorMessage) {
    this.status = NodeExecutionStatus.FAILED;
    this.completedAt = LocalDateTime.now();
    this.errorMessage = errorMessage;
    if (this.startedAt != null) {
      this.durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();
    }
  }

  /** Marks this node as timed out. */
  public void markTimeout(String errorMessage, JsonNode outputData) {
    this.status = NodeExecutionStatus.TIMEOUT;
    this.completedAt = LocalDateTime.now();
    this.errorMessage = errorMessage;
    this.outputSummary = errorMessage;
    this.outputData = outputData;
    if (this.startedAt != null) {
      this.durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();
    }
  }

  /** Increments the retry counter. */
  public void incrementRetry() {
    this.retryCount = (this.retryCount == null) ? 1 : this.retryCount + 1;
    this.status = NodeExecutionStatus.PENDING;
  }

  /** Returns true if this node is currently running. */
  public boolean isRunning() {
    return status == NodeExecutionStatus.RUNNING;
  }

  /** Returns true if this node completed successfully. */
  public boolean isCompleted() {
    return status == NodeExecutionStatus.COMPLETED;
  }

  /** Returns true if this node failed. */
  public boolean isFailed() {
    return status == NodeExecutionStatus.FAILED;
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

  public String getNodeId() {
    return nodeId;
  }

  public void setNodeId(String nodeId) {
    this.nodeId = nodeId;
  }

  public NodeType getNodeType() {
    return nodeType;
  }

  public void setNodeType(NodeType nodeType) {
    this.nodeType = nodeType;
  }

  public NodeExecutionStatus getStatus() {
    return status;
  }

  public void setStatus(NodeExecutionStatus status) {
    this.status = status;
  }

  public Integer getRetryCount() {
    return retryCount;
  }

  public void setRetryCount(Integer retryCount) {
    this.retryCount = retryCount;
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

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public String getOutputSummary() {
    return outputSummary;
  }

  public void setOutputSummary(String outputSummary) {
    this.outputSummary = outputSummary;
  }

  public JsonNode getOutputData() {
    return outputData;
  }

  public void setOutputData(JsonNode outputData) {
    this.outputData = outputData;
  }

  public Integer getAttemptNumber() {
    return attemptNumber;
  }

  public void setAttemptNumber(Integer attemptNumber) {
    this.attemptNumber = attemptNumber;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchLoopGraphNode that = (PatchLoopGraphNode) o;
    return Objects.equals(executionId, that.executionId)
        && Objects.equals(nodeId, that.nodeId)
        && Objects.equals(attemptNumber, that.attemptNumber);
  }

  @Override
  public int hashCode() {
    return Objects.hash(executionId, nodeId, attemptNumber);
  }

  @Override
  public String toString() {
    return "PatchLoopGraphNode{"
        + "id="
        + id
        + ", executionId='"
        + executionId
        + '\''
        + ", nodeId='"
        + nodeId
        + '\''
        + ", nodeType="
        + nodeType
        + ", status="
        + status
        + ", retryCount="
        + retryCount
        + ", durationMs="
        + durationMs
        + '}';
  }
}

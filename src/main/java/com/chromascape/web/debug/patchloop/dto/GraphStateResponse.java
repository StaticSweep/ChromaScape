package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Response containing the current state of a graph execution.
 *
 * <p>Includes execution metadata, node states, artifacts, and control options.
 */
public class GraphStateResponse {

  /** Execution ID */
  @JsonProperty("executionId")
  private String executionId;

  /** Iteration ID */
  @JsonProperty("iterationId")
  private String iterationId;

  /** Current execution status */
  @JsonProperty("status")
  private String status;

  /** Current node ID */
  @JsonProperty("currentNodeId")
  private String currentNodeId;

  /** Attempt count */
  @JsonProperty("attemptCount")
  private Integer attemptCount;

  /** Original Mermaid source */
  @JsonProperty("mermaidSource")
  private String mermaidSource;

  /** Compiled graph structure */
  @JsonProperty("graph")
  private CompiledGraphInfo graph;

  /** States of all nodes */
  @JsonProperty("nodes")
  private List<NodeStateInfo> nodes;

  /** All attempts */
  @JsonProperty("attempts")
  private List<AttemptInfo> attempts;

  /** All patches generated */
  @JsonProperty("patches")
  private List<PatchArtifactInfo> patches;

  /** Execution timeline */
  @JsonProperty("timeline")
  private ExecutionTimeline timeline;

  /** Control actions available */
  @JsonProperty("availableActions")
  private List<String> availableActions;

  /** Stop reason if terminal */
  @JsonProperty("stopReason")
  private String stopReason;

  /** Final status if terminal */
  @JsonProperty("finalStatus")
  private String finalStatus;

  public GraphStateResponse() {}

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

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getCurrentNodeId() {
    return currentNodeId;
  }

  public void setCurrentNodeId(String currentNodeId) {
    this.currentNodeId = currentNodeId;
  }

  public Integer getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(Integer attemptCount) {
    this.attemptCount = attemptCount;
  }

  public String getMermaidSource() {
    return mermaidSource;
  }

  public void setMermaidSource(String mermaidSource) {
    this.mermaidSource = mermaidSource;
  }

  public CompiledGraphInfo getGraph() {
    return graph;
  }

  public void setGraph(CompiledGraphInfo graph) {
    this.graph = graph;
  }

  public List<NodeStateInfo> getNodes() {
    return nodes;
  }

  public void setNodes(List<NodeStateInfo> nodes) {
    this.nodes = nodes;
  }

  public List<AttemptInfo> getAttempts() {
    return attempts;
  }

  public void setAttempts(List<AttemptInfo> attempts) {
    this.attempts = attempts;
  }

  public List<PatchArtifactInfo> getPatches() {
    return patches;
  }

  public void setPatches(List<PatchArtifactInfo> patches) {
    this.patches = patches;
  }

  public ExecutionTimeline getTimeline() {
    return timeline;
  }

  public void setTimeline(ExecutionTimeline timeline) {
    this.timeline = timeline;
  }

  public List<String> getAvailableActions() {
    return availableActions;
  }

  public void setAvailableActions(List<String> availableActions) {
    this.availableActions = availableActions;
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

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GraphStateResponse that = (GraphStateResponse) o;
    return Objects.equals(executionId, that.executionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(executionId);
  }

  /** State information for a single node. */
  public static class NodeStateInfo {

    @JsonProperty("nodeId")
    private String nodeId;

    @JsonProperty("nodeType")
    private String nodeType;

    @JsonProperty("status")
    private String status;

    @JsonProperty("retryCount")
    private Integer retryCount;

    @JsonProperty("startedAt")
    private LocalDateTime startedAt;

    @JsonProperty("completedAt")
    private LocalDateTime completedAt;

    @JsonProperty("durationMs")
    private Long durationMs;

    @JsonProperty("errorMessage")
    private String errorMessage;

    @JsonProperty("outputSummary")
    private String outputSummary;

    @JsonProperty("attemptNumber")
    private Integer attemptNumber;

    public String getNodeId() {
      return nodeId;
    }

    public void setNodeId(String nodeId) {
      this.nodeId = nodeId;
    }

    public String getNodeType() {
      return nodeType;
    }

    public void setNodeType(String nodeType) {
      this.nodeType = nodeType;
    }

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
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

    public Long getDurationMs() {
      return durationMs;
    }

    public void setDurationMs(Long durationMs) {
      this.durationMs = durationMs;
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

    public Integer getAttemptNumber() {
      return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
      this.attemptNumber = attemptNumber;
    }
  }

  /** Information about a single attempt. */
  public static class AttemptInfo {

    @JsonProperty("attemptNumber")
    private Integer attemptNumber;

    @JsonProperty("runSessionId")
    private String runSessionId;

    @JsonProperty("runRecordingId")
    private String runRecordingId;

    @JsonProperty("startedAt")
    private LocalDateTime startedAt;

    @JsonProperty("completedAt")
    private LocalDateTime completedAt;

    @JsonProperty("outcome")
    private String outcome;

    @JsonProperty("stopReason")
    private String stopReason;

    @JsonProperty("durationMs")
    private Long durationMs;

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

    public String getOutcome() {
      return outcome;
    }

    public void setOutcome(String outcome) {
      this.outcome = outcome;
    }

    public String getStopReason() {
      return stopReason;
    }

    public void setStopReason(String stopReason) {
      this.stopReason = stopReason;
    }

    public Long getDurationMs() {
      return durationMs;
    }

    public void setDurationMs(Long durationMs) {
      this.durationMs = durationMs;
    }
  }

  /** Information about a patch artifact. */
  public static class PatchArtifactInfo {

    @JsonProperty("patchId")
    private String patchId;

    @JsonProperty("attemptNumber")
    private Integer attemptNumber;

    @JsonProperty("filesChanged")
    private Integer filesChanged;

    @JsonProperty("linesChanged")
    private Integer linesChanged;

    @JsonProperty("confidence")
    private String confidence;

    @JsonProperty("applyStatus")
    private String applyStatus;

    @JsonProperty("validationPassed")
    private Boolean validationPassed;

    @JsonProperty("rolledBack")
    private Boolean rolledBack;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @JsonProperty("rationale")
    private String rationale;

    public String getPatchId() {
      return patchId;
    }

    public void setPatchId(String patchId) {
      this.patchId = patchId;
    }

    public Integer getAttemptNumber() {
      return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
      this.attemptNumber = attemptNumber;
    }

    public Integer getFilesChanged() {
      return filesChanged;
    }

    public void setFilesChanged(Integer filesChanged) {
      this.filesChanged = filesChanged;
    }

    public Integer getLinesChanged() {
      return linesChanged;
    }

    public void setLinesChanged(Integer linesChanged) {
      this.linesChanged = linesChanged;
    }

    public String getConfidence() {
      return confidence;
    }

    public void setConfidence(String confidence) {
      this.confidence = confidence;
    }

    public String getApplyStatus() {
      return applyStatus;
    }

    public void setApplyStatus(String applyStatus) {
      this.applyStatus = applyStatus;
    }

    public Boolean getValidationPassed() {
      return validationPassed;
    }

    public void setValidationPassed(Boolean validationPassed) {
      this.validationPassed = validationPassed;
    }

    public Boolean getRolledBack() {
      return rolledBack;
    }

    public void setRolledBack(Boolean rolledBack) {
      this.rolledBack = rolledBack;
    }

    public LocalDateTime getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
      this.createdAt = createdAt;
    }

    public String getRationale() {
      return rationale;
    }

    public void setRationale(String rationale) {
      this.rationale = rationale;
    }
  }

  /** Timeline information for the execution. */
  public static class ExecutionTimeline {

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @JsonProperty("startedAt")
    private LocalDateTime startedAt;

    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;

    @JsonProperty("completedAt")
    private LocalDateTime completedAt;

    @JsonProperty("totalDurationMs")
    private Long totalDurationMs;

    public LocalDateTime getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
      this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
      return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
      this.startedAt = startedAt;
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

    public Long getTotalDurationMs() {
      return totalDurationMs;
    }

    public void setTotalDurationMs(Long totalDurationMs) {
      this.totalDurationMs = totalDurationMs;
    }
  }

  /** Reuse the CompiledGraphInfo from GraphExecuteResponse. */
  public static class CompiledGraphInfo extends GraphExecuteResponse.CompiledGraphInfo {}
}

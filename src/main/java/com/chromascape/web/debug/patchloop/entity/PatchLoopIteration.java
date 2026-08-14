package com.chromascape.web.debug.patchloop.entity;

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
 * Main entity for tracking patch loop iterations.
 *
 * <p>Represents a single run of the BMAD (Bot Metric Analysis & Diagnostics) pipeline: script run →
 * evidence collection → analysis → recommendations.
 */
@Entity
@Table(name = "patch_loop_iteration")
public class PatchLoopIteration {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Unique iteration ID (e.g., "patchloop_abc123") */
  @Column(unique = true, nullable = false, length = 100)
  private String iterationId;

  /** Current lifecycle status */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private PatchLoopStatus status;

  /** Script name to run */
  @Column(nullable = false, length = 200)
  private String scriptName;

  /** User's prompt/question about the failure */
  @Column(nullable = false, columnDefinition = "TEXT")
  private String prompt;

  /** Additional context about the failure */
  @Column(columnDefinition = "TEXT")
  private String context;

  /** Evidence collection configuration (JSON) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode evidenceConfig;

  // Run tracking
  /** Debug session ID from the script run */
  @Column(length = 100)
  private String runSessionId;

  /** Recording ID from the script run */
  @Column(length = 100)
  private String runRecordingId;

  @Column private LocalDateTime runStartTime;

  @Column private LocalDateTime runEndTime;

  /** Whether the script run succeeded */
  @Column private Boolean runSuccess;

  // Evidence tracking
  @Column private LocalDateTime evidenceStartTime;

  @Column private LocalDateTime evidenceEndTime;

  @Column private Integer evidenceLogCount;

  @Column private Integer evidenceTickLogCount;

  @Column private Integer evidenceDecisionCount;

  // Analysis tracking
  /** Analysis ID (UUID) from the analysis response */
  @Column(length = 100)
  private String analysisId;

  /** Full analysis request (JSON) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode analysisRequestJson;

  /** Full analysis response (JSON) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode analysisResponseJson;

  /** Patch ID from V2 analysis response (stored separately for clean queries) */
  @Column(name = "patch_id", length = 100)
  private String patchId;

  /** Diagnosis summary from analysis */
  @Column(columnDefinition = "TEXT")
  private String diagnosisSummary;

  /** Root cause from analysis */
  @Column(columnDefinition = "TEXT")
  private String rootCause;

  /** Confidence level (HIGH/MEDIUM/LOW) */
  @Column(length = 20)
  private String confidence;

  /** Number of recommendations returned */
  @Column private Integer recommendationCount;

  /** Time taken for analysis (milliseconds) */
  @Column private Long analysisTimeMs;

  // Lifecycle tracking
  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column(nullable = false)
  private LocalDateTime lastStateAt;

  @Column private LocalDateTime completedAt;

  /** Failure code if iteration failed */
  @Column(length = 100)
  private String failureCode;

  /** Failure reason (short message) */
  @Column(length = 500)
  private String failureReason;

  /** Failure context (detailed information) */
  @Column(columnDefinition = "TEXT")
  private String failureContext;

  // Autonomous loop fields (E6.5+)
  /** Execution mode: "analysis_only" or "autofix_loop" */
  @Column(name = "execution_mode", length = 50)
  private String executionMode;

  /** Maximum number of retry attempts */
  @Column(name = "max_attempts")
  private Integer maxAttempts;

  /** Current attempt number (1-indexed) */
  @Column(name = "current_attempt")
  private Integer currentAttempt;

  /** Success criteria in JSON format */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode successCriteria;

  /** Reason why the iteration stopped */
  @Column(name = "stop_reason", length = 200)
  private String stopReason;

  /** Safety policy configuration (JSON) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode policyJson;

  /** Graph execution ID if using graph-based workflow */
  @Column(name = "graph_execution_id", length = 100)
  private String graphExecutionId;

  /** Execution ID from graph execution (links simple API to graph execution) */
  @Column(name = "execution_id", length = 100)
  private String executionId;

  /** Prompt type: ISSUE or FEATURE */
  @Column(name = "prompt_type", length = 20)
  private String promptType;

  /** Session context JSON (debug session, recording, tick window) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "session_context", columnDefinition = "jsonb")
  private JsonNode sessionContext;

  /** Timestamp of the latest lifecycle event */
  @Column(name = "latest_event_at")
  private LocalDateTime latestEventAt;

  // V2 Analysis Output Fields
  /** Patch draft summary from V2 analysis */
  @Column(name = "patch_draft_summary", columnDefinition = "TEXT")
  private String patchDraftSummary;

  /** Retest plan summary from V2 analysis */
  @Column(name = "retest_plan_summary", columnDefinition = "TEXT")
  private String retestPlanSummary;

  /** Number of impacted modules from diagnosis */
  @Column(name = "impacted_modules_count")
  private Integer impactedModulesCount;

  /** Result type: SUCCESS, PARTIAL_SUCCESS, FAILURE, INSUFFICIENT_EVIDENCE */
  @Column(name = "result_type", length = 50)
  private String resultType;

  /** Task plan ID for plan-linked execution */
  @Column(name = "task_plan_id", length = 100)
  private String taskPlanId;

  /** Task plan item ID for plan-linked execution */
  @Column(name = "task_item_id", length = 100)
  private String taskItemId;

  /** Source of this run (planner, builder_goal, builder_goal_analysis, etc.). */
  @Column(name = "source_type", length = 50)
  private String sourceType;

  /** Patch target for recovery flows (repo_local, micro6_task_source, etc.). */
  @Column(name = "patch_target", length = 50)
  private String patchTarget;

  /** Imported builder payload snapshot or other source payload metadata. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "imported_payload_json", columnDefinition = "jsonb")
  private JsonNode importedPayloadJson;

  /** Executor task metadata snapshot used to launch the run. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "task_metadata_json", columnDefinition = "jsonb")
  private JsonNode taskMetadataJson;

  /** Parent iteration ID for child analyze/relaunch runs. */
  @Column(name = "parent_iteration_id", length = 100)
  private String parentIterationId;

  /** Latest Micro6 task source revision applied to this run family. */
  @Column(name = "micro6_revision_id", length = 100)
  private String micro6RevisionId;

  /** Timestamp of the last Micro6 source patch apply. */
  @Column(name = "micro6_patched_at")
  private LocalDateTime micro6PatchedAt;

  public PatchLoopIteration() {
    this.createdAt = LocalDateTime.now();
    this.lastStateAt = LocalDateTime.now();
    this.status = PatchLoopStatus.QUEUED;
    this.executionMode = "analysis_only";
    this.maxAttempts = 3;
    this.currentAttempt = 1;
  }

  // Helper methods

  /** Transitions to a new status and updates lastStateAt. */
  public void transitionTo(PatchLoopStatus newStatus) {
    this.status = newStatus;
    this.lastStateAt = LocalDateTime.now();

    if (newStatus.isTerminal()) {
      this.completedAt = LocalDateTime.now();
    }
  }

  /** Marks this iteration as failed with the given code, reason, and context. */
  public void markFailed(String code, String reason, String context) {
    this.status = PatchLoopStatus.FAILED;
    this.failureCode = code;
    this.failureReason = reason;
    this.failureContext = context;
    this.lastStateAt = LocalDateTime.now();
    this.completedAt = LocalDateTime.now();
  }

  /** Returns true if this iteration is in a terminal state. */
  public boolean isTerminal() {
    return status != null && status.isTerminal();
  }

  /** Returns true if this iteration is active (not terminal). */
  public boolean isActive() {
    return status != null && status.isActive();
  }

  // Getters and setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getIterationId() {
    return iterationId;
  }

  public void setIterationId(String iterationId) {
    this.iterationId = iterationId;
  }

  public PatchLoopStatus getStatus() {
    return status;
  }

  public void setStatus(PatchLoopStatus status) {
    this.status = status;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public String getPrompt() {
    return prompt;
  }

  public void setPrompt(String prompt) {
    this.prompt = prompt;
  }

  public String getContext() {
    return context;
  }

  public void setContext(String context) {
    this.context = context;
  }

  public JsonNode getEvidenceConfig() {
    return evidenceConfig;
  }

  public void setEvidenceConfig(JsonNode evidenceConfig) {
    this.evidenceConfig = evidenceConfig;
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

  public LocalDateTime getRunStartTime() {
    return runStartTime;
  }

  public void setRunStartTime(LocalDateTime runStartTime) {
    this.runStartTime = runStartTime;
  }

  public LocalDateTime getRunEndTime() {
    return runEndTime;
  }

  public void setRunEndTime(LocalDateTime runEndTime) {
    this.runEndTime = runEndTime;
  }

  public Boolean getRunSuccess() {
    return runSuccess;
  }

  public void setRunSuccess(Boolean runSuccess) {
    this.runSuccess = runSuccess;
  }

  public LocalDateTime getEvidenceStartTime() {
    return evidenceStartTime;
  }

  public void setEvidenceStartTime(LocalDateTime evidenceStartTime) {
    this.evidenceStartTime = evidenceStartTime;
  }

  public LocalDateTime getEvidenceEndTime() {
    return evidenceEndTime;
  }

  public void setEvidenceEndTime(LocalDateTime evidenceEndTime) {
    this.evidenceEndTime = evidenceEndTime;
  }

  public Integer getEvidenceLogCount() {
    return evidenceLogCount;
  }

  public void setEvidenceLogCount(Integer evidenceLogCount) {
    this.evidenceLogCount = evidenceLogCount;
  }

  public Integer getEvidenceTickLogCount() {
    return evidenceTickLogCount;
  }

  public void setEvidenceTickLogCount(Integer evidenceTickLogCount) {
    this.evidenceTickLogCount = evidenceTickLogCount;
  }

  public Integer getEvidenceDecisionCount() {
    return evidenceDecisionCount;
  }

  public void setEvidenceDecisionCount(Integer evidenceDecisionCount) {
    this.evidenceDecisionCount = evidenceDecisionCount;
  }

  public String getAnalysisId() {
    return analysisId;
  }

  public void setAnalysisId(String analysisId) {
    this.analysisId = analysisId;
  }

  public JsonNode getAnalysisRequestJson() {
    return analysisRequestJson;
  }

  public void setAnalysisRequestJson(JsonNode analysisRequestJson) {
    this.analysisRequestJson = analysisRequestJson;
  }

  public JsonNode getAnalysisResponseJson() {
    return analysisResponseJson;
  }

  public void setAnalysisResponseJson(JsonNode analysisResponseJson) {
    this.analysisResponseJson = analysisResponseJson;
  }

  public String getPatchId() {
    return patchId;
  }

  public void setPatchId(String patchId) {
    this.patchId = patchId;
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

  public Long getAnalysisTimeMs() {
    return analysisTimeMs;
  }

  public void setAnalysisTimeMs(Long analysisTimeMs) {
    this.analysisTimeMs = analysisTimeMs;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getLastStateAt() {
    return lastStateAt;
  }

  public void setLastStateAt(LocalDateTime lastStateAt) {
    this.lastStateAt = lastStateAt;
  }

  public LocalDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(LocalDateTime completedAt) {
    this.completedAt = completedAt;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public void setFailureCode(String failureCode) {
    this.failureCode = failureCode;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public void setFailureReason(String failureReason) {
    this.failureReason = failureReason;
  }

  public String getFailureContext() {
    return failureContext;
  }

  public void setFailureContext(String failureContext) {
    this.failureContext = failureContext;
  }

  public String getExecutionMode() {
    return executionMode;
  }

  public void setExecutionMode(String executionMode) {
    this.executionMode = executionMode;
  }

  public Integer getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(Integer maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Integer getCurrentAttempt() {
    return currentAttempt;
  }

  public void setCurrentAttempt(Integer currentAttempt) {
    this.currentAttempt = currentAttempt;
  }

  public JsonNode getSuccessCriteria() {
    return successCriteria;
  }

  public void setSuccessCriteria(JsonNode successCriteria) {
    this.successCriteria = successCriteria;
  }

  public String getStopReason() {
    return stopReason;
  }

  public void setStopReason(String stopReason) {
    this.stopReason = stopReason;
  }

  public JsonNode getPolicyJson() {
    return policyJson;
  }

  public void setPolicyJson(JsonNode policyJson) {
    this.policyJson = policyJson;
  }

  public String getGraphExecutionId() {
    return graphExecutionId;
  }

  public void setGraphExecutionId(String graphExecutionId) {
    this.graphExecutionId = graphExecutionId;
  }

  public String getExecutionId() {
    return executionId;
  }

  public void setExecutionId(String executionId) {
    this.executionId = executionId;
  }

  public String getPromptType() {
    return promptType;
  }

  public void setPromptType(String promptType) {
    this.promptType = promptType;
  }

  public JsonNode getSessionContext() {
    return sessionContext;
  }

  public void setSessionContext(JsonNode sessionContext) {
    this.sessionContext = sessionContext;
  }

  public LocalDateTime getLatestEventAt() {
    return latestEventAt;
  }

  public void setLatestEventAt(LocalDateTime latestEventAt) {
    this.latestEventAt = latestEventAt;
  }

  public String getPatchDraftSummary() {
    return patchDraftSummary;
  }

  public void setPatchDraftSummary(String patchDraftSummary) {
    this.patchDraftSummary = patchDraftSummary;
  }

  public String getRetestPlanSummary() {
    return retestPlanSummary;
  }

  public void setRetestPlanSummary(String retestPlanSummary) {
    this.retestPlanSummary = retestPlanSummary;
  }

  public Integer getImpactedModulesCount() {
    return impactedModulesCount;
  }

  public void setImpactedModulesCount(Integer impactedModulesCount) {
    this.impactedModulesCount = impactedModulesCount;
  }

  public String getResultType() {
    return resultType;
  }

  public void setResultType(String resultType) {
    this.resultType = resultType;
  }

  public String getTaskPlanId() {
    return taskPlanId;
  }

  public void setTaskPlanId(String taskPlanId) {
    this.taskPlanId = taskPlanId;
  }

  public String getTaskItemId() {
    return taskItemId;
  }

  public void setTaskItemId(String taskItemId) {
    this.taskItemId = taskItemId;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getPatchTarget() {
    return patchTarget;
  }

  public void setPatchTarget(String patchTarget) {
    this.patchTarget = patchTarget;
  }

  public JsonNode getImportedPayloadJson() {
    return importedPayloadJson;
  }

  public void setImportedPayloadJson(JsonNode importedPayloadJson) {
    this.importedPayloadJson = importedPayloadJson;
  }

  public JsonNode getTaskMetadataJson() {
    return taskMetadataJson;
  }

  public void setTaskMetadataJson(JsonNode taskMetadataJson) {
    this.taskMetadataJson = taskMetadataJson;
  }

  public String getParentIterationId() {
    return parentIterationId;
  }

  public void setParentIterationId(String parentIterationId) {
    this.parentIterationId = parentIterationId;
  }

  public String getMicro6RevisionId() {
    return micro6RevisionId;
  }

  public void setMicro6RevisionId(String micro6RevisionId) {
    this.micro6RevisionId = micro6RevisionId;
  }

  public LocalDateTime getMicro6PatchedAt() {
    return micro6PatchedAt;
  }

  public void setMicro6PatchedAt(LocalDateTime micro6PatchedAt) {
    this.micro6PatchedAt = micro6PatchedAt;
  }

  /** Increments the current attempt counter. */
  public void incrementAttempt() {
    this.currentAttempt = (this.currentAttempt == null) ? 2 : this.currentAttempt + 1;
  }

  /** Returns true if this is an autonomous fix loop iteration. */
  public boolean isAutofixLoop() {
    return "autofix_loop".equals(executionMode);
  }

  /** Returns true if more attempts are allowed. */
  public boolean hasMoreAttempts() {
    int max = maxAttempts != null ? maxAttempts : 3;
    int current = currentAttempt != null ? currentAttempt : 1;
    return current < max;
  }

  @Override
  public String toString() {
    return "PatchLoopIteration{"
        + "id="
        + id
        + ", iterationId='"
        + iterationId
        + '\''
        + ", status="
        + status
        + ", scriptName='"
        + scriptName
        + '\''
        + ", executionMode='"
        + executionMode
        + '\''
        + ", currentAttempt="
        + currentAttempt
        + ", maxAttempts="
        + maxAttempts
        + ", createdAt="
        + createdAt
        + ", completedAt="
        + completedAt
        + '}';
  }
}

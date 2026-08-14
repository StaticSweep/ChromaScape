package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Response DTO for patch loop iteration details.
 *
 * <p>Contains full iteration state including run info, evidence counts, and analysis results.
 */
public class PatchLoopIterationResponse {

  @JsonProperty("iterationId")
  private String iterationId;

  @JsonProperty("status")
  private String status;

  @JsonProperty("scriptName")
  private String scriptName;

  @JsonProperty("prompt")
  private String prompt;

  @JsonProperty("context")
  private String context;

  @JsonProperty("createdAt")
  private LocalDateTime createdAt;

  @JsonProperty("lastStateAt")
  private LocalDateTime lastStateAt;

  @JsonProperty("completedAt")
  private LocalDateTime completedAt;

  // Run tracking
  @JsonProperty("runSessionId")
  private String runSessionId;

  @JsonProperty("runRecordingId")
  private String runRecordingId;

  @JsonProperty("runDurationMs")
  private Long runDurationMs;

  @JsonProperty("runSuccess")
  private Boolean runSuccess;

  // Evidence tracking
  @JsonProperty("evidenceLogCount")
  private Integer evidenceLogCount;

  @JsonProperty("evidenceTickLogCount")
  private Integer evidenceTickLogCount;

  @JsonProperty("evidenceDecisionCount")
  private Integer evidenceDecisionCount;

  // Analysis results
  @JsonProperty("analysisId")
  private String analysisId;

  @JsonProperty("diagnosisSummary")
  private String diagnosisSummary;

  @JsonProperty("rootCause")
  private String rootCause;

  @JsonProperty("confidence")
  private String confidence;

  @JsonProperty("recommendationCount")
  private Integer recommendationCount;

  @JsonProperty("analysisTimeMs")
  private Long analysisTimeMs;

  // Failure tracking
  @JsonProperty("failureCode")
  private String failureCode;

  @JsonProperty("failureReason")
  private String failureReason;

  // Computed fields
  @JsonProperty("isActive")
  private Boolean isActive;

  @JsonProperty("isComplete")
  private Boolean isComplete;

  // E6.5+: Execution configuration fields
  @JsonProperty("executionMode")
  private String executionMode;

  @JsonProperty("maxAttempts")
  private Integer maxAttempts;

  @JsonProperty("currentAttempt")
  private Integer currentAttempt;

  @JsonProperty("sessionContext")
  private SessionContextDto sessionContext;

  @JsonProperty("artifactSummary")
  private ArtifactSummaryDto artifactSummary;

  @JsonProperty("retestSummary")
  private RetestSummaryDto retestSummary;

  public PatchLoopIterationResponse() {}

  // Getters and setters

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

  public Long getRunDurationMs() {
    return runDurationMs;
  }

  public void setRunDurationMs(Long runDurationMs) {
    this.runDurationMs = runDurationMs;
  }

  public Boolean getRunSuccess() {
    return runSuccess;
  }

  public void setRunSuccess(Boolean runSuccess) {
    this.runSuccess = runSuccess;
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

  public Boolean getIsActive() {
    return isActive;
  }

  public void setIsActive(Boolean isActive) {
    this.isActive = isActive;
  }

  public Boolean getIsComplete() {
    return isComplete;
  }

  public void setIsComplete(Boolean isComplete) {
    this.isComplete = isComplete;
  }

  // E6.5+: Getters and setters for new fields

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

  public SessionContextDto getSessionContext() {
    return sessionContext;
  }

  public void setSessionContext(SessionContextDto sessionContext) {
    this.sessionContext = sessionContext;
  }

  public ArtifactSummaryDto getArtifactSummary() {
    return artifactSummary;
  }

  public void setArtifactSummary(ArtifactSummaryDto artifactSummary) {
    this.artifactSummary = artifactSummary;
  }

  public RetestSummaryDto getRetestSummary() {
    return retestSummary;
  }

  public void setRetestSummary(RetestSummaryDto retestSummary) {
    this.retestSummary = retestSummary;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchLoopIterationResponse that = (PatchLoopIterationResponse) o;
    return Objects.equals(iterationId, that.iterationId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(iterationId);
  }

  @Override
  public String toString() {
    return "PatchLoopIterationResponse{"
        + "iterationId='"
        + iterationId
        + '\''
        + ", status='"
        + status
        + '\''
        + ", scriptName='"
        + scriptName
        + '\''
        + ", executionMode='"
        + executionMode
        + '\''
        + ", maxAttempts="
        + maxAttempts
        + ", currentAttempt="
        + currentAttempt
        + ", createdAt="
        + createdAt
        + ", completedAt="
        + completedAt
        + '}';
  }
}

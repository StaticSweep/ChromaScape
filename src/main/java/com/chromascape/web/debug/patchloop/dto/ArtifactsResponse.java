package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
 * Response containing artifacts from a graph execution.
 *
 * <p>Includes patches, validations, retest results, and analysis data.
 */
public class ArtifactsResponse {

  /** Execution ID */
  @JsonProperty("executionId")
  private String executionId;

  /** All patch artifacts */
  @JsonProperty("patches")
  private List<PatchArtifactDetail> patches;

  /** All validation results */
  @JsonProperty("validations")
  private List<ValidationArtifact> validations;

  /** All retest results */
  @JsonProperty("retests")
  private List<RetestArtifact> retests;

  /** Analysis results */
  @JsonProperty("analyses")
  private List<AnalysisArtifact> analyses;

  /** Evidence collected */
  @JsonProperty("evidence")
  private List<EvidenceArtifact> evidence;

  /** Script run terminal details */
  @JsonProperty("scriptRuns")
  private List<ScriptRunArtifact> scriptRuns;

  public ArtifactsResponse() {}

  public String getExecutionId() {
    return executionId;
  }

  public void setExecutionId(String executionId) {
    this.executionId = executionId;
  }

  public List<PatchArtifactDetail> getPatches() {
    return patches;
  }

  public void setPatches(List<PatchArtifactDetail> patches) {
    this.patches = patches;
  }

  public List<ValidationArtifact> getValidations() {
    return validations;
  }

  public void setValidations(List<ValidationArtifact> validations) {
    this.validations = validations;
  }

  public List<RetestArtifact> getRetests() {
    return retests;
  }

  public void setRetests(List<RetestArtifact> retests) {
    this.retests = retests;
  }

  public List<AnalysisArtifact> getAnalyses() {
    return analyses;
  }

  public void setAnalyses(List<AnalysisArtifact> analyses) {
    this.analyses = analyses;
  }

  public List<EvidenceArtifact> getEvidence() {
    return evidence;
  }

  public void setEvidence(List<EvidenceArtifact> evidence) {
    this.evidence = evidence;
  }

  public List<ScriptRunArtifact> getScriptRuns() {
    return scriptRuns;
  }

  public void setScriptRuns(List<ScriptRunArtifact> scriptRuns) {
    this.scriptRuns = scriptRuns;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ArtifactsResponse that = (ArtifactsResponse) o;
    return Objects.equals(executionId, that.executionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(executionId);
  }

  /** Detailed patch artifact information. */
  public static class PatchArtifactDetail {

    @JsonProperty("patchId")
    private String patchId;

    @JsonProperty("attemptNumber")
    private Integer attemptNumber;

    @JsonProperty("diffText")
    private String diffText;

    @JsonProperty("rationale")
    private String rationale;

    @JsonProperty("affectedFiles")
    private List<String> affectedFiles;

    @JsonProperty("filesChanged")
    private Integer filesChanged;

    @JsonProperty("linesChanged")
    private Integer linesChanged;

    @JsonProperty("confidence")
    private String confidence;

    @JsonProperty("applyStatus")
    private String applyStatus;

    @JsonProperty("gitBranch")
    private String gitBranch;

    @JsonProperty("gitCommit")
    private String gitCommit;

    @JsonProperty("validationPassed")
    private Boolean validationPassed;

    @JsonProperty("validationOutput")
    private String validationOutput;

    @JsonProperty("rolledBack")
    private Boolean rolledBack;

    @JsonProperty("rollbackReason")
    private String rollbackReason;

    @JsonProperty("createdAt")
    private String createdAt;

    @JsonProperty("appliedAt")
    private String appliedAt;

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

    public String getDiffText() {
      return diffText;
    }

    public void setDiffText(String diffText) {
      this.diffText = diffText;
    }

    public String getRationale() {
      return rationale;
    }

    public void setRationale(String rationale) {
      this.rationale = rationale;
    }

    public List<String> getAffectedFiles() {
      return affectedFiles;
    }

    public void setAffectedFiles(List<String> affectedFiles) {
      this.affectedFiles = affectedFiles;
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

    public String getGitBranch() {
      return gitBranch;
    }

    public void setGitBranch(String gitBranch) {
      this.gitBranch = gitBranch;
    }

    public String getGitCommit() {
      return gitCommit;
    }

    public void setGitCommit(String gitCommit) {
      this.gitCommit = gitCommit;
    }

    public Boolean getValidationPassed() {
      return validationPassed;
    }

    public void setValidationPassed(Boolean validationPassed) {
      this.validationPassed = validationPassed;
    }

    public String getValidationOutput() {
      return validationOutput;
    }

    public void setValidationOutput(String validationOutput) {
      this.validationOutput = validationOutput;
    }

    public Boolean getRolledBack() {
      return rolledBack;
    }

    public void setRolledBack(Boolean rolledBack) {
      this.rolledBack = rolledBack;
    }

    public String getRollbackReason() {
      return rollbackReason;
    }

    public void setRollbackReason(String rollbackReason) {
      this.rollbackReason = rollbackReason;
    }

    public String getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(String createdAt) {
      this.createdAt = createdAt;
    }

    public String getAppliedAt() {
      return appliedAt;
    }

    public void setAppliedAt(String appliedAt) {
      this.appliedAt = appliedAt;
    }
  }

  /** Validation artifact. */
  public static class ValidationArtifact {

    @JsonProperty("patchId")
    private String patchId;

    @JsonProperty("attemptNumber")
    private Integer attemptNumber;

    @JsonProperty("passed")
    private Boolean passed;

    @JsonProperty("output")
    private String output;

    @JsonProperty("exitCode")
    private Integer exitCode;

    @JsonProperty("durationMs")
    private Long durationMs;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("rolledBack")
    private Boolean rolledBack;

    @JsonProperty("rollbackReason")
    private String rollbackReason;

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

    public Boolean getPassed() {
      return passed;
    }

    public void setPassed(Boolean passed) {
      this.passed = passed;
    }

    public String getOutput() {
      return output;
    }

    public void setOutput(String output) {
      this.output = output;
    }

    public Integer getExitCode() {
      return exitCode;
    }

    public void setExitCode(Integer exitCode) {
      this.exitCode = exitCode;
    }

    public Long getDurationMs() {
      return durationMs;
    }

    public void setDurationMs(Long durationMs) {
      this.durationMs = durationMs;
    }

    public String getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(String timestamp) {
      this.timestamp = timestamp;
    }

    public Boolean getRolledBack() {
      return rolledBack;
    }

    public void setRolledBack(Boolean rolledBack) {
      this.rolledBack = rolledBack;
    }

    public String getRollbackReason() {
      return rollbackReason;
    }

    public void setRollbackReason(String rollbackReason) {
      this.rollbackReason = rollbackReason;
    }
  }

  /** Retest artifact. */
  public static class RetestArtifact {

    @JsonProperty("attemptNumber")
    private Integer attemptNumber;

    @JsonProperty("baselineAttemptNumber")
    private Integer baselineAttemptNumber;

    @JsonProperty("success")
    private Boolean success;

    @JsonProperty("comparisonSummary")
    private String comparisonSummary;

    @JsonProperty("improvements")
    private List<String> improvements;

    @JsonProperty("improvementsCount")
    private Integer improvementsCount;

    @JsonProperty("regressions")
    private List<String> regressions;

    @JsonProperty("regressionsCount")
    private Integer regressionsCount;

    @JsonProperty("timestamp")
    private String timestamp;

    public Integer getAttemptNumber() {
      return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
      this.attemptNumber = attemptNumber;
    }

    public Integer getBaselineAttemptNumber() {
      return baselineAttemptNumber;
    }

    public void setBaselineAttemptNumber(Integer baselineNumber) {
      this.baselineAttemptNumber = baselineNumber;
    }

    public Boolean getSuccess() {
      return success;
    }

    public void setSuccess(Boolean success) {
      this.success = success;
    }

    public String getComparisonSummary() {
      return comparisonSummary;
    }

    public void setComparisonSummary(String comparisonSummary) {
      this.comparisonSummary = comparisonSummary;
    }

    public List<String> getImprovements() {
      return improvements;
    }

    public void setImprovements(List<String> improvements) {
      this.improvements = improvements;
    }

    public Integer getImprovementsCount() {
      return improvementsCount;
    }

    public void setImprovementsCount(Integer improvementsCount) {
      this.improvementsCount = improvementsCount;
    }

    public List<String> getRegressions() {
      return regressions;
    }

    public void setRegressions(List<String> regressions) {
      this.regressions = regressions;
    }

    public Integer getRegressionsCount() {
      return regressionsCount;
    }

    public void setRegressionsCount(Integer regressionsCount) {
      this.regressionsCount = regressionsCount;
    }

    public String getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(String timestamp) {
      this.timestamp = timestamp;
    }
  }

  /** Analysis artifact. */
  public static class AnalysisArtifact {

    @JsonProperty("attemptNumber")
    private Integer attemptNumber;

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

    @JsonProperty("timestamp")
    private String timestamp;

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

    public String getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(String timestamp) {
      this.timestamp = timestamp;
    }
  }

  /** Evidence artifact. */
  public static class EvidenceArtifact {

    @JsonProperty("attemptNumber")
    private Integer attemptNumber;

    @JsonProperty("evidenceType")
    private String evidenceType;

    @JsonProperty("count")
    private Integer count;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("timestamp")
    private String timestamp;

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

    public String getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(String timestamp) {
      this.timestamp = timestamp;
    }
  }

  /** Script run artifact. */
  public static class ScriptRunArtifact {

    @JsonProperty("nodeId")
    private String nodeId;

    @JsonProperty("attemptNumber")
    private Integer attemptNumber;

    @JsonProperty("scriptName")
    private String scriptName;

    @JsonProperty("backend")
    private String backend;

    @JsonProperty("terminalStatus")
    private String terminalStatus;

    @JsonProperty("timedOut")
    private Boolean timedOut;

    @JsonProperty("success")
    private Boolean success;

    @JsonProperty("errorMessage")
    private String errorMessage;

    @JsonProperty("durationMs")
    private Long durationMs;

    @JsonProperty("startedAt")
    private String startedAt;

    @JsonProperty("completedAt")
    private String completedAt;

    public String getNodeId() {
      return nodeId;
    }

    public void setNodeId(String nodeId) {
      this.nodeId = nodeId;
    }

    public Integer getAttemptNumber() {
      return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
      this.attemptNumber = attemptNumber;
    }

    public String getScriptName() {
      return scriptName;
    }

    public void setScriptName(String scriptName) {
      this.scriptName = scriptName;
    }

    public String getBackend() {
      return backend;
    }

    public void setBackend(String backend) {
      this.backend = backend;
    }

    public String getTerminalStatus() {
      return terminalStatus;
    }

    public void setTerminalStatus(String terminalStatus) {
      this.terminalStatus = terminalStatus;
    }

    public Boolean getTimedOut() {
      return timedOut;
    }

    public void setTimedOut(Boolean timedOut) {
      this.timedOut = timedOut;
    }

    public Boolean getSuccess() {
      return success;
    }

    public void setSuccess(Boolean success) {
      this.success = success;
    }

    public String getErrorMessage() {
      return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
      this.errorMessage = errorMessage;
    }

    public Long getDurationMs() {
      return durationMs;
    }

    public void setDurationMs(Long durationMs) {
      this.durationMs = durationMs;
    }

    public String getStartedAt() {
      return startedAt;
    }

    public void setStartedAt(String startedAt) {
      this.startedAt = startedAt;
    }

    public String getCompletedAt() {
      return completedAt;
    }

    public void setCompletedAt(String completedAt) {
      this.completedAt = completedAt;
    }
  }
}

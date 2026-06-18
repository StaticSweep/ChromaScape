package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Request to execute a Mermaid-defined patch loop graph.
 *
 * <p>Contains the graph definition, script configuration, goal prompt, and safety policy.
 */
public class GraphExecuteRequest {

  /** Mermaid graph source code */
  @JsonProperty("mermaid")
  private String mermaid;

  /** Script name to execute */
  @JsonProperty("scriptName")
  private String scriptName;

  /** Goal prompt describing what the loop should achieve */
  @JsonProperty("goalPrompt")
  private String goalPrompt;

  /** Additional context about the issue */
  @JsonProperty("context")
  private String context;

  /** Maximum number of attempts (default: 3) */
  @JsonProperty("maxAttempts")
  private Integer maxAttempts;

  /** Success criteria JSON (optional predicate for stopping) */
  @JsonProperty("successCriteria")
  private Map<String, Object> successCriteria;

  /** Safety policy configuration (optional, overrides defaults) */
  @JsonProperty("policy")
  private PatchPolicyConfig policy;

  /** Execution mode: "analysis_only", "propose_then_apply", or "autofix_loop" */
  @JsonProperty("executionMode")
  private String executionMode;

  /** Evidence collection configuration (optional) */
  @JsonProperty("evidenceConfig")
  private EvidenceConfigDto evidenceConfig;

  /** Debugger session context for linking to existing sessions (optional). */
  @JsonProperty("sessionContext")
  private SessionContextDto sessionContext;

  /** Task metadata for executor-side configuration (optional) */
  @JsonProperty("taskMetadata")
  private Map<String, Object> taskMetadata;

  /**
   * Optional iteration ID to reuse an existing iteration.
   *
   * <p>When provided, the graph orchestrator will reuse the existing iteration instead of creating
   * a new one. This is used by the simple API wrapper to avoid creating duplicate iterations.
   */
  @JsonProperty("iterationId")
  private String iterationId;

  public GraphExecuteRequest() {
    this.maxAttempts = 3;
    this.executionMode = "analysis_only";
    this.successCriteria = new HashMap<>();
  }

  public String getMermaid() {
    return mermaid;
  }

  public void setMermaid(String mermaid) {
    this.mermaid = mermaid;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public String getGoalPrompt() {
    return goalPrompt;
  }

  public void setGoalPrompt(String goalPrompt) {
    this.goalPrompt = goalPrompt;
  }

  public String getContext() {
    return context;
  }

  public void setContext(String context) {
    this.context = context;
  }

  public Integer getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(Integer maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Map<String, Object> getSuccessCriteria() {
    return successCriteria;
  }

  public void setSuccessCriteria(Map<String, Object> successCriteria) {
    this.successCriteria = successCriteria;
  }

  public PatchPolicyConfig getPolicy() {
    return policy;
  }

  public void setPolicy(PatchPolicyConfig policy) {
    this.policy = policy;
  }

  public String getExecutionMode() {
    return executionMode;
  }

  /**
   * Returns the normalized execution mode.
   *
   * <p>Handles legacy mode values for backward compatibility. For example, {@code "full_loop"} is
   * normalized to {@code "autofix_loop"}.
   *
   * @return the normalized execution mode, or {@code "analysis_only"} if null
   */
  public String getNormalizedExecutionMode() {
    if (executionMode == null) {
      return "analysis_only";
    }
    if ("full_loop".equals(executionMode)) {
      return "autofix_loop";
    }
    return executionMode;
  }

  public void setExecutionMode(String executionMode) {
    this.executionMode = executionMode;
  }

  public EvidenceConfigDto getEvidenceConfig() {
    return evidenceConfig;
  }

  public void setEvidenceConfig(EvidenceConfigDto evidenceConfig) {
    this.evidenceConfig = evidenceConfig;
  }

  public SessionContextDto getSessionContext() {
    return sessionContext;
  }

  public void setSessionContext(SessionContextDto sessionContext) {
    this.sessionContext = sessionContext;
  }

  public String getIterationId() {
    return iterationId;
  }

  public void setIterationId(String iterationId) {
    this.iterationId = iterationId;
  }

  public Map<String, Object> getTaskMetadata() {
    return taskMetadata;
  }

  public void setTaskMetadata(Map<String, Object> taskMetadata) {
    this.taskMetadata = taskMetadata;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GraphExecuteRequest that = (GraphExecuteRequest) o;
    return Objects.equals(mermaid, that.mermaid)
        && Objects.equals(scriptName, that.scriptName)
        && Objects.equals(goalPrompt, that.goalPrompt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mermaid, scriptName, goalPrompt);
  }

  @Override
  public String toString() {
    return "GraphExecuteRequest{"
        + "scriptName='"
        + scriptName
        + '\''
        + ", executionMode='"
        + executionMode
        + '\''
        + ", maxAttempts="
        + maxAttempts
        + ", iterationId='"
        + iterationId
        + '\''
        + '}';
  }

  /** Safety policy configuration for patch application. */
  public static class PatchPolicyConfig {

    @JsonProperty("maxFilesChanged")
    private Integer maxFilesChanged = 5;

    @JsonProperty("maxLinesChanged")
    private Integer maxLinesChanged = 300;

    @JsonProperty("allowedPaths")
    private List<String> allowedPaths = new ArrayList<>();

    @JsonProperty("blockedPaths")
    private List<String> blockedPaths = new ArrayList<>();

    @JsonProperty("forbiddenPatterns")
    private List<String> forbiddenPatterns = new ArrayList<>();

    @JsonProperty("autoRollbackOnFailure")
    private Boolean autoRollbackOnFailure = true;

    @JsonProperty("timeoutMinutes")
    private Integer timeoutMinutes = 30;

    public Integer getMaxFilesChanged() {
      return maxFilesChanged;
    }

    public void setMaxFilesChanged(Integer maxFilesChanged) {
      this.maxFilesChanged = maxFilesChanged;
    }

    public Integer getMaxLinesChanged() {
      return maxLinesChanged;
    }

    public void setMaxLinesChanged(Integer maxLinesChanged) {
      this.maxLinesChanged = maxLinesChanged;
    }

    public List<String> getAllowedPaths() {
      return allowedPaths;
    }

    public void setAllowedPaths(List<String> allowedPaths) {
      this.allowedPaths = allowedPaths;
    }

    public List<String> getBlockedPaths() {
      return blockedPaths;
    }

    public void setBlockedPaths(List<String> blockedPaths) {
      this.blockedPaths = blockedPaths;
    }

    public List<String> getForbiddenPatterns() {
      return forbiddenPatterns;
    }

    public void setForbiddenPatterns(List<String> forbiddenPatterns) {
      this.forbiddenPatterns = forbiddenPatterns;
    }

    public Boolean getAutoRollbackOnFailure() {
      return autoRollbackOnFailure;
    }

    public void setAutoRollbackOnFailure(Boolean autoRollbackOnFailure) {
      this.autoRollbackOnFailure = autoRollbackOnFailure;
    }

    public Integer getTimeoutMinutes() {
      return timeoutMinutes;
    }

    public void setTimeoutMinutes(Integer timeoutMinutes) {
      this.timeoutMinutes = timeoutMinutes;
    }
  }
}

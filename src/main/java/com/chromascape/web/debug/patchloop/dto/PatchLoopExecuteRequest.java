package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

/**
 * Request to execute a patch loop iteration.
 *
 * <p>Triggers the full BMAD pipeline: script run → evidence collection → analysis → retest.
 *
 * @since 2.1.0
 */
public class PatchLoopExecuteRequest {

  /** Script name to run (required) */
  @JsonProperty("scriptName")
  private String scriptName;

  /** Question/prompt for analysis (required) */
  @JsonProperty("prompt")
  private String prompt;

  /** Additional context about the failure (optional) */
  @JsonProperty("context")
  private String context;

  /** Evidence collection configuration (optional) */
  @JsonProperty("evidenceConfig")
  private EvidenceConfigDto evidenceConfig;

  /** Type of prompt (default: ISSUE) */
  @JsonProperty("promptType")
  private PromptType promptType = PromptType.ISSUE;

  /** Execution mode: "analysis_only" or "autofix_loop" (default: analysis_only) */
  @JsonProperty("executionMode")
  private String executionMode = "analysis_only";

  /** Maximum number of attempts (default: 3) */
  @JsonProperty("maxAttempts")
  private Integer maxAttempts = 3;

  /** Success criteria for loop termination (optional) */
  @JsonProperty("successCriteria")
  private Map<String, Object> successCriteria;

  /** Debugger session context for linking to existing sessions (optional) */
  @JsonProperty("sessionContext")
  private SessionContextDto sessionContext;

  public PatchLoopExecuteRequest() {}

  public PatchLoopExecuteRequest(String scriptName, String prompt) {
    this.scriptName = scriptName;
    this.prompt = prompt;
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

  public EvidenceConfigDto getEvidenceConfig() {
    return evidenceConfig;
  }

  public void setEvidenceConfig(EvidenceConfigDto evidenceConfig) {
    this.evidenceConfig = evidenceConfig;
  }

  public PromptType getPromptType() {
    return promptType;
  }

  public void setPromptType(PromptType promptType) {
    this.promptType = promptType;
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

  public Map<String, Object> getSuccessCriteria() {
    return successCriteria;
  }

  public void setSuccessCriteria(Map<String, Object> successCriteria) {
    this.successCriteria = successCriteria;
  }

  public SessionContextDto getSessionContext() {
    return sessionContext;
  }

  public void setSessionContext(SessionContextDto sessionContext) {
    this.sessionContext = sessionContext;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchLoopExecuteRequest that = (PatchLoopExecuteRequest) o;
    return Objects.equals(scriptName, that.scriptName)
        && Objects.equals(prompt, that.prompt)
        && Objects.equals(context, that.context)
        && Objects.equals(evidenceConfig, that.evidenceConfig);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scriptName, prompt, context, evidenceConfig);
  }

  @Override
  public String toString() {
    return "PatchLoopExecuteRequest{"
        + "scriptName='"
        + scriptName
        + '\''
        + ", prompt='"
        + prompt
        + '\''
        + ", context='"
        + context
        + '\''
        + ", promptType="
        + promptType
        + ", executionMode='"
        + executionMode
        + '\''
        + ", maxAttempts="
        + maxAttempts
        + '}';
  }
}

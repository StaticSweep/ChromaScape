package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Request to execute a patch loop iteration.
 *
 * <p>Triggers the full pipeline: script run → evidence collection → analysis.
 */
public class PatchLoopExecuteRequest {

  /** Script name to run (required) */
  @JsonProperty("scriptName")
  private String scriptName;

  /** Question/prompt to ask Claude (required) */
  @JsonProperty("prompt")
  private String prompt;

  /** Additional context about the failure (optional) */
  @JsonProperty("context")
  private String context;

  /** Evidence collection configuration (optional) */
  @JsonProperty("evidenceConfig")
  private EvidenceConfigDto evidenceConfig;

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
        + ", evidenceConfig="
        + evidenceConfig
        + '}';
  }
}

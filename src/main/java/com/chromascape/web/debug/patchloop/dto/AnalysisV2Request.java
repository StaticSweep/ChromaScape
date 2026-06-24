package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Request for analysis.v2 contract.
 *
 * <p>Breaking change from V1: adds structured promptType, target, and evidenceSelectors for
 * autonomous loop support.
 *
 * @since 2.0.0
 */
public class AnalysisV2Request {

  /** Current schema version for the analysis.v2 contract. */
  public static final String SCHEMA_VERSION = "2.0.0";

  /** Schema version for contract negotiation (required). */
  @JsonProperty("schemaVersion")
  private String schemaVersion = SCHEMA_VERSION;

  /** Type of analysis prompt (required). */
  @JsonProperty("promptType")
  private PromptType promptType;

  /** Target specification for analysis (required). */
  @JsonProperty("target")
  private AnalysisV2Target target;

  /** The question or prompt to ask the analyzer (required). */
  @JsonProperty("prompt")
  private String prompt;

  /** Additional context about the failure or issue (optional). */
  @JsonProperty("context")
  private String context;

  /** Evidence selectors for collection (required). */
  @JsonProperty("evidenceSelectors")
  private List<AnalysisV2EvidenceSelector> evidenceSelectors;

  /** Concrete evidence bundle to analyze (optional, used when the caller preselects evidence). */
  @JsonProperty("evidenceBundle")
  private AnalysisV1EvidenceBundle evidenceBundle;

  /** Configuration options for the analysis (optional). */
  @JsonProperty("config")
  private AnalysisV1Config config;

  public AnalysisV2Request() {
    this.schemaVersion = SCHEMA_VERSION;
    this.evidenceSelectors = new ArrayList<>();
  }

  public AnalysisV2Request(
      PromptType promptType,
      AnalysisV2Target target,
      String prompt,
      List<AnalysisV2EvidenceSelector> evidenceSelectors) {
    this.schemaVersion = SCHEMA_VERSION;
    this.promptType = promptType;
    this.target = target;
    this.prompt = prompt;
    this.evidenceSelectors = evidenceSelectors != null ? evidenceSelectors : new ArrayList<>();
  }

  public String getSchemaVersion() {
    return schemaVersion != null ? schemaVersion : SCHEMA_VERSION;
  }

  public void setSchemaVersion(String schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public PromptType getPromptType() {
    return promptType;
  }

  public void setPromptType(PromptType promptType) {
    this.promptType = promptType;
  }

  public AnalysisV2Target getTarget() {
    return target;
  }

  public void setTarget(AnalysisV2Target target) {
    this.target = target;
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

  public List<AnalysisV2EvidenceSelector> getEvidenceSelectors() {
    return evidenceSelectors != null ? evidenceSelectors : new ArrayList<>();
  }

  public void setEvidenceSelectors(List<AnalysisV2EvidenceSelector> evidenceSelectors) {
    this.evidenceSelectors = evidenceSelectors;
  }

  public AnalysisV1EvidenceBundle getEvidenceBundle() {
    return evidenceBundle;
  }

  public void setEvidenceBundle(AnalysisV1EvidenceBundle evidenceBundle) {
    this.evidenceBundle = evidenceBundle;
  }

  public AnalysisV1Config getConfig() {
    return config;
  }

  public void setConfig(AnalysisV1Config config) {
    this.config = config;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AnalysisV2Request that = (AnalysisV2Request) o;
    return Objects.equals(schemaVersion, that.schemaVersion)
        && promptType == that.promptType
        && Objects.equals(target, that.target)
        && Objects.equals(prompt, that.prompt)
        && Objects.equals(context, that.context)
        && Objects.equals(evidenceSelectors, that.evidenceSelectors)
        && Objects.equals(evidenceBundle, that.evidenceBundle)
        && Objects.equals(config, that.config);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        schemaVersion, promptType, target, prompt, context, evidenceSelectors, evidenceBundle, config);
  }

  @Override
  public String toString() {
    return "AnalysisV2Request{"
        + "schemaVersion='"
        + schemaVersion
        + '\''
        + ", promptType="
        + promptType
        + ", target="
        + target
        + ", prompt='"
        + prompt
        + '\''
        + ", context='"
        + context
        + '\''
        + ", evidenceSelectors="
        + evidenceSelectors.size()
        + ", evidenceBundle="
        + (evidenceBundle != null ? evidenceBundle.getTotalEvidenceCount() : 0)
        + ", config="
        + config
        + '}';
  }
}

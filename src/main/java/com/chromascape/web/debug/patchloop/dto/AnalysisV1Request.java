package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Request for analysis.v1 contract.
 *
 * <p>Contains the prompt/question to ask about the evidence, along with the evidence bundle and
 * optional configuration.
 */
public class AnalysisV1Request {

  /** Current schema version for the analysis.v1 contract. */
  public static final String SCHEMA_VERSION = "1.0.0";

  /** Schema version for backward compatibility and contract negotiation (required). */
  @JsonProperty("schemaVersion")
  private String schemaVersion = SCHEMA_VERSION;

  /** The question or prompt to ask the analyzer (required). */
  @JsonProperty("prompt")
  private String prompt;

  /** Additional context about the failure or issue (optional). */
  @JsonProperty("context")
  private String context;

  /** Bundle of evidence to analyze (required). */
  @JsonProperty("evidence")
  private AnalysisV1EvidenceBundle evidence;

  /** Configuration options for the analysis (optional). */
  @JsonProperty("config")
  private AnalysisV1Config config;

  public AnalysisV1Request() {
    this.schemaVersion = SCHEMA_VERSION;
  }

  public AnalysisV1Request(String prompt, AnalysisV1EvidenceBundle evidence) {
    this.schemaVersion = SCHEMA_VERSION;
    this.prompt = prompt;
    this.evidence = evidence;
  }

  public AnalysisV1Request(
      String prompt, String context, AnalysisV1EvidenceBundle evidence, AnalysisV1Config config) {
    this.schemaVersion = SCHEMA_VERSION;
    this.prompt = prompt;
    this.context = context;
    this.evidence = evidence;
    this.config = config;
  }

  public String getSchemaVersion() {
    return schemaVersion != null ? schemaVersion : SCHEMA_VERSION;
  }

  public void setSchemaVersion(String schemaVersion) {
    this.schemaVersion = schemaVersion;
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

  public AnalysisV1EvidenceBundle getEvidence() {
    return evidence;
  }

  public void setEvidence(AnalysisV1EvidenceBundle evidence) {
    this.evidence = evidence;
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
    AnalysisV1Request that = (AnalysisV1Request) o;
    return Objects.equals(schemaVersion, that.schemaVersion)
        && Objects.equals(prompt, that.prompt)
        && Objects.equals(context, that.context)
        && Objects.equals(evidence, that.evidence)
        && Objects.equals(config, that.config);
  }

  @Override
  public int hashCode() {
    return Objects.hash(schemaVersion, prompt, context, evidence, config);
  }

  @Override
  public String toString() {
    return "AnalysisV1Request{"
        + "schemaVersion='"
        + schemaVersion
        + '\''
        + ", prompt='"
        + prompt
        + '\''
        + ", context='"
        + context
        + '\''
        + ", evidence="
        + evidence
        + ", config="
        + config
        + '}';
  }
}

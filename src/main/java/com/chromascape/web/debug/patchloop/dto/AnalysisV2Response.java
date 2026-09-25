package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Response from analysis.v2 contract.
 *
 * <p>Breaking change from V1: adds patchDraft, retestPlan, impactedModules, and evidenceReferences
 * as required fields for SUCCESS status. Diagnosis is now a structured object (AnalysisV2Diagnosis)
 * rather than a nested class.
 *
 * <p>V1 compatibility: diagnosis preserves summary, rootCause, confidence, recommendations from V1.
 *
 * @since 2.0.0
 */
public class AnalysisV2Response {

  /** Current schema version for the analysis.v2 contract. */
  public static final String SCHEMA_VERSION = "2.0.0";

  /** Schema version for contract negotiation. */
  @JsonProperty("schemaVersion")
  private String schemaVersion = SCHEMA_VERSION;

  /** Unique identifier for this analysis. */
  @JsonProperty("analysisId")
  private String analysisId;

  /** Timestamp when the analysis was performed. */
  @JsonProperty("timestamp")
  private LocalDateTime timestamp;

  /** Status of the analysis operation. */
  @JsonProperty("status")
  private AnalysisStatus status;

  /** Structured diagnosis (present if status is SUCCESS or PARTIAL_SUCCESS). */
  @JsonProperty("diagnosis")
  private AnalysisV2Diagnosis diagnosis;

  /** List of modules/components impacted by this issue (required for SUCCESS). */
  @JsonProperty("impactedModules")
  private List<String> impactedModules;

  /** Structured patch draft (required for SUCCESS). */
  @JsonProperty("patchDraft")
  private AnalysisV2PatchDraft patchDraft;

  /** Retest plan for validating the patch (required for SUCCESS). */
  @JsonProperty("retestPlan")
  private AnalysisV2RetestPlan retestPlan;

  /** References to evidence supporting this analysis (required for SUCCESS). */
  @JsonProperty("evidenceReferences")
  private List<String> evidenceReferences;

  /** Error information (present if status is FAILURE). */
  @JsonProperty("error")
  private AnalysisV1Error error;

  /** Metadata about the analysis execution. */
  @JsonProperty("metadata")
  private AnalysisMetadata metadata;

  public AnalysisV2Response() {
    this.schemaVersion = SCHEMA_VERSION;
    this.timestamp = LocalDateTime.now();
    this.impactedModules = new ArrayList<>();
    this.evidenceReferences = new ArrayList<>();
    this.analysisId = UUID.randomUUID().toString();
  }

  public String getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(String schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getAnalysisId() {
    return analysisId;
  }

  public void setAnalysisId(String analysisId) {
    this.analysisId = analysisId;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public AnalysisStatus getStatus() {
    return status;
  }

  public void setStatus(AnalysisStatus status) {
    this.status = status;
  }

  public AnalysisV2Diagnosis getDiagnosis() {
    return diagnosis;
  }

  public void setDiagnosis(AnalysisV2Diagnosis diagnosis) {
    this.diagnosis = diagnosis;
  }

  public List<String> getImpactedModules() {
    return impactedModules != null ? impactedModules : new ArrayList<>();
  }

  public void setImpactedModules(List<String> impactedModules) {
    this.impactedModules = impactedModules;
  }

  public AnalysisV2PatchDraft getPatchDraft() {
    return patchDraft;
  }

  public void setPatchDraft(AnalysisV2PatchDraft patchDraft) {
    this.patchDraft = patchDraft;
  }

  public AnalysisV2RetestPlan getRetestPlan() {
    return retestPlan;
  }

  public void setRetestPlan(AnalysisV2RetestPlan retestPlan) {
    this.retestPlan = retestPlan;
  }

  public List<String> getEvidenceReferences() {
    return evidenceReferences != null ? evidenceReferences : new ArrayList<>();
  }

  public void setEvidenceReferences(List<String> evidenceReferences) {
    this.evidenceReferences = evidenceReferences;
  }

  public AnalysisV1Error getError() {
    return error;
  }

  public void setError(AnalysisV1Error error) {
    this.error = error;
  }

  public AnalysisMetadata getMetadata() {
    return metadata;
  }

  public void setMetadata(AnalysisMetadata metadata) {
    this.metadata = metadata;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AnalysisV2Response that = (AnalysisV2Response) o;
    return Objects.equals(schemaVersion, that.schemaVersion)
        && Objects.equals(analysisId, that.analysisId)
        && Objects.equals(timestamp, that.timestamp)
        && status == that.status
        && Objects.equals(diagnosis, that.diagnosis)
        && Objects.equals(impactedModules, that.impactedModules)
        && Objects.equals(patchDraft, that.patchDraft)
        && Objects.equals(retestPlan, that.retestPlan)
        && Objects.equals(evidenceReferences, that.evidenceReferences)
        && Objects.equals(error, that.error)
        && Objects.equals(metadata, that.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        schemaVersion,
        analysisId,
        timestamp,
        status,
        diagnosis,
        impactedModules,
        patchDraft,
        retestPlan,
        evidenceReferences,
        error,
        metadata);
  }

  @Override
  public String toString() {
    return "AnalysisV2Response{"
        + "schemaVersion='"
        + schemaVersion
        + '\''
        + ", analysisId='"
        + analysisId
        + '\''
        + ", timestamp="
        + timestamp
        + ", status="
        + status
        + ", diagnosis="
        + diagnosis
        + ", impactedModules="
        + (impactedModules != null ? impactedModules.size() : 0)
        + ", patchDraft="
        + patchDraft
        + ", retestPlan="
        + retestPlan
        + ", evidenceReferences="
        + (evidenceReferences != null ? evidenceReferences.size() : 0)
        + ", error="
        + error
        + ", metadata="
        + metadata
        + '}';
  }

  /** Metadata about the analysis execution (reused from V1). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class AnalysisMetadata {

    /** Model used for analysis. */
    @JsonProperty("model")
    private String model;

    /** Time taken to perform analysis (milliseconds). */
    @JsonProperty("analysisTimeMs")
    private Long analysisTimeMs;

    /** Number of evidence items processed. */
    @JsonProperty("evidenceCount")
    private Integer evidenceCount;

    /** API tokens used (if applicable). */
    @JsonProperty("tokensUsed")
    private Integer tokensUsed;

    /** Provider identifier used for analysis. */
    @JsonProperty("provider")
    private String provider;

    /** Additional metadata fields. */
    @JsonProperty("extra")
    private Map<String, Object> extra;

    public AnalysisMetadata() {
      this.extra = new HashMap<>();
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public Long getAnalysisTimeMs() {
      return analysisTimeMs;
    }

    public void setAnalysisTimeMs(Long analysisTimeMs) {
      this.analysisTimeMs = analysisTimeMs;
    }

    public Integer getEvidenceCount() {
      return evidenceCount;
    }

    public void setEvidenceCount(Integer evidenceCount) {
      this.evidenceCount = evidenceCount;
    }

    public Integer getTokensUsed() {
      return tokensUsed;
    }

    public void setTokensUsed(Integer tokensUsed) {
      this.tokensUsed = tokensUsed;
    }

    public String getProvider() {
      return provider;
    }

    public void setProvider(String provider) {
      this.provider = provider;
    }

    public Map<String, Object> getExtra() {
      return extra != null ? extra : new HashMap<>();
    }

    public void setExtra(Map<String, Object> extra) {
      this.extra = extra;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      AnalysisMetadata that = (AnalysisMetadata) o;
      return Objects.equals(model, that.model)
          && Objects.equals(analysisTimeMs, that.analysisTimeMs)
          && Objects.equals(evidenceCount, that.evidenceCount)
          && Objects.equals(tokensUsed, that.tokensUsed)
          && Objects.equals(provider, that.provider)
          && Objects.equals(extra, that.extra);
    }

    @Override
    public int hashCode() {
      return Objects.hash(model, analysisTimeMs, evidenceCount, tokensUsed, provider, extra);
    }

    @Override
    public String toString() {
      return "AnalysisMetadata{"
          + "model='"
          + model
          + '\''
          + ", analysisTimeMs="
          + analysisTimeMs
          + ", evidenceCount="
          + evidenceCount
          + ", tokensUsed="
          + tokensUsed
          + ", provider='"
          + provider
          + '\''
          + ", extra="
          + extra
          + '}';
    }
  }
}

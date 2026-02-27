package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Response from analysis.v1 contract.
 *
 * <p>Contains either a diagnosis (on success) or an error (on failure).
 */
public class AnalysisV1Response {

  /** Current schema version for the analysis.v1 contract. */
  public static final String SCHEMA_VERSION = "1.0.0";

  /** Schema version for backward compatibility and contract negotiation. */
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

  /** Diagnosis section (present if status is SUCCESS or PARTIAL_SUCCESS). */
  @JsonProperty("diagnosis")
  private DiagnosisSection diagnosis;

  /** Error information (present if status is FAILURE). */
  @JsonProperty("error")
  private AnalysisV1Error error;

  /** Metadata about the analysis execution. */
  @JsonProperty("metadata")
  private AnalysisMetadata metadata;

  public AnalysisV1Response() {
    this.schemaVersion = SCHEMA_VERSION;
    this.timestamp = LocalDateTime.now();
  }

  public String getSchemaVersion() {
    return schemaVersion != null ? schemaVersion : SCHEMA_VERSION;
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

  public DiagnosisSection getDiagnosis() {
    return diagnosis;
  }

  public void setDiagnosis(DiagnosisSection diagnosis) {
    this.diagnosis = diagnosis;
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
    AnalysisV1Response that = (AnalysisV1Response) o;
    return Objects.equals(schemaVersion, that.schemaVersion)
        && Objects.equals(analysisId, that.analysisId)
        && Objects.equals(timestamp, that.timestamp)
        && status == that.status
        && Objects.equals(diagnosis, that.diagnosis)
        && Objects.equals(error, that.error)
        && Objects.equals(metadata, that.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(schemaVersion, analysisId, timestamp, status, diagnosis, error, metadata);
  }

  @Override
  public String toString() {
    return "AnalysisV1Response{"
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
        + ", error="
        + error
        + ", metadata="
        + metadata
        + '}';
  }

  /** Diagnosis section containing the analysis results. */
  public static class DiagnosisSection {

    /** Brief summary of the diagnosis (1-2 sentences). */
    @JsonProperty("summary")
    private String summary;

    /** Detailed explanation of the root cause. */
    @JsonProperty("rootCause")
    private String rootCause;

    /** Confidence level in this diagnosis. */
    @JsonProperty("confidence")
    private ConfidenceLevel confidence;

    /** List of recommended fixes. */
    @JsonProperty("recommendations")
    private List<FixRecommendation> recommendations;

    /** Trail of evidence that supports this diagnosis. */
    @JsonProperty("evidenceTrail")
    private List<EvidenceReference> evidenceTrail;

    public DiagnosisSection() {
      this.recommendations = new ArrayList<>();
      this.evidenceTrail = new ArrayList<>();
    }

    public String getSummary() {
      return summary;
    }

    public void setSummary(String summary) {
      this.summary = summary;
    }

    public String getRootCause() {
      return rootCause;
    }

    public void setRootCause(String rootCause) {
      this.rootCause = rootCause;
    }

    public ConfidenceLevel getConfidence() {
      return confidence;
    }

    public void setConfidence(ConfidenceLevel confidence) {
      this.confidence = confidence;
    }

    public List<FixRecommendation> getRecommendations() {
      return recommendations != null ? recommendations : new ArrayList<>();
    }

    public void setRecommendations(List<FixRecommendation> recommendations) {
      this.recommendations = recommendations;
    }

    public List<EvidenceReference> getEvidenceTrail() {
      return evidenceTrail != null ? evidenceTrail : new ArrayList<>();
    }

    public void setEvidenceTrail(List<EvidenceReference> evidenceTrail) {
      this.evidenceTrail = evidenceTrail;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      DiagnosisSection that = (DiagnosisSection) o;
      return Objects.equals(summary, that.summary)
          && Objects.equals(rootCause, that.rootCause)
          && confidence == that.confidence
          && Objects.equals(recommendations, that.recommendations)
          && Objects.equals(evidenceTrail, that.evidenceTrail);
    }

    @Override
    public int hashCode() {
      return Objects.hash(summary, rootCause, confidence, recommendations, evidenceTrail);
    }

    @Override
    public String toString() {
      return "DiagnosisSection{"
          + "summary='"
          + summary
          + '\''
          + ", rootCause='"
          + rootCause
          + '\''
          + ", confidence="
          + confidence
          + ", recommendations="
          + recommendations.size()
          + ", evidenceTrail="
          + evidenceTrail.size()
          + '}';
    }
  }

  /** A recommended fix with implementation details. */
  public static class FixRecommendation {

    /** Priority rank (1 = highest priority). */
    @JsonProperty("priority")
    private Integer priority;

    /** Short title of the recommendation. */
    @JsonProperty("title")
    private String title;

    /** Detailed description of what to change. */
    @JsonProperty("description")
    private String description;

    /** Confidence level in this recommendation. */
    @JsonProperty("confidence")
    private ConfidenceLevel confidence;

    /** File paths or code locations to modify. */
    @JsonProperty("affectedFiles")
    private List<String> affectedFiles;

    /** Expected impact of implementing this fix. */
    @JsonProperty("expectedImpact")
    private String expectedImpact;

    public FixRecommendation() {
      this.affectedFiles = new ArrayList<>();
    }

    public Integer getPriority() {
      return priority;
    }

    public void setPriority(Integer priority) {
      this.priority = priority;
    }

    public String getTitle() {
      return title;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public ConfidenceLevel getConfidence() {
      return confidence;
    }

    public void setConfidence(ConfidenceLevel confidence) {
      this.confidence = confidence;
    }

    public List<String> getAffectedFiles() {
      return affectedFiles != null ? affectedFiles : new ArrayList<>();
    }

    public void setAffectedFiles(List<String> affectedFiles) {
      this.affectedFiles = affectedFiles;
    }

    public String getExpectedImpact() {
      return expectedImpact;
    }

    public void setExpectedImpact(String expectedImpact) {
      this.expectedImpact = expectedImpact;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      FixRecommendation that = (FixRecommendation) o;
      return Objects.equals(priority, that.priority)
          && Objects.equals(title, that.title)
          && Objects.equals(description, that.description)
          && confidence == that.confidence
          && Objects.equals(affectedFiles, that.affectedFiles)
          && Objects.equals(expectedImpact, that.expectedImpact);
    }

    @Override
    public int hashCode() {
      return Objects.hash(priority, title, description, confidence, affectedFiles, expectedImpact);
    }

    @Override
    public String toString() {
      return "FixRecommendation{"
          + "priority="
          + priority
          + ", title='"
          + title
          + '\''
          + ", description='"
          + description
          + '\''
          + ", confidence="
          + confidence
          + ", affectedFiles="
          + affectedFiles
          + ", expectedImpact='"
          + expectedImpact
          + '\''
          + '}';
    }
  }

  /** Reference to a specific piece of evidence. */
  public static class EvidenceReference {

    /** Type of evidence. */
    @JsonProperty("type")
    private EvidenceType type;

    /** Description of what this evidence shows. */
    @JsonProperty("description")
    private String description;

    /** Timestamp or tick number for the evidence. */
    @JsonProperty("timestamp")
    private String timestamp;

    /** Excerpt from the evidence (log message, decision context, etc.). */
    @JsonProperty("excerpt")
    private String excerpt;

    public EvidenceReference() {}

    public EvidenceType getType() {
      return type;
    }

    public void setType(EvidenceType type) {
      this.type = type;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public String getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(String timestamp) {
      this.timestamp = timestamp;
    }

    public String getExcerpt() {
      return excerpt;
    }

    public void setExcerpt(String excerpt) {
      this.excerpt = excerpt;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      EvidenceReference that = (EvidenceReference) o;
      return type == that.type
          && Objects.equals(description, that.description)
          && Objects.equals(timestamp, that.timestamp)
          && Objects.equals(excerpt, that.excerpt);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, description, timestamp, excerpt);
    }

    @Override
    public String toString() {
      return "EvidenceReference{"
          + "type="
          + type
          + ", description='"
          + description
          + '\''
          + ", timestamp='"
          + timestamp
          + '\''
          + ", excerpt='"
          + excerpt
          + '\''
          + '}';
    }
  }

  /** Metadata about the analysis execution. */
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
          && Objects.equals(extra, that.extra);
    }

    @Override
    public int hashCode() {
      return Objects.hash(model, analysisTimeMs, evidenceCount, tokensUsed, extra);
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
          + ", extra="
          + extra
          + '}';
    }
  }
}

package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Structured diagnosis for V2 analysis responses.
 *
 * <p>Preserves V1 semantics (summary, rootCause, confidence, recommendations) while being a
 * structured object rather than nested class. This allows runtime consumers to continue using the
 * same fields without redesign.
 *
 * @since 2.0.0
 */
public class AnalysisV2Diagnosis {

  /** Brief summary of the diagnosis (1-2 sentences). */
  @JsonProperty("summary")
  private String summary;

  /** Detailed explanation of the root cause. */
  @JsonProperty("rootCause")
  private String rootCause;

  /** Confidence level in this diagnosis. */
  @JsonProperty("confidence")
  private ConfidenceLevel confidence;

  /** List of recommended fixes (optional in V2, preserved for V1 compatibility). */
  @JsonProperty("recommendations")
  private List<FixRecommendation> recommendations;

  public AnalysisV2Diagnosis() {
    this.recommendations = new ArrayList<>();
  }

  public AnalysisV2Diagnosis(String summary, String rootCause, ConfidenceLevel confidence) {
    this.summary = summary;
    this.rootCause = rootCause;
    this.confidence = confidence;
    this.recommendations = new ArrayList<>();
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

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AnalysisV2Diagnosis that = (AnalysisV2Diagnosis) o;
    return Objects.equals(summary, that.summary)
        && Objects.equals(rootCause, that.rootCause)
        && confidence == that.confidence
        && Objects.equals(recommendations, that.recommendations);
  }

  @Override
  public int hashCode() {
    return Objects.hash(summary, rootCause, confidence, recommendations);
  }

  @Override
  public String toString() {
    return "AnalysisV2Diagnosis{"
        + "summary='"
        + summary
        + '\''
        + ", rootCause='"
        + rootCause
        + '\''
        + ", confidence="
        + confidence
        + ", recommendations="
        + (recommendations != null ? recommendations.size() : 0)
        + '}';
  }

  /** A recommended fix with implementation details (reused from V1). */
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
}

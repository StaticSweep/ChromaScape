package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Configuration options for analysis.v1 requests.
 *
 * <p>Optional parameters to control analysis behavior.
 */
public class AnalysisV1Config {

  /** Maximum number of recommendations to return (default: 5) */
  @JsonProperty("maxRecommendations")
  private Integer maxRecommendations;

  /** Whether to include detailed evidence trail in response (default: true) */
  @JsonProperty("includeEvidenceTrail")
  private Boolean includeEvidenceTrail;

  /** Minimum confidence level to include recommendations (default: LOW) */
  @JsonProperty("minConfidence")
  private ConfidenceLevel minConfidence;

  public AnalysisV1Config() {
    this.maxRecommendations = 5;
    this.includeEvidenceTrail = true;
    this.minConfidence = ConfidenceLevel.LOW;
  }

  public Integer getMaxRecommendations() {
    return maxRecommendations;
  }

  public void setMaxRecommendations(Integer maxRecommendations) {
    this.maxRecommendations = maxRecommendations;
  }

  public Boolean getIncludeEvidenceTrail() {
    return includeEvidenceTrail;
  }

  public void setIncludeEvidenceTrail(Boolean includeEvidenceTrail) {
    this.includeEvidenceTrail = includeEvidenceTrail;
  }

  public ConfidenceLevel getMinConfidence() {
    return minConfidence;
  }

  public void setMinConfidence(ConfidenceLevel minConfidence) {
    this.minConfidence = minConfidence;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AnalysisV1Config that = (AnalysisV1Config) o;
    return Objects.equals(maxRecommendations, that.maxRecommendations)
        && Objects.equals(includeEvidenceTrail, that.includeEvidenceTrail)
        && minConfidence == that.minConfidence;
  }

  @Override
  public int hashCode() {
    return Objects.hash(maxRecommendations, includeEvidenceTrail, minConfidence);
  }

  @Override
  public String toString() {
    return "AnalysisV1Config{"
        + "maxRecommendations="
        + maxRecommendations
        + ", includeEvidenceTrail="
        + includeEvidenceTrail
        + ", minConfidence="
        + minConfidence
        + '}';
  }
}

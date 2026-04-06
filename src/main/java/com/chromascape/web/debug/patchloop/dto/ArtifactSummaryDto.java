package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Summary of artifacts generated during patch loop execution.
 *
 * <p>Provides counts and references to the most recent artifacts of each type.
 *
 * @since 2.1.0
 */
public class ArtifactSummaryDto {

  @JsonProperty("analysisCount")
  private Integer analysisCount;

  @JsonProperty("patchCount")
  private Integer patchCount;

  @JsonProperty("validationCount")
  private Integer validationCount;

  @JsonProperty("retestCount")
  private Integer retestCount;

  @JsonProperty("evidenceCount")
  private Integer evidenceCount;

  @JsonProperty("latestAnalysisId")
  private String latestAnalysisId;

  @JsonProperty("latestPatchId")
  private String latestPatchId;

  public ArtifactSummaryDto() {}

  public Integer getAnalysisCount() {
    return analysisCount;
  }

  public void setAnalysisCount(Integer analysisCount) {
    this.analysisCount = analysisCount;
  }

  public Integer getPatchCount() {
    return patchCount;
  }

  public void setPatchCount(Integer patchCount) {
    this.patchCount = patchCount;
  }

  public Integer getValidationCount() {
    return validationCount;
  }

  public void setValidationCount(Integer validationCount) {
    this.validationCount = validationCount;
  }

  public Integer getRetestCount() {
    return retestCount;
  }

  public void setRetestCount(Integer retestCount) {
    this.retestCount = retestCount;
  }

  public Integer getEvidenceCount() {
    return evidenceCount;
  }

  public void setEvidenceCount(Integer evidenceCount) {
    this.evidenceCount = evidenceCount;
  }

  public String getLatestAnalysisId() {
    return latestAnalysisId;
  }

  public void setLatestAnalysisId(String latestAnalysisId) {
    this.latestAnalysisId = latestAnalysisId;
  }

  public String getLatestPatchId() {
    return latestPatchId;
  }

  public void setLatestPatchId(String latestPatchId) {
    this.latestPatchId = latestPatchId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ArtifactSummaryDto that = (ArtifactSummaryDto) o;
    return Objects.equals(analysisCount, that.analysisCount)
        && Objects.equals(patchCount, that.patchCount)
        && Objects.equals(validationCount, that.validationCount)
        && Objects.equals(retestCount, that.retestCount)
        && Objects.equals(evidenceCount, that.evidenceCount);
  }

  @Override
  public int hashCode() {
    return Objects.hash(analysisCount, patchCount, validationCount, retestCount, evidenceCount);
  }

  @Override
  public String toString() {
    return "ArtifactSummaryDto{"
        + "analysisCount="
        + analysisCount
        + ", patchCount="
        + patchCount
        + ", validationCount="
        + validationCount
        + ", retestCount="
        + retestCount
        + ", evidenceCount="
        + evidenceCount
        + '}';
  }
}

package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * AI-generated patch proposal.
 *
 * <p>Contains the diff text, rationale, affected files, and metadata for a proposed patch.
 */
public class PatchProposal {

  /** Unique patch ID */
  @JsonProperty("patchId")
  private String patchId;

  /** Unified diff format patch text */
  @JsonProperty("diffText")
  private String diffText;

  /** Rationale explaining why this patch fixes the issue */
  @JsonProperty("rationale")
  private String rationale;

  /** List of affected file paths */
  @JsonProperty("affectedFiles")
  private List<String> affectedFiles;

  /** Number of files changed */
  @JsonProperty("filesChanged")
  private Integer filesChanged;

  /** Number of lines changed (additions + deletions) */
  @JsonProperty("linesChanged")
  private Integer linesChanged;

  /** Confidence level (HIGH/MEDIUM/LOW) */
  @JsonProperty("confidence")
  private String confidence;

  /** Source recommendation that led to this patch */
  @JsonProperty("sourceRecommendation")
  private String sourceRecommendation;

  /** Expected impact of applying this patch */
  @JsonProperty("expectedImpact")
  private String expectedImpact;

  /** Associated diagnosis ID */
  @JsonProperty("diagnosisId")
  private String diagnosisId;

  public PatchProposal() {
    this.affectedFiles = new ArrayList<>();
    this.confidence = "MEDIUM";
  }

  public String getPatchId() {
    return patchId;
  }

  public void setPatchId(String patchId) {
    this.patchId = patchId;
  }

  public String getDiffText() {
    return diffText;
  }

  public void setDiffText(String diffText) {
    this.diffText = diffText;
  }

  public String getRationale() {
    return rationale;
  }

  public void setRationale(String rationale) {
    this.rationale = rationale;
  }

  public List<String> getAffectedFiles() {
    return affectedFiles;
  }

  public void setAffectedFiles(List<String> affectedFiles) {
    this.affectedFiles = affectedFiles;
  }

  public Integer getFilesChanged() {
    return filesChanged;
  }

  public void setFilesChanged(Integer filesChanged) {
    this.filesChanged = filesChanged;
  }

  public Integer getLinesChanged() {
    return linesChanged;
  }

  public void setLinesChanged(Integer linesChanged) {
    this.linesChanged = linesChanged;
  }

  public String getConfidence() {
    return confidence;
  }

  public void setConfidence(String confidence) {
    this.confidence = confidence;
  }

  public String getSourceRecommendation() {
    return sourceRecommendation;
  }

  public void setSourceRecommendation(String sourceRecommendation) {
    this.sourceRecommendation = sourceRecommendation;
  }

  public String getExpectedImpact() {
    return expectedImpact;
  }

  public void setExpectedImpact(String expectedImpact) {
    this.expectedImpact = expectedImpact;
  }

  public String getDiagnosisId() {
    return diagnosisId;
  }

  public void setDiagnosisId(String diagnosisId) {
    this.diagnosisId = diagnosisId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchProposal that = (PatchProposal) o;
    return Objects.equals(patchId, that.patchId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(patchId);
  }

  @Override
  public String toString() {
    return "PatchProposal{"
        + "patchId='"
        + patchId
        + '\''
        + ", filesChanged="
        + filesChanged
        + ", linesChanged="
        + linesChanged
        + ", confidence='"
        + confidence
        + '\''
        + '}';
  }
}

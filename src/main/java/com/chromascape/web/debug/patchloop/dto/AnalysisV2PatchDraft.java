package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Structured patch draft for V2 analysis responses.
 *
 * <p>Contains all information needed to generate a code patch, including affected files, specific
 * changes, confidence, and rationale. This is the primary source for the GENERATE_PATCH node in the
 * autonomous fix loop.
 *
 * @since 2.0.0
 */
public class AnalysisV2PatchDraft {

  /** Unique identifier for this patch draft. */
  @JsonProperty("patchId")
  private String patchId;

  /** Human-readable description of the patch. */
  @JsonProperty("description")
  private String description;

  /** List of file paths affected by this patch. */
  @JsonProperty("affectedFiles")
  private List<String> affectedFiles;

  /** List of specific code changes to apply. */
  @JsonProperty("changes")
  private List<PatchChange> changes;

  /** Confidence level in this patch. */
  @JsonProperty("confidence")
  private ConfidenceLevel confidence;

  /** Rationale for why this patch will fix the issue. */
  @JsonProperty("rationale")
  private String rationale;

  public AnalysisV2PatchDraft() {
    this.affectedFiles = new ArrayList<>();
    this.changes = new ArrayList<>();
  }

  public String getPatchId() {
    return patchId;
  }

  public void setPatchId(String patchId) {
    this.patchId = patchId;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public List<String> getAffectedFiles() {
    return affectedFiles != null ? affectedFiles : new ArrayList<>();
  }

  public void setAffectedFiles(List<String> affectedFiles) {
    this.affectedFiles = affectedFiles;
  }

  public List<PatchChange> getChanges() {
    return changes != null ? changes : new ArrayList<>();
  }

  public void setChanges(List<PatchChange> changes) {
    this.changes = changes;
  }

  public ConfidenceLevel getConfidence() {
    return confidence;
  }

  public void setConfidence(ConfidenceLevel confidence) {
    this.confidence = confidence;
  }

  public String getRationale() {
    return rationale;
  }

  public void setRationale(String rationale) {
    this.rationale = rationale;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AnalysisV2PatchDraft that = (AnalysisV2PatchDraft) o;
    return Objects.equals(patchId, that.patchId)
        && Objects.equals(description, that.description)
        && Objects.equals(affectedFiles, that.affectedFiles)
        && Objects.equals(changes, that.changes)
        && confidence == that.confidence
        && Objects.equals(rationale, that.rationale);
  }

  @Override
  public int hashCode() {
    return Objects.hash(patchId, description, affectedFiles, changes, confidence, rationale);
  }

  @Override
  public String toString() {
    return "AnalysisV2PatchDraft{"
        + "patchId='"
        + patchId
        + '\''
        + ", description='"
        + description
        + '\''
        + ", affectedFiles="
        + affectedFiles.size()
        + ", changes="
        + changes.size()
        + ", confidence="
        + confidence
        + ", rationale='"
        + rationale
        + '\''
        + '}';
  }

  /** A specific code change to apply as part of the patch. */
  public static class PatchChange {

    /** File path to modify. */
    @JsonProperty("filePath")
    private String filePath;

    /** Old code to replace (can be partial match). */
    @JsonProperty("oldText")
    private String oldText;

    /** New code to insert. */
    @JsonProperty("newText")
    private String newText;

    /** Line number where change should be applied (optional). */
    @JsonProperty("lineNumber")
    private Integer lineNumber;

    /** Type of change (optional). */
    @JsonProperty("changeType")
    private ChangeType changeType;

    public PatchChange() {}

    public PatchChange(String filePath, String oldText, String newText) {
      this.filePath = filePath;
      this.oldText = oldText;
      this.newText = newText;
    }

    public String getFilePath() {
      return filePath;
    }

    public void setFilePath(String filePath) {
      this.filePath = filePath;
    }

    public String getOldText() {
      return oldText;
    }

    public void setOldText(String oldText) {
      this.oldText = oldText;
    }

    public String getNewText() {
      return newText;
    }

    public void setNewText(String newText) {
      this.newText = newText;
    }

    public Integer getLineNumber() {
      return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
      this.lineNumber = lineNumber;
    }

    public ChangeType getChangeType() {
      return changeType;
    }

    public void setChangeType(ChangeType changeType) {
      this.changeType = changeType;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      PatchChange that = (PatchChange) o;
      return Objects.equals(filePath, that.filePath)
          && Objects.equals(oldText, that.oldText)
          && Objects.equals(newText, that.newText)
          && Objects.equals(lineNumber, that.lineNumber)
          && changeType == that.changeType;
    }

    @Override
    public int hashCode() {
      return Objects.hash(filePath, oldText, newText, lineNumber, changeType);
    }

    @Override
    public String toString() {
      return "PatchChange{"
          + "filePath='"
          + filePath
          + '\''
          + ", oldText='"
          + (oldText != null ? truncate(oldText, 50) : null)
          + '\''
          + ", newText='"
          + (newText != null ? truncate(newText, 50) : null)
          + '\''
          + ", lineNumber="
          + lineNumber
          + ", changeType="
          + changeType
          + '}';
    }

    private String truncate(String s, int maxLen) {
      if (s == null) return null;
      if (s.length() <= maxLen) return s;
      return s.substring(0, maxLen) + "...";
    }
  }

  /** Type of code change. */
  public enum ChangeType {
    /** Modify existing code */
    MODIFY,

    /** Insert new code */
    INSERT,

    /** Remove existing code */
    DELETE,

    /** Replace entire block */
    REPLACE
  }
}

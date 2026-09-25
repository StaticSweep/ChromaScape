package com.chromascape.web.debug.patchloop.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Entity representing a generated patch artifact.
 *
 * <p>Tracks AI-generated patches including their diff content, rationale, apply status, and
 * validation results.
 */
@Entity
@Table(name = "patch_loop_patch_artifact")
public class PatchLoopPatchArtifact {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Reference to the parent graph execution */
  @Column(name = "execution_id", nullable = false, length = 100)
  private String executionId;

  /** Attempt number this patch was generated for */
  @Column(name = "attempt_number")
  private Integer attemptNumber;

  /** Unique patch ID */
  @Column(name = "patch_id", unique = true, nullable = false, length = 100)
  private String patchId;

  /** Unified diff format patch text */
  @Column(columnDefinition = "TEXT", nullable = false)
  private String diffText;

  /** Rationale explaining why this patch fixes the issue */
  @Column(columnDefinition = "TEXT")
  private String rationale;

  /** Affected files (JSON array) */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode affectedFiles;

  /** Number of files changed */
  @Column(name = "files_changed")
  private Integer filesChanged;

  /** Number of lines changed (additions + deletions) */
  @Column(name = "lines_changed")
  private Integer linesChanged;

  /** Confidence level of this patch (HIGH/MEDIUM/LOW) */
  @Column(length = 20)
  private String confidence;

  /** Current apply status */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private PatchApplyStatus applyStatus;

  /** When this patch was created */
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  /** When this patch was applied */
  @Column(name = "applied_at")
  private LocalDateTime appliedAt;

  /** Git branch name for this patch */
  @Column(name = "git_branch", length = 200)
  private String gitBranch;

  /** Git commit hash for this patch */
  @Column(name = "git_commit", length = 100)
  private String gitCommit;

  /** Whether validation passed */
  @Column(name = "validation_passed")
  private Boolean validationPassed;

  /** Validation output (test results, etc.) */
  @Column(columnDefinition = "TEXT")
  private String validationOutput;

  /** Validation error message if failed */
  @Column(name = "validation_error", columnDefinition = "TEXT")
  private String validationError;

  /** Whether this patch was rolled back */
  @Column(name = "rolled_back")
  private Boolean rolledBack;

  /** Rollback reason */
  @Column(name = "rollback_reason", columnDefinition = "TEXT")
  private String rollbackReason;

  public PatchLoopPatchArtifact() {
    this.createdAt = LocalDateTime.now();
    this.applyStatus = PatchApplyStatus.STAGED;
  }

  /** Marks this patch as applied. */
  public void markApplied(String gitBranch, String gitCommit) {
    this.applyStatus = PatchApplyStatus.APPLIED;
    this.appliedAt = LocalDateTime.now();
    this.gitBranch = gitBranch;
    this.gitCommit = gitCommit;
  }

  /** Marks this patch as failed to apply. */
  public void markApplyFailed(String reason) {
    this.applyStatus = PatchApplyStatus.FAILED;
    this.validationError = reason;
  }

  /** Marks this patch as rolled back. */
  public void markRolledBack(String reason) {
    this.rolledBack = true;
    this.rollbackReason = reason;
    this.applyStatus = PatchApplyStatus.ROLLED_BACK;
  }

  /** Returns true if this patch is currently applied. */
  public boolean isApplied() {
    return applyStatus == PatchApplyStatus.APPLIED;
  }

  /** Returns true if this patch can be applied (not already applied or failed). */
  public boolean canApply() {
    return applyStatus == PatchApplyStatus.STAGED;
  }

  // Getters and setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getExecutionId() {
    return executionId;
  }

  public void setExecutionId(String executionId) {
    this.executionId = executionId;
  }

  public Integer getAttemptNumber() {
    return attemptNumber;
  }

  public void setAttemptNumber(Integer attemptNumber) {
    this.attemptNumber = attemptNumber;
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

  public JsonNode getAffectedFiles() {
    return affectedFiles;
  }

  public void setAffectedFiles(JsonNode affectedFiles) {
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

  public PatchApplyStatus getApplyStatus() {
    return applyStatus;
  }

  public void setApplyStatus(PatchApplyStatus applyStatus) {
    this.applyStatus = applyStatus;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getAppliedAt() {
    return appliedAt;
  }

  public void setAppliedAt(LocalDateTime appliedAt) {
    this.appliedAt = appliedAt;
  }

  public String getGitBranch() {
    return gitBranch;
  }

  public void setGitBranch(String gitBranch) {
    this.gitBranch = gitBranch;
  }

  public String getGitCommit() {
    return gitCommit;
  }

  public void setGitCommit(String gitCommit) {
    this.gitCommit = gitCommit;
  }

  public Boolean getValidationPassed() {
    return validationPassed;
  }

  public void setValidationPassed(Boolean validationPassed) {
    this.validationPassed = validationPassed;
  }

  public String getValidationOutput() {
    return validationOutput;
  }

  public void setValidationOutput(String validationOutput) {
    this.validationOutput = validationOutput;
  }

  public String getValidationError() {
    return validationError;
  }

  public void setValidationError(String validationError) {
    this.validationError = validationError;
  }

  public Boolean getRolledBack() {
    return rolledBack;
  }

  public void setRolledBack(Boolean rolledBack) {
    this.rolledBack = rolledBack;
  }

  public String getRollbackReason() {
    return rollbackReason;
  }

  public void setRollbackReason(String rollbackReason) {
    this.rollbackReason = rollbackReason;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchLoopPatchArtifact that = (PatchLoopPatchArtifact) o;
    return Objects.equals(patchId, that.patchId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(patchId);
  }

  @Override
  public String toString() {
    return "PatchLoopPatchArtifact{"
        + "id="
        + id
        + ", patchId='"
        + patchId
        + '\''
        + ", executionId='"
        + executionId
        + '\''
        + ", applyStatus="
        + applyStatus
        + ", filesChanged="
        + filesChanged
        + ", linesChanged="
        + linesChanged
        + ", confidence='"
        + confidence
        + '\''
        + ", createdAt="
        + createdAt
        + '}';
  }
}

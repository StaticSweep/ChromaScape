package com.chromascape.web.debug.buildergoal.recovery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Immutable canonical patch proposal. Any change to diff, base, pre-images, validation plan, policy
 * version, or expiry produces a new versioned proposal; prior approvals are invalidated.
 */
@Entity
@Table(name = "micro6_patch_proposal")
public class Micro6PatchProposal {
  @Id
  @Column(name = "proposal_id", nullable = false, length = 80)
  private String proposalId;

  @Column(name = "incident_id", nullable = false, length = 80)
  private String incidentId;

  @Column(name = "version", nullable = false)
  private int version;

  @Column(nullable = false, length = 40)
  private String status;

  @Column(name = "source_snapshot_id", nullable = false, length = 80)
  private String sourceSnapshotId;

  @Column(name = "source_manifest_hash", nullable = false, length = 71)
  private String sourceManifestHash;

  @Column(name = "repository_id", nullable = false, length = 160)
  private String repositoryId;

  @Column(name = "base_commit", nullable = false, length = 64)
  private String baseCommit;

  @Column(name = "base_tree", nullable = false, length = 64)
  private String baseTree;

  @Column(name = "base_build_id", nullable = false, length = 160)
  private String baseBuildId;

  @Column(name = "base_artifact_digest", nullable = false, length = 71)
  private String baseArtifactDigest;

  @Column(name = "canonical_diff", nullable = false, columnDefinition = "text")
  private String canonicalDiff;

  @Column(name = "patch_hash", nullable = false, length = 71)
  private String patchHash;

  @Column(name = "proposal_hash", nullable = false, unique = true, length = 71)
  private String proposalHash;

  @Column(name = "affected_files_json", nullable = false, columnDefinition = "text")
  private String affectedFilesJson;

  @Column(name = "preimages_json", nullable = false, columnDefinition = "text")
  private String preimagesJson;

  @Column(name = "citations_json", nullable = false, columnDefinition = "text")
  private String citationsJson;

  @Column(columnDefinition = "text")
  private String rationale;

  @Column(name = "expected_change", columnDefinition = "text")
  private String expectedChange;

  @Column(name = "invariants_json", columnDefinition = "text")
  private String invariantsJson;

  @Column(name = "non_goals_json", columnDefinition = "text")
  private String nonGoalsJson;

  @Column(name = "validation_plan_json", nullable = false, columnDefinition = "text")
  private String validationPlanJson;

  @Column(name = "validation_plan_hash", nullable = false, length = 71)
  private String validationPlanHash;

  @Column(name = "policy_report_json", nullable = false, columnDefinition = "text")
  private String policyReportJson;

  @Column(name = "generator_json", nullable = false, columnDefinition = "text")
  private String generatorJson;

  @Column(name = "prompt_hash", length = 71)
  private String promptHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Version
  @Column(nullable = false)
  private Long entityVersion;

  public String getProposalId() {
    return proposalId;
  }

  public void setProposalId(String value) {
    proposalId = value;
  }

  public String getIncidentId() {
    return incidentId;
  }

  public void setIncidentId(String value) {
    incidentId = value;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int value) {
    version = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    status = value;
  }

  public String getSourceSnapshotId() {
    return sourceSnapshotId;
  }

  public void setSourceSnapshotId(String value) {
    sourceSnapshotId = value;
  }

  public String getSourceManifestHash() {
    return sourceManifestHash;
  }

  public void setSourceManifestHash(String value) {
    sourceManifestHash = value;
  }

  public String getRepositoryId() {
    return repositoryId;
  }

  public void setRepositoryId(String value) {
    repositoryId = value;
  }

  public String getBaseCommit() {
    return baseCommit;
  }

  public void setBaseCommit(String value) {
    baseCommit = value;
  }

  public String getBaseTree() {
    return baseTree;
  }

  public void setBaseTree(String value) {
    baseTree = value;
  }

  public String getBaseBuildId() {
    return baseBuildId;
  }

  public void setBaseBuildId(String value) {
    baseBuildId = value;
  }

  public String getBaseArtifactDigest() {
    return baseArtifactDigest;
  }

  public void setBaseArtifactDigest(String value) {
    baseArtifactDigest = value;
  }

  public String getCanonicalDiff() {
    return canonicalDiff;
  }

  public void setCanonicalDiff(String value) {
    canonicalDiff = value;
  }

  public String getPatchHash() {
    return patchHash;
  }

  public void setPatchHash(String value) {
    patchHash = value;
  }

  public String getProposalHash() {
    return proposalHash;
  }

  public void setProposalHash(String value) {
    proposalHash = value;
  }

  public String getAffectedFilesJson() {
    return affectedFilesJson;
  }

  public void setAffectedFilesJson(String value) {
    affectedFilesJson = value;
  }

  public String getPreimagesJson() {
    return preimagesJson;
  }

  public void setPreimagesJson(String value) {
    preimagesJson = value;
  }

  public String getCitationsJson() {
    return citationsJson;
  }

  public void setCitationsJson(String value) {
    citationsJson = value;
  }

  public String getRationale() {
    return rationale;
  }

  public void setRationale(String value) {
    rationale = value;
  }

  public String getExpectedChange() {
    return expectedChange;
  }

  public void setExpectedChange(String value) {
    expectedChange = value;
  }

  public String getInvariantsJson() {
    return invariantsJson;
  }

  public void setInvariantsJson(String value) {
    invariantsJson = value;
  }

  public String getNonGoalsJson() {
    return nonGoalsJson;
  }

  public void setNonGoalsJson(String value) {
    nonGoalsJson = value;
  }

  public String getValidationPlanJson() {
    return validationPlanJson;
  }

  public void setValidationPlanJson(String value) {
    validationPlanJson = value;
  }

  public String getValidationPlanHash() {
    return validationPlanHash;
  }

  public void setValidationPlanHash(String value) {
    validationPlanHash = value;
  }

  public String getPolicyReportJson() {
    return policyReportJson;
  }

  public void setPolicyReportJson(String value) {
    policyReportJson = value;
  }

  public String getGeneratorJson() {
    return generatorJson;
  }

  public void setGeneratorJson(String value) {
    generatorJson = value;
  }

  public String getPromptHash() {
    return promptHash;
  }

  public void setPromptHash(String value) {
    promptHash = value;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant value) {
    expiresAt = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    createdAt = value;
  }
}

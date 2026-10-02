package com.chromascape.web.debug.buildergoal.recovery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Immutable validated candidate produced by a successful isolated validation job. Eligibility after
 * this workflow is BUILD_VALIDATED only; certification and promotion are separate stories.
 */
@Entity
@Table(name = "micro6_candidate_artifact")
public class Micro6CandidateArtifact {
  @Id
  @Column(name = "candidate_id", nullable = false, length = 80)
  private String candidateId;

  @Column(name = "job_id", nullable = false, length = 80)
  private String jobId;

  @Column(name = "incident_id", nullable = false, length = 80)
  private String incidentId;

  @Column(name = "proposal_id", nullable = false, length = 80)
  private String proposalId;

  @Column(name = "candidate_digest", nullable = false, unique = true, length = 71)
  private String candidateDigest;

  @Column(name = "resulting_tree_hash", nullable = false, length = 71)
  private String resultingTreeHash;

  @Column(name = "package_storage_key", nullable = false, unique = true, length = 500)
  private String packageStorageKey;

  @Column(name = "package_sha256", nullable = false, length = 71)
  private String packageSha256;

  @Column(name = "package_size", nullable = false)
  private long packageSize;

  @Column(name = "sbom_storage_key", nullable = false, length = 500)
  private String sbomStorageKey;

  @Column(name = "sbom_sha256", nullable = false, length = 71)
  private String sbomSha256;

  @Column(name = "manifest_json", nullable = false, columnDefinition = "text")
  private String manifestJson;

  @Column(name = "manifest_hash", nullable = false, length = 71)
  private String manifestHash;

  @Column(nullable = false, columnDefinition = "text")
  private String signature;

  @Column(name = "signing_key_id", nullable = false, length = 120)
  private String signingKeyId;

  @Column(name = "validation_report_json", nullable = false, columnDefinition = "text")
  private String validationReportJson;

  @Column(name = "eligibility_state", nullable = false, length = 40)
  private String eligibilityState;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "quarantined_at")
  private Instant quarantinedAt;

  public String getCandidateId() {
    return candidateId;
  }

  public void setCandidateId(String value) {
    candidateId = value;
  }

  public String getJobId() {
    return jobId;
  }

  public void setJobId(String value) {
    jobId = value;
  }

  public String getIncidentId() {
    return incidentId;
  }

  public void setIncidentId(String value) {
    incidentId = value;
  }

  public String getProposalId() {
    return proposalId;
  }

  public void setProposalId(String value) {
    proposalId = value;
  }

  public String getCandidateDigest() {
    return candidateDigest;
  }

  public void setCandidateDigest(String value) {
    candidateDigest = value;
  }

  public String getResultingTreeHash() {
    return resultingTreeHash;
  }

  public void setResultingTreeHash(String value) {
    resultingTreeHash = value;
  }

  public String getPackageStorageKey() {
    return packageStorageKey;
  }

  public void setPackageStorageKey(String value) {
    packageStorageKey = value;
  }

  public String getPackageSha256() {
    return packageSha256;
  }

  public void setPackageSha256(String value) {
    packageSha256 = value;
  }

  public long getPackageSize() {
    return packageSize;
  }

  public void setPackageSize(long value) {
    packageSize = value;
  }

  public String getSbomStorageKey() {
    return sbomStorageKey;
  }

  public void setSbomStorageKey(String value) {
    sbomStorageKey = value;
  }

  public String getSbomSha256() {
    return sbomSha256;
  }

  public void setSbomSha256(String value) {
    sbomSha256 = value;
  }

  public String getManifestJson() {
    return manifestJson;
  }

  public void setManifestJson(String value) {
    manifestJson = value;
  }

  public String getManifestHash() {
    return manifestHash;
  }

  public void setManifestHash(String value) {
    manifestHash = value;
  }

  public String getSignature() {
    return signature;
  }

  public void setSignature(String value) {
    signature = value;
  }

  public String getSigningKeyId() {
    return signingKeyId;
  }

  public void setSigningKeyId(String value) {
    signingKeyId = value;
  }

  public String getValidationReportJson() {
    return validationReportJson;
  }

  public void setValidationReportJson(String value) {
    validationReportJson = value;
  }

  public String getEligibilityState() {
    return eligibilityState;
  }

  public void setEligibilityState(String value) {
    eligibilityState = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    createdAt = value;
  }

  public Instant getQuarantinedAt() {
    return quarantinedAt;
  }

  public void setQuarantinedAt(Instant value) {
    quarantinedAt = value;
  }
}

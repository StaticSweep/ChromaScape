package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "patch_candidate_runs")
public class PatchCandidateRun {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Version private Long version;
  @Column(name = "proposal_id", nullable = false, unique = true, length = 160) private String proposalId;
  @Column(nullable = false, length = 40) private String state = "FAIL_SELECTED";
  @Column(name = "failure_manifest_hash", nullable = false, length = 71) private String failureManifestHash;
  @Column(name = "source_snapshot_id", nullable = false, length = 40) private String sourceSnapshotId;
  @Column(name = "source_manifest_hash", nullable = false, length = 71) private String sourceManifestHash;
  @Lob @Column(name = "source_manifest_json", nullable = false) private String sourceManifestJson;
  @Column(name = "source_file_id", nullable = false, length = 80) private String sourceFileId;
  @Column(name = "source_relative_path", nullable = false, length = 500) private String sourceRelativePath;
  @Column(name = "start_line", nullable = false) private int startLine;
  @Column(name = "end_line", nullable = false) private int endLine;
  @Column(name = "preimage_hash", nullable = false, length = 71) private String preimageHash;
  @Column(name = "base_commit", nullable = false, length = 160) private String baseCommit;
  @Lob @Column(name = "patch_text", nullable = false) private String patchText;
  @Lob @Column(name = "binding_json", nullable = false) private String bindingJson;
  @Lob @Column(name = "identity_json") private String identityJson;
  @Column(name = "patch_hash", nullable = false, length = 71) private String patchHash;
  @Column(name = "proposal_hash", nullable = false, length = 71) private String proposalHash;
  private String approverId;
  private Instant approvedAt;
  @Column(name = "approval_hash", length = 71) private String approvalHash;
  @Column(name = "candidate_id", length = 40) private String candidateId;
  @Column(name = "candidate_hash", length = 71) private String candidateHash;
  @Lob @Column(name = "candidate_manifest_json") private String candidateManifestJson;
  @Lob @Column(name = "final_identity_json") private String finalIdentityJson;
  @Lob private String resultJson;
  @Column(name = "worker_started_at") private Instant workerStartedAt;
  @Column(name = "worker_completed_at") private Instant workerCompletedAt;
  @Column(name = "candidate_staging_directory", length = 500) private String candidateStagingDirectory;
  @Column(name = "registration_request_hash", length = 71) private String registrationRequestHash;
  @Lob private String registryResponseJson;
  @Column(name = "last_failure_code", length = 160) private String lastFailureCode;
  @Column(name = "worker_attempt_id", length = 80) private String workerAttemptId;
  @Column(name = "worker_lease_expires_at") private Instant workerLeaseExpiresAt;
  @Column(name = "registration_attempt_id", length = 80) private String registrationAttemptId;
  @Column(name = "registration_prepared_at") private Instant registrationPreparedAt;
  @Column(name = "registration_lease_expires_at") private Instant registrationLeaseExpiresAt;
  @Column(name = "registry_reconciled_at") private Instant registryReconciledAt;
  @Column(name = "manifest_file_hash", length = 71) private String manifestFileHash;
  @Column(name = "jar_sha256", length = 71) private String jarSha256;
  @Column(name = "jar_size") private Long jarSize;
  @Column(name = "sbom_sha256", length = 71) private String sbomSha256;
  @Column(name = "sbom_size") private Long sbomSize;
  @Column(name = "certification_document_hash", length = 71) private String certificationDocumentHash;
  @Column(name = "registry_response_hash", length = 71) private String registryResponseHash;
  @Lob @Column(name = "registry_response_bytes", columnDefinition = "BYTEA") private byte[] registryResponseBytes;
  @Column(name = "registry_manifest_hash", length = 71) private String registryManifestHash;
  @Column(name = "registry_download_hash", length = 71) private String registryDownloadHash;
  @Column(name = "registry_verified_at") private Instant registryVerifiedAt;
  @Column(nullable = false) private Instant updatedAt = Instant.now();

  public Long getId() { return id; }
  public String getProposalId() { return proposalId; }
  public void setProposalId(String value) { proposalId = value; }
  public String getState() { return state; }
  public void setState(String value) { state = value; }
  public String getFailureManifestHash() { return failureManifestHash; }
  public void setFailureManifestHash(String value) { failureManifestHash = value; }
  public String getSourceSnapshotId() { return sourceSnapshotId; }
  public void setSourceSnapshotId(String value) { sourceSnapshotId = value; }
  public String getSourceManifestHash() { return sourceManifestHash; }
  public void setSourceManifestHash(String value) { sourceManifestHash = value; }
  public String getSourceManifestJson() { return sourceManifestJson; }
  public void setSourceManifestJson(String value) { sourceManifestJson = value; }
  public String getSourceFileId() { return sourceFileId; }
  public void setSourceFileId(String value) { sourceFileId = value; }
  public String getSourceRelativePath() { return sourceRelativePath; }
  public void setSourceRelativePath(String value) { sourceRelativePath = value; }
  public int getStartLine() { return startLine; }
  public void setStartLine(int value) { startLine = value; }
  public int getEndLine() { return endLine; }
  public void setEndLine(int value) { endLine = value; }
  public String getPreimageHash() { return preimageHash; }
  public void setPreimageHash(String value) { preimageHash = value; }
  public String getBaseCommit() { return baseCommit; }
  public void setBaseCommit(String value) { baseCommit = value; }
  public String getPatchText() { return patchText; }
  public void setPatchText(String value) { patchText = value; }
  public String getBindingJson() { return bindingJson; }
  public void setBindingJson(String value) { bindingJson = value; }
  public String getIdentityJson() { return identityJson; }
  public void setIdentityJson(String value) { identityJson = value; }
  public String getPatchHash() { return patchHash; }
  public void setPatchHash(String value) { patchHash = value; }
  public String getProposalHash() { return proposalHash; }
  public void setProposalHash(String value) { proposalHash = value; }
  public String getApproverId() { return approverId; }
  public void setApproverId(String value) { approverId = value; }
  public Instant getApprovedAt() { return approvedAt; }
  public void setApprovedAt(Instant value) { approvedAt = value; }
  public String getApprovalHash() { return approvalHash; }
  public void setApprovalHash(String value) { approvalHash = value; }
  public String getCandidateId() { return candidateId; }
  public void setCandidateId(String value) { candidateId = value; }
  public String getCandidateHash() { return candidateHash; }
  public void setCandidateHash(String value) { candidateHash = value; }
  public String getCandidateManifestJson() { return candidateManifestJson; }
  public void setCandidateManifestJson(String value) { candidateManifestJson = value; }
  public String getFinalIdentityJson() { return finalIdentityJson; }
  public void setFinalIdentityJson(String value) { finalIdentityJson = value; }
  public String getResultJson() { return resultJson; }
  public void setResultJson(String value) { resultJson = value; updatedAt = Instant.now(); }
  public Instant getWorkerStartedAt() { return workerStartedAt; }
  public void setWorkerStartedAt(Instant value) { workerStartedAt = value; }
  public Instant getWorkerCompletedAt() { return workerCompletedAt; }
  public void setWorkerCompletedAt(Instant value) { workerCompletedAt = value; }
  public String getCandidateStagingDirectory() { return candidateStagingDirectory; }
  public void setCandidateStagingDirectory(String value) { candidateStagingDirectory = value; }
  public String getRegistrationRequestHash() { return registrationRequestHash; }
  public void setRegistrationRequestHash(String value) { registrationRequestHash = value; }
  public String getRegistryResponseJson() { return registryResponseJson; }
  public void setRegistryResponseJson(String value) { registryResponseJson = value; }
  public String getLastFailureCode() { return lastFailureCode; }
  public void setLastFailureCode(String value) { lastFailureCode = value; }
  public String getWorkerAttemptId() { return workerAttemptId; }
  public void setWorkerAttemptId(String value) { workerAttemptId = value; }
  public Instant getWorkerLeaseExpiresAt() { return workerLeaseExpiresAt; }
  public void setWorkerLeaseExpiresAt(Instant value) { workerLeaseExpiresAt = value; }
  public String getRegistrationAttemptId() { return registrationAttemptId; }
  public void setRegistrationAttemptId(String value) { registrationAttemptId = value; }
  public Instant getRegistrationPreparedAt() { return registrationPreparedAt; }
  public void setRegistrationPreparedAt(Instant value) { registrationPreparedAt = value; }
  public Instant getRegistrationLeaseExpiresAt() { return registrationLeaseExpiresAt; }
  public void setRegistrationLeaseExpiresAt(Instant value) { registrationLeaseExpiresAt = value; }
  public Instant getRegistryReconciledAt() { return registryReconciledAt; }
  public void setRegistryReconciledAt(Instant value) { registryReconciledAt = value; }
  public String getManifestFileHash() { return manifestFileHash; }
  public void setManifestFileHash(String value) { manifestFileHash = value; }
  public String getJarSha256() { return jarSha256; }
  public void setJarSha256(String value) { jarSha256 = value; }
  public Long getJarSize() { return jarSize; }
  public void setJarSize(Long value) { jarSize = value; }
  public String getSbomSha256() { return sbomSha256; }
  public void setSbomSha256(String value) { sbomSha256 = value; }
  public Long getSbomSize() { return sbomSize; }
  public void setSbomSize(Long value) { sbomSize = value; }
  public String getCertificationDocumentHash() { return certificationDocumentHash; }
  public void setCertificationDocumentHash(String value) { certificationDocumentHash = value; }
  public String getRegistryResponseHash() { return registryResponseHash; }
  public void setRegistryResponseHash(String value) { registryResponseHash = value; }
  public byte[] getRegistryResponseBytes() { return registryResponseBytes == null ? null : registryResponseBytes.clone(); }
  public void setRegistryResponseBytes(byte[] value) { registryResponseBytes = value == null ? null : value.clone(); }
  public String getRegistryManifestHash() { return registryManifestHash; }
  public void setRegistryManifestHash(String value) { registryManifestHash = value; }
  public String getRegistryDownloadHash() { return registryDownloadHash; }
  public void setRegistryDownloadHash(String value) { registryDownloadHash = value; }
  public Instant getRegistryVerifiedAt() { return registryVerifiedAt; }
  public void setRegistryVerifiedAt(Instant value) { registryVerifiedAt = value; }
  public Instant getUpdatedAt() { return updatedAt; }
}

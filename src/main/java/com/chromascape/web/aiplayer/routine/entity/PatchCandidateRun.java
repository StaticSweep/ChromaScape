package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "patch_candidate_runs")
public class PatchCandidateRun {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "proposal_id", nullable = false, unique = true, length = 160) private String proposalId;
  @Column(nullable = false, length = 40) private String state = "FAIL_SELECTED";
  @Column(name = "failure_manifest_hash", nullable = false, length = 71) private String failureManifestHash;
  @Column(name = "source_snapshot_id", nullable = false, length = 40) private String sourceSnapshotId;
  @Column(name = "source_manifest_hash", nullable = false, length = 71) private String sourceManifestHash;
  @Column(name = "source_file_id", nullable = false, length = 80) private String sourceFileId;
  @Column(name = "source_relative_path", nullable = false, length = 500) private String sourceRelativePath;
  @Column(name = "start_line", nullable = false) private int startLine;
  @Column(name = "end_line", nullable = false) private int endLine;
  @Column(name = "preimage_hash", nullable = false, length = 71) private String preimageHash;
  @Column(name = "base_commit", nullable = false, length = 160) private String baseCommit;
  @Lob @Column(name = "patch_text", nullable = false) private String patchText;
  @Lob @Column(name = "binding_json", nullable = false) private String bindingJson;
  @Column(name = "patch_hash", nullable = false, length = 71) private String patchHash;
  @Column(name = "proposal_hash", nullable = false, length = 71) private String proposalHash;
  private String approverId;
  private Instant approvedAt;
  @Column(name = "approval_hash", length = 71) private String approvalHash;
  @Lob private String resultJson;

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
  public String getResultJson() { return resultJson; }
  public void setResultJson(String value) { resultJson = value; }
}

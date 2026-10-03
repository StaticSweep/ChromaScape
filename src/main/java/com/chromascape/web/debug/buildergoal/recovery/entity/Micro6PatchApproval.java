package com.chromascape.web.debug.buildergoal.recovery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Exact human approval receipt bound to a proposal hash, source binding, pre-image set, and
 * validation plan. Only one ACTIVE approval may exist per proposal hash.
 */
@Entity
@Table(name = "micro6_patch_approval")
public class Micro6PatchApproval {
  @Id
  @Column(name = "approval_id", nullable = false, length = 80)
  private String approvalId;

  @Column(name = "proposal_id", nullable = false, length = 80)
  private String proposalId;

  @Column(name = "proposal_hash", nullable = false, length = 71)
  private String proposalHash;

  @Column(name = "source_binding_hash", nullable = false, length = 71)
  private String sourceBindingHash;

  @Column(name = "preimage_set_hash", nullable = false, length = 71)
  private String preimageSetHash;

  @Column(name = "validation_plan_hash", nullable = false, length = 71)
  private String validationPlanHash;

  @Column(name = "actor_id", nullable = false, length = 160)
  private String actorId;

  @Column(name = "actor_role", nullable = false, length = 60)
  private String actorRole;

  @Column(nullable = false, columnDefinition = "text")
  private String reason;

  @Column(nullable = false, length = 60)
  private String environment;

  @Column(name = "policy_version", nullable = false, length = 30)
  private String policyVersion;

  @Column(name = "approved_at", nullable = false)
  private Instant approvedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "approval_hash", nullable = false, length = 71)
  private String approvalHash;

  @Column(nullable = false, length = 30)
  private String status;

  @Version
  @Column(name = "entity_version", nullable = false)
  private Long entityVersion;

  public String getApprovalId() {
    return approvalId;
  }

  public void setApprovalId(String value) {
    approvalId = value;
  }

  public String getProposalId() {
    return proposalId;
  }

  public void setProposalId(String value) {
    proposalId = value;
  }

  public String getProposalHash() {
    return proposalHash;
  }

  public void setProposalHash(String value) {
    proposalHash = value;
  }

  public String getSourceBindingHash() {
    return sourceBindingHash;
  }

  public void setSourceBindingHash(String value) {
    sourceBindingHash = value;
  }

  public String getPreimageSetHash() {
    return preimageSetHash;
  }

  public void setPreimageSetHash(String value) {
    preimageSetHash = value;
  }

  public String getValidationPlanHash() {
    return validationPlanHash;
  }

  public void setValidationPlanHash(String value) {
    validationPlanHash = value;
  }

  public String getActorId() {
    return actorId;
  }

  public void setActorId(String value) {
    actorId = value;
  }

  public String getActorRole() {
    return actorRole;
  }

  public void setActorRole(String value) {
    actorRole = value;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String value) {
    reason = value;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String value) {
    environment = value;
  }

  public String getPolicyVersion() {
    return policyVersion;
  }

  public void setPolicyVersion(String value) {
    policyVersion = value;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }

  public void setApprovedAt(Instant value) {
    approvedAt = value;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant value) {
    expiresAt = value;
  }

  public String getApprovalHash() {
    return approvalHash;
  }

  public void setApprovalHash(String value) {
    approvalHash = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    status = value;
  }
}

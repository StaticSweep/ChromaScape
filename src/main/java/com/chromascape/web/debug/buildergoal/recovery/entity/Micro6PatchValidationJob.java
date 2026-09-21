package com.chromascape.web.debug.buildergoal.recovery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Durable isolated validation job. All worker updates require the current lease token and entity
 * version; only one nonterminal job may exist per input hash.
 */
@Entity
@Table(name = "micro6_patch_validation_job")
public class Micro6PatchValidationJob {
  @Id
  @Column(name = "job_id", nullable = false, length = 80)
  private String jobId;

  @Column(name = "proposal_id", nullable = false, length = 80)
  private String proposalId;

  @Column(name = "approval_id", nullable = false, length = 80)
  private String approvalId;

  @Column(name = "incident_id", nullable = false, length = 80)
  private String incidentId;

  @Column(nullable = false, length = 40)
  private String state;

  @Column(name = "idempotency_key", nullable = false, unique = true, length = 160)
  private String idempotencyKey;

  @Column(name = "input_hash", nullable = false, length = 71)
  private String inputHash;

  @Column(name = "toolchain_hash", nullable = false, length = 71)
  private String toolchainHash;

  @Column(name = "attempt_number", nullable = false)
  private int attemptNumber;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Column(name = "worker_id", length = 160)
  private String workerId;

  @Column(name = "lease_token", length = 80)
  private String leaseToken;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "current_stage", length = 60)
  private String currentStage;

  @Column(name = "stage_started_at")
  private Instant stageStartedAt;

  @Column(name = "cancel_requested_at")
  private Instant cancelRequestedAt;

  @Column(name = "heartbeat_at")
  private Instant heartbeatAt;

  @Column(name = "resulting_tree_hash", length = 71)
  private String resultingTreeHash;

  @Column(name = "candidate_id", length = 80)
  private String candidateId;

  @Column(name = "failure_code", length = 100)
  private String failureCode;

  @Column(name = "failure_detail", length = 500)
  private String failureDetail;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Version private Long version;

  public String getJobId() {
    return jobId;
  }

  public void setJobId(String value) {
    jobId = value;
  }

  public String getProposalId() {
    return proposalId;
  }

  public void setProposalId(String value) {
    proposalId = value;
  }

  public String getApprovalId() {
    return approvalId;
  }

  public void setApprovalId(String value) {
    approvalId = value;
  }

  public String getIncidentId() {
    return incidentId;
  }

  public void setIncidentId(String value) {
    incidentId = value;
  }

  public String getState() {
    return state;
  }

  public void setState(String value) {
    state = value;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String value) {
    idempotencyKey = value;
  }

  public String getInputHash() {
    return inputHash;
  }

  public void setInputHash(String value) {
    inputHash = value;
  }

  public String getToolchainHash() {
    return toolchainHash;
  }

  public void setToolchainHash(String value) {
    toolchainHash = value;
  }

  public int getAttemptNumber() {
    return attemptNumber;
  }

  public void setAttemptNumber(int value) {
    attemptNumber = value;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int value) {
    maxAttempts = value;
  }

  public String getWorkerId() {
    return workerId;
  }

  public void setWorkerId(String value) {
    workerId = value;
  }

  public String getLeaseToken() {
    return leaseToken;
  }

  public void setLeaseToken(String value) {
    leaseToken = value;
  }

  public Instant getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  public void setLeaseExpiresAt(Instant value) {
    leaseExpiresAt = value;
  }

  public String getCurrentStage() {
    return currentStage;
  }

  public void setCurrentStage(String value) {
    currentStage = value;
  }

  public Instant getStageStartedAt() {
    return stageStartedAt;
  }

  public void setStageStartedAt(Instant value) {
    stageStartedAt = value;
  }

  public Instant getCancelRequestedAt() {
    return cancelRequestedAt;
  }

  public void setCancelRequestedAt(Instant value) {
    cancelRequestedAt = value;
  }

  public Instant getHeartbeatAt() {
    return heartbeatAt;
  }

  public void setHeartbeatAt(Instant value) {
    heartbeatAt = value;
  }

  public String getResultingTreeHash() {
    return resultingTreeHash;
  }

  public void setResultingTreeHash(String value) {
    resultingTreeHash = value;
  }

  public String getCandidateId() {
    return candidateId;
  }

  public void setCandidateId(String value) {
    candidateId = value;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public void setFailureCode(String value) {
    failureCode = value;
  }

  public String getFailureDetail() {
    return failureDetail;
  }

  public void setFailureDetail(String value) {
    failureDetail = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    createdAt = value;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant value) {
    startedAt = value;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant value) {
    completedAt = value;
  }

  public Long getVersion() {
    return version;
  }
}

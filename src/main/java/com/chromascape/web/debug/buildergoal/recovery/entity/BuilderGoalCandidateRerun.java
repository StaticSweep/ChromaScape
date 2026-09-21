package com.chromascape.web.debug.buildergoal.recovery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Operator-authorized candidate-bound rerun request and its reconciliation record. */
@Entity
@Table(name = "builder_goal_candidate_rerun")
public class BuilderGoalCandidateRerun {
  @Id
  @Column(name = "rerun_request_id", nullable = false, length = 80)
  private String rerunRequestId;

  @Column(name = "incident_id", nullable = false, length = 80)
  private String incidentId;

  @Column(name = "candidate_id", nullable = false, length = 80)
  private String candidateId;

  @Column(name = "parent_iteration_id", nullable = false, length = 100)
  private String parentIterationId;

  @Column(name = "child_iteration_id", length = 100)
  private String childIterationId;

  @Column(name = "candidate_digest", nullable = false, length = 71)
  private String candidateDigest;

  @Column(name = "scenario_hash", nullable = false, length = 71)
  private String scenarioHash;

  @Column(name = "account_hash", nullable = false, length = 160)
  private String accountHash;

  @Column(name = "machine_uuid", nullable = false, length = 160)
  private String machineUuid;

  @Column(name = "authority_id", nullable = false, length = 160)
  private String authorityId;

  @Column(name = "idempotency_key", nullable = false, length = 160)
  private String idempotencyKey;

  @Column(nullable = false, length = 40)
  private String state;

  @Column(name = "executor_request_id", length = 160)
  private String executorRequestId;

  @Column(name = "reconciliation_json", columnDefinition = "text")
  private String reconciliationJson;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Version
  @Column(name = "entity_version", nullable = false)
  private Long version;

  public String getRerunRequestId() {
    return rerunRequestId;
  }

  public void setRerunRequestId(String value) {
    rerunRequestId = value;
  }

  public String getIncidentId() {
    return incidentId;
  }

  public void setIncidentId(String value) {
    incidentId = value;
  }

  public String getCandidateId() {
    return candidateId;
  }

  public void setCandidateId(String value) {
    candidateId = value;
  }

  public String getParentIterationId() {
    return parentIterationId;
  }

  public void setParentIterationId(String value) {
    parentIterationId = value;
  }

  public String getChildIterationId() {
    return childIterationId;
  }

  public void setChildIterationId(String value) {
    childIterationId = value;
  }

  public String getCandidateDigest() {
    return candidateDigest;
  }

  public void setCandidateDigest(String value) {
    candidateDigest = value;
  }

  public String getScenarioHash() {
    return scenarioHash;
  }

  public void setScenarioHash(String value) {
    scenarioHash = value;
  }

  public String getAccountHash() {
    return accountHash;
  }

  public void setAccountHash(String value) {
    accountHash = value;
  }

  public String getMachineUuid() {
    return machineUuid;
  }

  public void setMachineUuid(String value) {
    machineUuid = value;
  }

  public String getAuthorityId() {
    return authorityId;
  }

  public void setAuthorityId(String value) {
    authorityId = value;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String value) {
    idempotencyKey = value;
  }

  public String getState() {
    return state;
  }

  public void setState(String value) {
    state = value;
  }

  public String getExecutorRequestId() {
    return executorRequestId;
  }

  public void setExecutorRequestId(String value) {
    executorRequestId = value;
  }

  public String getReconciliationJson() {
    return reconciliationJson;
  }

  public void setReconciliationJson(String value) {
    reconciliationJson = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    createdAt = value;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant value) {
    updatedAt = value;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant value) {
    completedAt = value;
  }
}

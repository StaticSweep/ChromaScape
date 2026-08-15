package com.chromascape.web.accounttask.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Dedicated aggregate persistence model keyed by {@code run_id} (SC-572). One row per verified
 * report bundle carries the top-level manifest identity, terminal outcome, aggregate manifest hash
 * and attempt count; separate {@link ScriptStabilizationRun} rows hold per-attempt metadata.
 * {@code GET /runs/{runId}} returns this aggregate; attempt endpoints return only attempt rows.
 */
@Entity
@Table(name = "stabilization_run_aggregate")
public class StabilizationRunAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 100)
  private String runId;

  @Column(length = 100)
  private String planId;

  @Column(length = 200)
  private String planHash;

  @Column(length = 200)
  private String manifestHash;

  @Column(length = 200)
  private String deterministicContentHash;

  @Column(length = 100)
  private String taskKey;

  @Column(length = 200)
  private String settingsHash;

  @Column(length = 100)
  private String buildId;

  @Column(length = 100)
  private String artifactDigest;

  @Column(length = 200)
  private String catalogHash;

  @Column(length = 100)
  private String sourceSnapshotId;

  @Column(length = 100)
  private String micro6InstanceId;

  @Column(length = 20, nullable = false)
  private String outcome;

  @Column(length = 50)
  private String terminalState;

  @Column(nullable = false)
  private Integer attemptCount;

  @Column(nullable = false)
  private Boolean evidenceComplete;

  @Column(length = 50)
  private String tier;

  @Column(length = 50)
  private String environment;

  @Column(nullable = false)
  private LocalDateTime executedAt;

  public StabilizationRunAggregate() {
    this.executedAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public String getPlanId() {
    return planId;
  }

  public void setPlanId(String planId) {
    this.planId = planId;
  }

  public String getPlanHash() {
    return planHash;
  }

  public void setPlanHash(String planHash) {
    this.planHash = planHash;
  }

  public String getManifestHash() {
    return manifestHash;
  }

  public void setManifestHash(String manifestHash) {
    this.manifestHash = manifestHash;
  }

  public String getDeterministicContentHash() {
    return deterministicContentHash;
  }

  public void setDeterministicContentHash(String deterministicContentHash) {
    this.deterministicContentHash = deterministicContentHash;
  }

  public String getTaskKey() {
    return taskKey;
  }

  public void setTaskKey(String taskKey) {
    this.taskKey = taskKey;
  }

  public String getSettingsHash() {
    return settingsHash;
  }

  public void setSettingsHash(String settingsHash) {
    this.settingsHash = settingsHash;
  }

  public String getBuildId() {
    return buildId;
  }

  public void setBuildId(String buildId) {
    this.buildId = buildId;
  }

  public String getArtifactDigest() {
    return artifactDigest;
  }

  public void setArtifactDigest(String artifactDigest) {
    this.artifactDigest = artifactDigest;
  }

  public String getCatalogHash() {
    return catalogHash;
  }

  public void setCatalogHash(String catalogHash) {
    this.catalogHash = catalogHash;
  }

  public String getSourceSnapshotId() {
    return sourceSnapshotId;
  }

  public void setSourceSnapshotId(String sourceSnapshotId) {
    this.sourceSnapshotId = sourceSnapshotId;
  }

  public String getMicro6InstanceId() {
    return micro6InstanceId;
  }

  public void setMicro6InstanceId(String micro6InstanceId) {
    this.micro6InstanceId = micro6InstanceId;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }

  public String getTerminalState() {
    return terminalState;
  }

  public void setTerminalState(String terminalState) {
    this.terminalState = terminalState;
  }

  public Integer getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(Integer attemptCount) {
    this.attemptCount = attemptCount;
  }

  public Boolean getEvidenceComplete() {
    return evidenceComplete;
  }

  public void setEvidenceComplete(Boolean evidenceComplete) {
    this.evidenceComplete = evidenceComplete;
  }

  public String getTier() {
    return tier;
  }

  public void setTier(String tier) {
    this.tier = tier;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  public LocalDateTime getExecutedAt() {
    return executedAt;
  }

  public void setExecutedAt(LocalDateTime executedAt) {
    this.executedAt = executedAt;
  }
}

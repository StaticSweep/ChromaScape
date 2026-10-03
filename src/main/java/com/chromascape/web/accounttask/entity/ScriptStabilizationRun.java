package com.chromascape.web.accounttask.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Single execution row from stabilization harness. */
@Entity
@Table(name = "script_stabilization_run")
public class ScriptStabilizationRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String reportId;

  @Column(nullable = false, length = 200)
  private String scriptKey;

  @Column(length = 20, nullable = false)
  private String outcome; // PASS | FAIL | TIMEOUT

  @Column(length = 50)
  private String status;

  @Column private Long durationMs;

  @Column(length = 255)
  private String failureSignature;

  @Column(columnDefinition = "TEXT")
  private String errorMessage;

  @Column(length = 50)
  private String schemaVersion;

  @Column(length = 200)
  private String planHash;

  @Column(length = 100)
  private String runId;

  @Column(length = 100)
  private String attemptId;

  @Column(length = 200)
  private String settingsHash;

  @Column(length = 200)
  private String buildId;

  @Column(length = 200)
  private String catalogHash;

  @Column(length = 100)
  private String sourceSnapshotId;

  @Column(length = 100)
  private String primaryFailureCode;

  @Column private Boolean evidenceComplete;

  @Column(length = 200)
  private String manifestHash;

  @Column(length = 200)
  private String deterministicContentHash;

  @Column(length = 50)
  private String environment;

  @Column(length = 50)
  private String tier;

  @Column(length = 255)
  private String manifestReference;

  @Column(length = 255)
  private String transitionsReference;

  @Column(length = 255)
  private String evidenceReference;

  @Column(length = 255)
  private String checksumsReference;

  @Column(length = 255)
  private String matrixReference;

  @Column(length = 200)
  private String artifactDigest;

  @Column(length = 200)
  private String executorInstanceId;

  @Column(length = 100)
  private String entryId;

  @Column(length = 100)
  private String iterationId;

  @Column(length = 200)
  private String debugSessionId;

  @Column(length = 200)
  private String recordingId;

  /** Verified certified-bundle rows; legacy /ingest-report rows are excluded from KPIs/matrices. */
  @Column(nullable = false)
  private Boolean verified;

  @Column(columnDefinition = "TEXT")
  private String transitionsJson;

  @Column(columnDefinition = "TEXT")
  private String evidenceJson;

  @Column(nullable = false)
  private LocalDateTime executedAt;

  public ScriptStabilizationRun() {
    this.executedAt = LocalDateTime.now();
    this.verified = Boolean.TRUE;
  }

  public Long getId() {
    return id;
  }

  public String getReportId() {
    return reportId;
  }

  public void setReportId(String reportId) {
    this.reportId = reportId;
  }

  public String getScriptKey() {
    return scriptKey;
  }

  public void setScriptKey(String scriptKey) {
    this.scriptKey = scriptKey;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public String getFailureSignature() {
    return failureSignature;
  }

  public void setFailureSignature(String failureSignature) {
    this.failureSignature = failureSignature;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public String getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(String schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getPlanHash() {
    return planHash;
  }

  public void setPlanHash(String planHash) {
    this.planHash = planHash;
  }

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public String getAttemptId() {
    return attemptId;
  }

  public void setAttemptId(String attemptId) {
    this.attemptId = attemptId;
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

  public String getPrimaryFailureCode() {
    return primaryFailureCode;
  }

  public void setPrimaryFailureCode(String primaryFailureCode) {
    this.primaryFailureCode = primaryFailureCode;
  }

  public Boolean getEvidenceComplete() {
    return evidenceComplete;
  }

  public void setEvidenceComplete(Boolean evidenceComplete) {
    this.evidenceComplete = evidenceComplete;
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

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  public String getTier() {
    return tier;
  }

  public void setTier(String tier) {
    this.tier = tier;
  }

  public String getManifestReference() {
    return manifestReference;
  }

  public void setManifestReference(String manifestReference) {
    this.manifestReference = manifestReference;
  }

  public String getTransitionsReference() {
    return transitionsReference;
  }

  public void setTransitionsReference(String transitionsReference) {
    this.transitionsReference = transitionsReference;
  }

  public String getEvidenceReference() {
    return evidenceReference;
  }

  public void setEvidenceReference(String evidenceReference) {
    this.evidenceReference = evidenceReference;
  }

  public String getChecksumsReference() {
    return checksumsReference;
  }

  public void setChecksumsReference(String checksumsReference) {
    this.checksumsReference = checksumsReference;
  }

  public String getMatrixReference() {
    return matrixReference;
  }

  public void setMatrixReference(String matrixReference) {
    this.matrixReference = matrixReference;
  }

  public String getArtifactDigest() {
    return artifactDigest;
  }

  public void setArtifactDigest(String artifactDigest) {
    this.artifactDigest = artifactDigest;
  }

  public String getExecutorInstanceId() {
    return executorInstanceId;
  }

  public void setExecutorInstanceId(String executorInstanceId) {
    this.executorInstanceId = executorInstanceId;
  }

  public String getEntryId() {
    return entryId;
  }

  public void setEntryId(String entryId) {
    this.entryId = entryId;
  }

  public String getIterationId() {
    return iterationId;
  }

  public void setIterationId(String iterationId) {
    this.iterationId = iterationId;
  }

  public String getDebugSessionId() {
    return debugSessionId;
  }

  public void setDebugSessionId(String debugSessionId) {
    this.debugSessionId = debugSessionId;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
  }

  public Boolean getVerified() {
    return verified;
  }

  public void setVerified(Boolean verified) {
    this.verified = verified;
  }

  public String getTransitionsJson() {
    return transitionsJson;
  }

  public void setTransitionsJson(String transitionsJson) {
    this.transitionsJson = transitionsJson;
  }

  public String getEvidenceJson() {
    return evidenceJson;
  }

  public void setEvidenceJson(String evidenceJson) {
    this.evidenceJson = evidenceJson;
  }

  public LocalDateTime getExecutedAt() {
    return executedAt;
  }

  public void setExecutedAt(LocalDateTime executedAt) {
    this.executedAt = executedAt;
  }
}

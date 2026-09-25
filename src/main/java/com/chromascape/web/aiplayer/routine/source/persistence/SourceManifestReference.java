package com.chromascape.web.aiplayer.routine.source.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Immutable persisted reference to one verified producer manifest. */
@Entity
@Table(
    name = "source_manifest_references",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_source_manifest_references_manifest_hash",
          columnNames = "manifest_hash"),
      @UniqueConstraint(
          name = "uk_source_manifest_references_snapshot_id",
          columnNames = "snapshot_id")
    })
public class SourceManifestReference {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Version private Long version;

  @Column(name = "task_key", nullable = false, length = 160)
  private String taskKey;

  @Column(name = "schema_version", nullable = false, length = 80)
  private String schemaVersion;

  @Column(name = "manifest_hash", nullable = false, length = 71)
  private String manifestHash;

  @Column(name = "snapshot_id", nullable = false, length = 40)
  private String snapshotId;

  @Column(name = "repository_id", nullable = false, length = 200)
  private String repositoryId;

  @Column(name = "commit_id", nullable = false, length = 64)
  private String commitId;

  @Column(name = "tree_id", nullable = false, length = 64)
  private String treeId;

  @Column(name = "build_id", nullable = false, length = 200)
  private String buildId;

  @Column(name = "artifact_digest", nullable = false, length = 71)
  private String artifactDigest;

  @Column(name = "verification_state", nullable = false, length = 40)
  private String verificationState;

  @Column(name = "source_provenance", nullable = false, length = 40)
  private String sourceProvenance;

  @Column(name = "retention_class", nullable = false, length = 40)
  private String retentionClass;

  @Column(name = "confidentiality", nullable = false, length = 40)
  private String confidentiality;

  @Column(name = "environment", nullable = false, length = 120)
  private String environment;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "pruned_at")
  private Instant prunedAt;

  @Column(name = "redacted_at")
  private Instant redactedAt;

  @Column(name = "manifest_json", nullable = false, columnDefinition = "TEXT")
  private String manifestJson;

  public UUID getId() {
    return id;
  }

  public String getTaskKey() {
    return taskKey;
  }

  public void setTaskKey(String value) {
    taskKey = value;
  }

  public String getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(String value) {
    schemaVersion = value;
  }

  public String getManifestHash() {
    return manifestHash;
  }

  public void setManifestHash(String value) {
    manifestHash = value;
  }

  public String getSnapshotId() {
    return snapshotId;
  }

  public void setSnapshotId(String value) {
    snapshotId = value;
  }

  public String getRepositoryId() {
    return repositoryId;
  }

  public void setRepositoryId(String value) {
    repositoryId = value;
  }

  public String getCommitId() {
    return commitId;
  }

  public void setCommitId(String value) {
    commitId = value;
  }

  public String getTreeId() {
    return treeId;
  }

  public void setTreeId(String value) {
    treeId = value;
  }

  public String getBuildId() {
    return buildId;
  }

  public void setBuildId(String value) {
    buildId = value;
  }

  public String getArtifactDigest() {
    return artifactDigest;
  }

  public void setArtifactDigest(String value) {
    artifactDigest = value;
  }

  public String getVerificationState() {
    return verificationState;
  }

  public void setVerificationState(String value) {
    verificationState = value;
  }

  public String getSourceProvenance() {
    return sourceProvenance;
  }

  public void setSourceProvenance(String value) {
    sourceProvenance = value;
  }

  public String getRetentionClass() {
    return retentionClass;
  }

  public void setRetentionClass(String value) {
    retentionClass = value;
  }

  public String getConfidentiality() {
    return confidentiality;
  }

  public void setConfidentiality(String value) {
    confidentiality = value;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String value) {
    environment = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPrunedAt() {
    return prunedAt;
  }

  public void setPrunedAt(Instant value) {
    prunedAt = value;
  }

  public Instant getRedactedAt() {
    return redactedAt;
  }

  public void setRedactedAt(Instant value) {
    redactedAt = value;
  }

  public String getManifestJson() {
    return manifestJson;
  }

  public void setManifestJson(String value) {
    manifestJson = value;
  }
}

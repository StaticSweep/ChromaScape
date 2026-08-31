package com.chromascape.web.aiplayer.routine.source.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** Exact SC-609 run/recording to source-manifest binding. */
@Entity
@Table(
    name = "source_run_bindings",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_source_run_bindings_run_id", columnNames = "run_id"),
      @UniqueConstraint(name = "uk_source_run_bindings_recording_id", columnNames = "recording_id")
    })
public class SourceRunBinding {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "run_id", nullable = false, length = 160) private String runId;
  @Column(name = "recording_id", length = 160) private String recordingId;
  @Column(name = "task_key", nullable = false, length = 160) private String taskKey;
  @Column(name = "manifest_hash", nullable = false, length = 71) private String manifestHash;
  @Column(name = "snapshot_id", nullable = false, length = 40) private String snapshotId;
  // Legacy source bindings deliberately have no artifact proof; null means UNKNOWN rather than
  // manufacturing a digest. Authoritative v1 bindings are validated before persistence.
  @Column(name = "artifact_digest", length = 71) private String artifactDigest;
  @Column(name = "environment", length = 120) private String environment;
  @Column(name = "verification_state", nullable = false, length = 40) private String verificationState;
  @Column(name = "source_provenance", nullable = false, length = 40) private String sourceProvenance;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

  public UUID getId() { return id; }
  public String getRunId() { return runId; }
  public void setRunId(String value) { runId = value; }
  public String getRecordingId() { return recordingId; }
  public void setRecordingId(String value) { recordingId = value; }
  public String getTaskKey() { return taskKey; }
  public void setTaskKey(String value) { taskKey = value; }
  public String getManifestHash() { return manifestHash; }
  public void setManifestHash(String value) { manifestHash = value; }
  public String getSnapshotId() { return snapshotId; }
  public void setSnapshotId(String value) { snapshotId = value; }
  public String getArtifactDigest() { return artifactDigest; }
  public void setArtifactDigest(String value) { artifactDigest = value; }
  public String getEnvironment() { return environment; }
  public void setEnvironment(String value) { environment = value; }
  public String getVerificationState() { return verificationState; }
  public void setVerificationState(String value) { verificationState = value; }
  public String getSourceProvenance() { return sourceProvenance; }
  public void setSourceProvenance(String value) { sourceProvenance = value; }
  public Instant getCreatedAt() { return createdAt; }
}

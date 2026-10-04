package com.chromascape.web.replay.run.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "replay_checkpoints",
    uniqueConstraints = {
      @UniqueConstraint(columnNames = {"run_id", "branch_key", "tick", "action_index"})
    })
public class ReplayCheckpoint {

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "run_id", columnDefinition = "uuid", nullable = false)
  private UUID runId;

  @Column(name = "attempt_id", columnDefinition = "uuid", nullable = false)
  private UUID attemptId;

  @Column(name = "tick", nullable = false)
  private long tick;

  @Column(name = "action_index", nullable = false)
  private Integer actionIndex = 0;

  /** Kernel-internal snapshot checksum (sha256:&lt;hex&gt;); NOT NULL since V19. */
  @Column(name = "snapshot_checksum", nullable = false, length = 71)
  private String snapshotChecksum;

  /**
   * Content address of the complete serialized SNAPSHOT artifact (sha256:&lt;hex&gt;). Distinct
   * from {@link #snapshotChecksum}, which is the kernel-internal checksum embedded in the snapshot
   * document. Reconciled to a single NOT NULL column by V19.
   */
  @Column(name = "snapshot_artifact_checksum", nullable = false, length = 71)
  private String snapshotArtifactChecksum;

  @Column(name = "state_hash", nullable = false)
  private String stateHash;

  @Column(name = "chain_hash", nullable = false)
  private String chainHash;

  /**
   * SC-275 bound restore context document (JSON), binding snapshot digest, causal state digest,
   * tick, ordinal, and predecessor chain hash. Null for historical rows, which restore through the
   * legacy path guard.
   */
  @Column(name = "restore_context", columnDefinition = "TEXT")
  private String restoreContext;

  /**
   * Content address of the finalized CHAIN artifact carrying the bound transition-prefix frames.
   * Null for historical rows.
   */
  @Column(name = "chain_artifact_checksum", length = 71)
  private String chainArtifactChecksum;

  @Column(name = "parent_checkpoint_id", columnDefinition = "uuid")
  private UUID parentCheckpointId;

  @Column(name = "branch_key", nullable = false)
  private String branchKey = "default";

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ReplayCheckpoint() {}

  public ReplayCheckpoint(
      UUID id,
      UUID runId,
      UUID attemptId,
      long tick,
      String snapshotChecksum,
      String stateHash,
      String chainHash,
      String branchKey) {
    this.id = id;
    this.runId = runId;
    this.attemptId = attemptId;
    this.tick = tick;
    this.snapshotChecksum = snapshotChecksum;
    this.stateHash = stateHash;
    this.chainHash = chainHash;
    this.branchKey = branchKey;
    // createdAt is applied by the fenced persistence boundary from the PostgreSQL clock —
    // never the JVM clock.
    this.createdAt = null;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRunId() {
    return runId;
  }

  public UUID getAttemptId() {
    return attemptId;
  }

  public long getTick() {
    return tick;
  }

  public Integer getActionIndex() {
    return actionIndex;
  }

  public void setActionIndex(Integer actionIndex) {
    this.actionIndex = actionIndex;
  }

  public String getSnapshotChecksum() {
    return snapshotChecksum;
  }

  public void setSnapshotChecksum(String v) {
    this.snapshotChecksum = v;
  }

  public void setStateHash(String v) {
    this.stateHash = v;
  }

  public void setChainHash(String v) {
    this.chainHash = v;
  }

  public String getSnapshotArtifactChecksum() {
    return snapshotArtifactChecksum;
  }

  public void setSnapshotArtifactChecksum(String v) {
    this.snapshotArtifactChecksum = v;
  }

  /** Legacy durability-worker alias; artifact identifiers are content checksums since V18. */
  public void setSnapshotArtifactId(String v) {
    setSnapshotArtifactChecksum(v);
  }

  public String getRestoreContext() {
    return restoreContext;
  }

  public void setRestoreContext(String v) {
    this.restoreContext = v;
  }

  public String getChainArtifactChecksum() {
    return chainArtifactChecksum;
  }

  public void setChainArtifactChecksum(String v) {
    this.chainArtifactChecksum = v;
  }

  public String getStateHash() {
    return stateHash;
  }

  public String getChainHash() {
    return chainHash;
  }

  public UUID getParentCheckpointId() {
    return parentCheckpointId;
  }

  public void setParentCheckpointId(UUID parentCheckpointId) {
    this.parentCheckpointId = parentCheckpointId;
  }

  public String getBranchKey() {
    return branchKey;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Persistence-boundary stamping only: applied from the PostgreSQL clock. */
  public void setCreatedAt(Instant v) {
    this.createdAt = v;
  }
}

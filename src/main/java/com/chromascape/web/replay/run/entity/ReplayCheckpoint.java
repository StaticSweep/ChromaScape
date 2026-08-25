package com.chromascape.web.replay.run.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "replay_checkpoints", uniqueConstraints = {
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

    @Column(name = "snapshot_checksum")
    private String snapshotChecksum;

    /** Reference to the staged/finalized SNAPSHOT artifact for this checkpoint. */
    @Column(name = "snapshot_artifact_id", length = 64)
    private String snapshotArtifactId;

    @Column(name = "state_hash", nullable = false)
    private String stateHash;

    @Column(name = "chain_hash", nullable = false)
    private String chainHash;

    @Column(name = "parent_checkpoint_id", columnDefinition = "uuid")
    private UUID parentCheckpointId;

    @Column(name = "branch_key", nullable = false)
    private String branchKey = "default";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReplayCheckpoint() {}

    public ReplayCheckpoint(UUID id, UUID runId, UUID attemptId, long tick, String snapshotChecksum,
                            String stateHash, String chainHash, String branchKey) {
        this.id = id;
        this.runId = runId;
        this.attemptId = attemptId;
        this.tick = tick;
        this.snapshotChecksum = snapshotChecksum;
        this.stateHash = stateHash;
        this.chainHash = chainHash;
        this.branchKey = branchKey;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public UUID getAttemptId() { return attemptId; }
    public long getTick() { return tick; }
    public Integer getActionIndex() { return actionIndex; }
    public void setActionIndex(Integer actionIndex) { this.actionIndex = actionIndex; }
    public String getSnapshotChecksum() { return snapshotChecksum; }
    public void setSnapshotChecksum(String v) { this.snapshotChecksum = v; }
    public void setStateHash(String v) { this.stateHash = v; }
    public void setChainHash(String v) { this.chainHash = v; }
    public String getSnapshotArtifactId() { return snapshotArtifactId; }
    public void setSnapshotArtifactId(String v) { this.snapshotArtifactId = v; }
    public String getStateHash() { return stateHash; }
    public String getChainHash() { return chainHash; }
    public UUID getParentCheckpointId() { return parentCheckpointId; }
    public void setParentCheckpointId(UUID parentCheckpointId) { this.parentCheckpointId = parentCheckpointId; }
    public String getBranchKey() { return branchKey; }
    public Instant getCreatedAt() { return createdAt; }
}
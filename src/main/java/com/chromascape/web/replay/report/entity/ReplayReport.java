package com.chromascape.web.replay.report.entity;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable final report for one run. Columns match V14 exactly.
 * A report row is written exactly once, after all artifacts are finalized;
 * the report checksum covers the canonical report document.
 */
@Entity
@Table(name = "replay_reports")
public class ReplayReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", columnDefinition = "uuid", nullable = false, unique = true)
    private UUID runId;

    @Column(name = "attempt_id", columnDefinition = "uuid", nullable = false)
    private UUID attemptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReplayMode mode;

    @Column(name = "manifest_checksum", nullable = false)
    private String manifestChecksum;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReplayOutcome outcome;

    @Column(name = "executed_action_count", nullable = false)
    private int executedActionCount;

    @Column(name = "checkpoint_count", nullable = false)
    private int checkpointCount;

    @Column(name = "mismatch_hard_count", nullable = false)
    private long mismatchHardCount;

    @Column(name = "mismatch_soft_count", nullable = false)
    private long mismatchSoftCount;

    @Column(name = "mismatch_info_count", nullable = false)
    private long mismatchInfoCount;

    @Column(name = "final_state_hash")
    private String finalStateHash;

    @Column(name = "final_chain_hash")
    private String finalChainHash;

    @Column(name = "terminal_reason")
    private String terminalReason;

    @Column(name = "xrsps_adapter_version")
    private String xrspsAdapterVersion;

    @Column(name = "xrsps_build_commit")
    private String xrspsBuildCommit;

    @Column(name = "cache_revision")
    private String cacheRevision;

    @Column(name = "content_revision")
    private String contentRevision;

    @Column(name = "report_json", columnDefinition = "jsonb")
    private String reportJson;

    /** SHA-256 of the canonical report document. Unique and immutable. */
    @Column(name = "report_checksum", nullable = false, unique = true)
    private String reportChecksum;

    @Column(name = "control_allowed", nullable = false)
    private boolean controlAllowed = false;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "terminal_at")
    private Instant terminalAt;

    @Column(name = "finalized_at", nullable = false)
    private Instant finalizedAt;

    public ReplayReport() {}

    public void setControlAllowed(boolean v) { this.controlAllowed = v; }
    public void setFinalizedAt(Instant v) { this.finalizedAt = v; }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID v) { this.runId = v; }
    public UUID getAttemptId() { return attemptId; }
    public void setAttemptId(UUID v) { this.attemptId = v; }
    public ReplayMode getMode() { return mode; }
    public void setMode(ReplayMode v) { this.mode = v; }
    public String getManifestChecksum() { return manifestChecksum; }
    public void setManifestChecksum(String v) { this.manifestChecksum = v; }
    public ReplayOutcome getOutcome() { return outcome; }
    public void setOutcome(ReplayOutcome v) { this.outcome = v; }
    public int getExecutedActionCount() { return executedActionCount; }
    public void setExecutedActionCount(int v) { this.executedActionCount = v; }
    public int getCheckpointCount() { return checkpointCount; }
    public void setCheckpointCount(int v) { this.checkpointCount = v; }
    public long getMismatchHardCount() { return mismatchHardCount; }
    public void setMismatchHardCount(long v) { this.mismatchHardCount = v; }
    public long getMismatchSoftCount() { return mismatchSoftCount; }
    public void setMismatchSoftCount(long v) { this.mismatchSoftCount = v; }
    public long getMismatchInfoCount() { return mismatchInfoCount; }
    public void setMismatchInfoCount(long v) { this.mismatchInfoCount = v; }
    public String getFinalStateHash() { return finalStateHash; }
    public void setFinalStateHash(String v) { this.finalStateHash = v; }
    public String getFinalChainHash() { return finalChainHash; }
    public void setFinalChainHash(String v) { this.finalChainHash = v; }
    public String getTerminalReason() { return terminalReason; }
    public void setTerminalReason(String v) { this.terminalReason = v; }
    public String getXrspsAdapterVersion() { return xrspsAdapterVersion; }
    public void setXrspsAdapterVersion(String v) { this.xrspsAdapterVersion = v; }
    public String getXrspsBuildCommit() { return xrspsBuildCommit; }
    public void setXrspsBuildCommit(String v) { this.xrspsBuildCommit = v; }
    public String getCacheRevision() { return cacheRevision; }
    public void setCacheRevision(String v) { this.cacheRevision = v; }
    public String getContentRevision() { return contentRevision; }
    public void setContentRevision(String v) { this.contentRevision = v; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String v) { this.reportJson = v; }
    public String getReportChecksum() { return reportChecksum; }
    public void setReportChecksum(String v) { this.reportChecksum = v; }
    public boolean isControlAllowed() { return controlAllowed; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getTerminalAt() { return terminalAt; }
    public void setTerminalAt(Instant v) { this.terminalAt = v; }
    public Instant getFinalizedAt() { return finalizedAt; }
}

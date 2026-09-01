package com.chromascape.web.replay.run.entity;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "replay_runs")
public class ReplayRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReplayMode mode;

    @Column(name = "manifest_id", nullable = false)
    private UUID manifestId;

    @Column(name = "manifest_checksum", nullable = false)
    private String manifestChecksum;

    @Column(name = "manifest_version", nullable = false)
    private int manifestVersion = 1;

    @Column(nullable = false, length = 32)
    private String fidelity = "SYNTHETIC_CONTRACT";

    @Column(name = "conversion_quality", nullable = false, length = 32)
    private String conversionQuality = "BEST_EFFORT";

    @Column(name = "action_coverage_pct", nullable = false, columnDefinition = "NUMERIC(5,2)")
    private double actionCoveragePct;

    @Column(name = "gap_count", nullable = false)
    private int gapCount;

    @Column(name = "approximation_count", nullable = false)
    private int approximationCount;

    @Column(name = "viewer_trace_checksum", length = 71)
    private String viewerTraceChecksum;

    @Column(name = "viewer_trace_frame_count")
    private Long viewerTraceFrameCount;

    @Column(name = "viewer_requested", nullable = false)
    private boolean viewerRequested = false;

    /** Hash of the v2 creation request, excluding the idempotency key itself. */
    @Column(name = "request_fingerprint", length = 71)
    private String requestFingerprint;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReplayRunState state = ReplayRunState.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReplayRunPhase phase = ReplayRunPhase.PREFLIGHT;

    @Enumerated(EnumType.STRING)
    @Column
    private ReplayOutcome outcome;

    @Column(name = "terminal_reason")
    private String terminalReason;

    @Column(name = "stop_policy", nullable = false)
    @Enumerated(EnumType.STRING)
    private ReplayStopPolicy stopPolicy = ReplayStopPolicy.STOP_ON_FIRST_HARD;

    @Column(name = "control_allowed", nullable = false)
    private boolean controlAllowed = false;

    @Column(name = "xrsps_adapter_version")
    private String xrspsAdapterVersion;

    @Column(name = "xrsps_build_commit")
    private String xrspsBuildCommit;

    @Column(name = "cache_revision")
    private String cacheRevision;

    @Column(name = "content_revision")
    private String contentRevision;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Branch lineage (V15): 'main' for normal runs. */
    @Column(name = "branch_key", nullable = false)
    private String branchKey = "main";

    @Column(name = "parent_run_id")
    private UUID parentRunId;

    @Column(name = "parent_checkpoint_id")
    private UUID parentCheckpointId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "terminal_at")
    private LocalDateTime terminalAt;

    @Column(name = "finalization_reserved_at")
    private LocalDateTime finalizationReservedAt;

    public ReplayRun() {}

    // -- Getters / Setters --

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public ReplayMode getMode() { return mode; }
    public void setMode(ReplayMode mode) { this.mode = mode; }
    public UUID getManifestId() { return manifestId; }
    public void setManifestId(UUID manifestId) { this.manifestId = manifestId; }
    public String getManifestChecksum() { return manifestChecksum; }
    public void setManifestChecksum(String manifestChecksum) { this.manifestChecksum = manifestChecksum; }
    public int getManifestVersion() { return manifestVersion; }
    public void setManifestVersion(int v) { this.manifestVersion = v; }
    public String getFidelity() { return fidelity; }
    public void setFidelity(String v) { this.fidelity = v; }
    public String getConversionQuality() { return conversionQuality; }
    public void setConversionQuality(String v) { this.conversionQuality = v; }
    public double getActionCoveragePct() { return actionCoveragePct; }
    public void setActionCoveragePct(double v) { this.actionCoveragePct = v; }
    public int getGapCount() { return gapCount; }
    public void setGapCount(int v) { this.gapCount = v; }
    public int getApproximationCount() { return approximationCount; }
    public void setApproximationCount(int v) { this.approximationCount = v; }
    public String getViewerTraceChecksum() { return viewerTraceChecksum; }
    public void setViewerTraceChecksum(String v) { this.viewerTraceChecksum = v; }
    public Long getViewerTraceFrameCount() { return viewerTraceFrameCount; }
    public void setViewerTraceFrameCount(Long v) { this.viewerTraceFrameCount = v; }
    public boolean isViewerRequested() { return viewerRequested; }
    public void setViewerRequested(boolean v) { this.viewerRequested = v; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String v) { this.requestFingerprint = v; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public ReplayRunState getState() { return state; }
    public void setState(ReplayRunState state) { this.state = state; }
    public ReplayRunPhase getPhase() { return phase; }
    public void setPhase(ReplayRunPhase phase) { this.phase = phase; }
    public ReplayOutcome getOutcome() { return outcome; }
    public void setOutcome(ReplayOutcome outcome) { this.outcome = outcome; }
    public String getTerminalReason() { return terminalReason; }
    public void setTerminalReason(String terminalReason) { this.terminalReason = terminalReason; }
    public ReplayStopPolicy getStopPolicy() { return stopPolicy; }
    public void setStopPolicy(ReplayStopPolicy stopPolicy) { this.stopPolicy = stopPolicy; }
    public boolean isControlAllowed() { return controlAllowed; }
    public void setControlAllowed(boolean controlAllowed) { this.controlAllowed = controlAllowed; }
    public String getXrspsAdapterVersion() { return xrspsAdapterVersion; }
    public void setXrspsAdapterVersion(String v) { this.xrspsAdapterVersion = v; }
    public String getXrspsBuildCommit() { return xrspsBuildCommit; }
    public void setXrspsBuildCommit(String v) { this.xrspsBuildCommit = v; }
    public String getCacheRevision() { return cacheRevision; }
    public void setCacheRevision(String v) { this.cacheRevision = v; }
    public String getContentRevision() { return contentRevision; }
    public void setContentRevision(String v) { this.contentRevision = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public String getBranchKey() { return branchKey; }
    public void setBranchKey(String v) { this.branchKey = v; }
    public UUID getParentRunId() { return parentRunId; }
    public void setParentRunId(UUID v) { this.parentRunId = v; }
    public UUID getParentCheckpointId() { return parentCheckpointId; }
    public void setParentCheckpointId(UUID v) { this.parentCheckpointId = v; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime v) { this.startedAt = v; }
    public LocalDateTime getTerminalAt() { return terminalAt; }
    public void setTerminalAt(LocalDateTime v) { this.terminalAt = v; }
    public LocalDateTime getFinalizationReservedAt() { return finalizationReservedAt; }
    public void setFinalizationReservedAt(LocalDateTime v) { this.finalizationReservedAt = v; }

    // -- Convenience helpers --

    public boolean isTerminal() {
        return state == ReplayRunState.SUCCEEDED
                || state == ReplayRunState.FAILED
                || state == ReplayRunState.CANCELLED
                || state == ReplayRunState.TIMED_OUT;
    }

    public boolean isActive() {
        return state == ReplayRunState.QUEUED
                || state == ReplayRunState.CLAIMED
                || state == ReplayRunState.STARTING
                || state == ReplayRunState.RUNNING;
    }
}

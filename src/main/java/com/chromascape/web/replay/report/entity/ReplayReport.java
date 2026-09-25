package com.chromascape.web.replay.report.entity;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable final report for one run. Columns match V14 exactly. A report row is written exactly
 * once, after all artifacts are finalized; the report checksum covers the canonical report
 * document.
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

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private ReplayOutcome outcome;

  @Column(name = "executed_action_count", nullable = false)
  private int executedActionCount;

  @Column(name = "checkpoint_count", nullable = false)
  private int checkpointCount;

  @Column(name = "mismatch_hard_count", nullable = false)
  private int mismatchHardCount;

  @Column(name = "mismatch_soft_count", nullable = false)
  private int mismatchSoftCount;

  @Column(name = "mismatch_info_count", nullable = false)
  private int mismatchInfoCount;

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
  @JdbcTypeCode(SqlTypes.JSON)
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

  public void setControlAllowed(boolean v) {
    this.controlAllowed = v;
  }

  public void setFinalizedAt(Instant v) {
    this.finalizedAt = v;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRunId() {
    return runId;
  }

  public void setRunId(UUID v) {
    this.runId = v;
  }

  public UUID getAttemptId() {
    return attemptId;
  }

  public void setAttemptId(UUID v) {
    this.attemptId = v;
  }

  public ReplayMode getMode() {
    return mode;
  }

  public void setMode(ReplayMode v) {
    this.mode = v;
  }

  public String getManifestChecksum() {
    return manifestChecksum;
  }

  public void setManifestChecksum(String v) {
    this.manifestChecksum = v;
  }

  public int getManifestVersion() {
    return manifestVersion;
  }

  public void setManifestVersion(int v) {
    this.manifestVersion = v;
  }

  public String getFidelity() {
    return fidelity;
  }

  public void setFidelity(String v) {
    this.fidelity = v;
  }

  public String getConversionQuality() {
    return conversionQuality;
  }

  public void setConversionQuality(String v) {
    this.conversionQuality = v;
  }

  public double getActionCoveragePct() {
    return actionCoveragePct;
  }

  public void setActionCoveragePct(double v) {
    this.actionCoveragePct = v;
  }

  public int getGapCount() {
    return gapCount;
  }

  public void setGapCount(int v) {
    this.gapCount = v;
  }

  public int getApproximationCount() {
    return approximationCount;
  }

  public void setApproximationCount(int v) {
    this.approximationCount = v;
  }

  public String getViewerTraceChecksum() {
    return viewerTraceChecksum;
  }

  public void setViewerTraceChecksum(String v) {
    this.viewerTraceChecksum = v;
  }

  public Long getViewerTraceFrameCount() {
    return viewerTraceFrameCount;
  }

  public void setViewerTraceFrameCount(Long v) {
    this.viewerTraceFrameCount = v;
  }

  public ReplayOutcome getOutcome() {
    return outcome;
  }

  public void setOutcome(ReplayOutcome v) {
    this.outcome = v;
  }

  public int getExecutedActionCount() {
    return executedActionCount;
  }

  public void setExecutedActionCount(int v) {
    this.executedActionCount = v;
  }

  public int getCheckpointCount() {
    return checkpointCount;
  }

  public void setCheckpointCount(int v) {
    this.checkpointCount = v;
  }

  public long getMismatchHardCount() {
    return mismatchHardCount;
  }

  public void setMismatchHardCount(long v) {
    this.mismatchHardCount = Math.toIntExact(v);
  }

  public long getMismatchSoftCount() {
    return mismatchSoftCount;
  }

  public void setMismatchSoftCount(long v) {
    this.mismatchSoftCount = Math.toIntExact(v);
  }

  public long getMismatchInfoCount() {
    return mismatchInfoCount;
  }

  public void setMismatchInfoCount(long v) {
    this.mismatchInfoCount = Math.toIntExact(v);
  }

  public String getFinalStateHash() {
    return finalStateHash;
  }

  public void setFinalStateHash(String v) {
    this.finalStateHash = v;
  }

  public String getFinalChainHash() {
    return finalChainHash;
  }

  public void setFinalChainHash(String v) {
    this.finalChainHash = v;
  }

  public String getTerminalReason() {
    return terminalReason;
  }

  public void setTerminalReason(String v) {
    this.terminalReason = v;
  }

  public String getXrspsAdapterVersion() {
    return xrspsAdapterVersion;
  }

  public void setXrspsAdapterVersion(String v) {
    this.xrspsAdapterVersion = v;
  }

  public String getXrspsBuildCommit() {
    return xrspsBuildCommit;
  }

  public void setXrspsBuildCommit(String v) {
    this.xrspsBuildCommit = v;
  }

  public String getCacheRevision() {
    return cacheRevision;
  }

  public void setCacheRevision(String v) {
    this.cacheRevision = v;
  }

  public String getContentRevision() {
    return contentRevision;
  }

  public void setContentRevision(String v) {
    this.contentRevision = v;
  }

  public String getReportJson() {
    return reportJson;
  }

  public void setReportJson(String v) {
    this.reportJson = v;
  }

  public String getReportChecksum() {
    return reportChecksum;
  }

  public void setReportChecksum(String v) {
    this.reportChecksum = v;
  }

  public boolean isControlAllowed() {
    return controlAllowed;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant v) {
    this.startedAt = v;
  }

  public Instant getTerminalAt() {
    return terminalAt;
  }

  public void setTerminalAt(Instant v) {
    this.terminalAt = v;
  }

  public Instant getFinalizedAt() {
    return finalizedAt;
  }
}

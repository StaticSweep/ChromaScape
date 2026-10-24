package com.chromascape.web.replay.manifest.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "scenario_manifests")
public class ScenarioManifest {

  @Id
  // v2 assigns the immutable manifest ID before persistence, including during read-only preview.
  // Generated-ID mappings reject such new rows as stale detached entities in Hibernate 6.6.
  // v1 callers that do not assign an ID retain UUID generation without changing existing rows.
  private UUID id = UUID.randomUUID();

  @PrePersist
  void assignMissingId() {
    if (id == null) id = UUID.randomUUID();
  }

  /** Version of the serialized scenario contract. Existing rows are v1. */
  @Column(name = "manifest_version", nullable = false)
  private int manifestVersion = 1;

  @Column(name = "curation_id", nullable = false)
  private UUID curationId;

  @Column(name = "recording_id", nullable = false)
  private String recordingId;

  @Column(name = "debug_session_id", nullable = false)
  private String debugSessionId;

  @Column(name = "recording_checksum", nullable = false)
  private String recordingChecksum;

  @Column(name = "debug_session_checksum", nullable = false)
  private String debugSessionChecksum;

  @Column(name = "compiler_version", nullable = false)
  private String compilerVersion;

  @Column(name = "compiler_schema_version", nullable = false)
  private int compilerSchemaVersion;

  @Column(name = "xrsps_adapter_version", nullable = false)
  private String xrspsAdapterVersion;

  @Column(name = "xrsps_build_commit", nullable = false)
  private String xrspsBuildCommit;

  @Column(name = "cache_revision", nullable = false)
  private String cacheRevision;

  @Column(name = "content_revision", nullable = false)
  private String contentRevision;

  @Column(nullable = false)
  private String seed;

  @Column(name = "fixed_clock_origin", nullable = false)
  private LocalDateTime fixedClockOrigin;

  @Column(name = "start_tick", nullable = false)
  private long startTick;

  @Column(name = "end_tick", nullable = false)
  private long endTick;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "manifest_json", nullable = false)
  private JsonNode manifestJson;

  @Column(name = "manifest_checksum", nullable = false, unique = true)
  private String manifestChecksum;

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

  @Column(nullable = false)
  private String completeness = "COMPLETE";

  @Column(name = "publish_state", nullable = false)
  private String publishState = "DRAFT";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rejection_json")
  private JsonNode rejectionJson;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  public ScenarioManifest() {}

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public int getManifestVersion() {
    return manifestVersion;
  }

  public void setManifestVersion(int v) {
    this.manifestVersion = v;
  }

  public UUID getCurationId() {
    return curationId;
  }

  public void setCurationId(UUID v) {
    this.curationId = v;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String v) {
    this.recordingId = v;
  }

  public String getDebugSessionId() {
    return debugSessionId;
  }

  public void setDebugSessionId(String v) {
    this.debugSessionId = v;
  }

  public String getRecordingChecksum() {
    return recordingChecksum;
  }

  public void setRecordingChecksum(String v) {
    this.recordingChecksum = v;
  }

  public String getDebugSessionChecksum() {
    return debugSessionChecksum;
  }

  public void setDebugSessionChecksum(String v) {
    this.debugSessionChecksum = v;
  }

  public String getCompilerVersion() {
    return compilerVersion;
  }

  public void setCompilerVersion(String v) {
    this.compilerVersion = v;
  }

  public int getCompilerSchemaVersion() {
    return compilerSchemaVersion;
  }

  public void setCompilerSchemaVersion(int v) {
    this.compilerSchemaVersion = v;
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

  public String getSeed() {
    return seed;
  }

  public void setSeed(String seed) {
    this.seed = seed;
  }

  public LocalDateTime getFixedClockOrigin() {
    return fixedClockOrigin;
  }

  public void setFixedClockOrigin(LocalDateTime v) {
    this.fixedClockOrigin = v;
  }

  public long getStartTick() {
    return startTick;
  }

  public void setStartTick(long v) {
    this.startTick = v;
  }

  public long getEndTick() {
    return endTick;
  }

  public void setEndTick(long v) {
    this.endTick = v;
  }

  public JsonNode getManifestJson() {
    return manifestJson;
  }

  public void setManifestJson(JsonNode v) {
    this.manifestJson = v;
  }

  public String getManifestChecksum() {
    return manifestChecksum;
  }

  public void setManifestChecksum(String v) {
    this.manifestChecksum = v;
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

  public String getCompleteness() {
    return completeness;
  }

  public void setCompleteness(String v) {
    this.completeness = v;
  }

  public String getPublishState() {
    return publishState;
  }

  public void setPublishState(String v) {
    this.publishState = v;
  }

  public JsonNode getRejectionJson() {
    return rejectionJson;
  }

  public void setRejectionJson(JsonNode v) {
    this.rejectionJson = v;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime v) {
    this.createdAt = v;
  }
}

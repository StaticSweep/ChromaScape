package com.chromascape.web.debug.tickdebugger.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable analysis of an external source. The four capability flags are constrained false in
 * {@code V42__sc2150_mouse_video_sources.sql}: video-derived observations are estimates and never
 * claim native telemetry, exact ticks, control, or training eligibility.
 */
@Entity
@Table(name = "mouse_video_analyses")
public class MouseVideoAnalysis {

  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_COMPLETE = "COMPLETE";
  public static final String STATUS_FAILED = "FAILED";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "source_id", columnDefinition = "uuid", nullable = false)
  private UUID sourceId;

  @Column(name = "owner", nullable = false, length = 160)
  private String owner;

  @Column(name = "original_digest", nullable = false, length = 80)
  private String originalDigest;

  @Column(name = "algorithm_id", nullable = false, length = 80)
  private String algorithmId;

  @Column(name = "template_pack_id", length = 80)
  private String templatePackId;

  @Column(name = "config_digest", nullable = false, length = 80)
  private String configDigest;

  @Column(name = "toolchain_digest", nullable = false, length = 80)
  private String toolchainDigest;

  @Column(name = "template_checksum", length = 80)
  private String templateChecksum;

  @Column(name = "coverage_summary", length = 1024)
  private String coverageSummary;

  @Column(name = "manifest_checksum", length = 80)
  private String manifestChecksum;

  @Column(name = "status", nullable = false, length = 24)
  private String status = STATUS_PENDING;

  @Column(name = "native_telemetry", nullable = false)
  private boolean nativeTelemetry;

  @Column(name = "exact_ticks_available", nullable = false)
  private boolean exactTicksAvailable;

  @Column(name = "control_allowed", nullable = false)
  private boolean controlAllowed;

  @Column(name = "training_eligible", nullable = false)
  private boolean trainingEligible;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected MouseVideoAnalysis() {}

  public MouseVideoAnalysis(
      UUID sourceId,
      String owner,
      String originalDigest,
      String algorithmId,
      String templatePackId,
      String configDigest,
      String toolchainDigest) {
    this.sourceId = sourceId;
    this.owner = owner;
    this.originalDigest = originalDigest;
    this.algorithmId = algorithmId;
    this.templatePackId = templatePackId;
    this.configDigest = configDigest;
    this.toolchainDigest = toolchainDigest;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getSourceId() {
    return sourceId;
  }

  public String getOwner() {
    return owner;
  }

  public String getOriginalDigest() {
    return originalDigest;
  }

  public String getAlgorithmId() {
    return algorithmId;
  }

  public String getTemplatePackId() {
    return templatePackId;
  }

  public String getConfigDigest() {
    return configDigest;
  }

  public String getToolchainDigest() {
    return toolchainDigest;
  }

  public String getTemplateChecksum() {
    return templateChecksum;
  }

  public void setTemplateChecksum(String templateChecksum) {
    this.templateChecksum = templateChecksum;
  }

  public String getCoverageSummary() {
    return coverageSummary;
  }

  public void setCoverageSummary(String coverageSummary) {
    this.coverageSummary = coverageSummary;
  }

  public String getManifestChecksum() {
    return manifestChecksum;
  }

  public void setManifestChecksum(String manifestChecksum) {
    this.manifestChecksum = manifestChecksum;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public boolean isNativeTelemetry() {
    return nativeTelemetry;
  }

  public boolean isExactTicksAvailable() {
    return exactTicksAvailable;
  }

  public boolean isControlAllowed() {
    return controlAllowed;
  }

  public boolean isTrainingEligible() {
    return trainingEligible;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void complete(String coverageSummary, String manifestChecksum) {
    this.coverageSummary = coverageSummary;
    this.manifestChecksum = manifestChecksum;
    this.status = STATUS_COMPLETE;
    this.completedAt = Instant.now();
  }

  /** Re-queues an analysis row whose previous attempt failed, without changing its identity. */
  public void reopen() {
    this.status = STATUS_PENDING;
    this.completedAt = null;
  }

  public void fail() {
    this.status = STATUS_FAILED;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

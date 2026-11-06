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
 * Registration of a verified artifact for a source or analysis. The SQL row is written only after
 * the content-addressed byte object is published to the byte store; a STAGING row may exist while
 * publication is in flight and is reconciled after failure.
 */
@Entity
@Table(name = "mouse_video_artifact_refs")
public class MouseVideoArtifactRef {

  public static final String ROLE_ORIGINAL = "ORIGINAL";
  public static final String ROLE_PROXY = "PROXY";
  public static final String ROLE_THUMBNAIL = "THUMBNAIL";
  public static final String ROLE_FRAME_INDEX = "FRAME_INDEX";
  public static final String ROLE_OBSERVATIONS = "OBSERVATIONS";
  public static final String ROLE_QUALITY = "QUALITY";
  public static final String ROLE_REVIEW_EXPORT = "REVIEW_EXPORT";
  public static final String ROLE_MANIFEST = "MANIFEST";
  public static final String ROLE_EXPORT = "EXPORT";

  public static final String STATE_STAGING = "STAGING";
  public static final String STATE_FINALIZED = "FINALIZED";
  public static final String STATE_QUARANTINED = "QUARANTINED";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "owner", nullable = false, length = 160)
  private String owner;

  @Column(name = "source_id", columnDefinition = "uuid")
  private UUID sourceId;

  @Column(name = "analysis_id", columnDefinition = "uuid")
  private UUID analysisId;

  @Column(name = "export_id", columnDefinition = "uuid")
  private UUID exportId;

  @Column(name = "role", nullable = false, length = 24)
  private String role;

  @Column(name = "checksum", nullable = false, length = 80)
  private String checksum;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "media_type", length = 100)
  private String mediaType;

  @Column(name = "state", nullable = false, length = 16)
  private String state = STATE_STAGING;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "finalized_at")
  private Instant finalizedAt;

  protected MouseVideoArtifactRef() {}

  public MouseVideoArtifactRef(
      String owner,
      UUID sourceId,
      UUID analysisId,
      String role,
      String checksum,
      long sizeBytes,
      String mediaType) {
    this.owner = owner;
    this.sourceId = sourceId;
    this.analysisId = analysisId;
    this.role = role;
    this.checksum = checksum;
    this.sizeBytes = sizeBytes;
    this.mediaType = mediaType;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getOwner() {
    return owner;
  }

  public UUID getSourceId() {
    return sourceId;
  }

  public UUID getAnalysisId() {
    return analysisId;
  }

  public UUID getExportId() {
    return exportId;
  }

  public void setExportId(UUID exportId) {
    this.exportId = exportId;
  }

  public String getRole() {
    return role;
  }

  public String getChecksum() {
    return checksum;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getMediaType() {
    return mediaType;
  }

  public void setMediaType(String mediaType) {
    this.mediaType = mediaType;
  }

  public String getState() {
    return state;
  }

  public void finalizeRow() {
    this.state = STATE_FINALIZED;
    this.finalizedAt = Instant.now();
  }

  /** Finalizes a qualified artifact with the server-derived, verified media type. */
  public void finalizeVerified(String verifiedMediaType) {
    this.mediaType = verifiedMediaType;
    finalizeRow();
  }

  public void quarantine() {
    this.state = STATE_QUARANTINED;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getFinalizedAt() {
    return finalizedAt;
  }
}

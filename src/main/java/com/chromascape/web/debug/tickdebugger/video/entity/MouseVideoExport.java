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
 * Immutable export snapshot pinned to a source, an optional analysis identity and a review
 * revision. Identical creation requests return the existing snapshot; publishing newer
 * analysis/review evidence creates a distinct version instead of overwriting the old bytes.
 */
@Entity
@Table(name = "mouse_video_exports")
public class MouseVideoExport {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "owner", nullable = false, length = 160)
  private String owner;

  @Column(name = "source_id", columnDefinition = "uuid", nullable = false)
  private UUID sourceId;

  @Column(name = "request_id", nullable = false, length = 80)
  private String requestId;

  @Column(name = "request_digest", nullable = false, length = 80)
  private String requestDigest;

  @Column(name = "contract", nullable = false, length = 80)
  private String contract;

  @Column(name = "analysis_id", columnDefinition = "uuid")
  private UUID analysisId;

  @Column(name = "review_revision", nullable = false)
  private long reviewRevision;

  @Column(name = "manifest_checksum", nullable = false, length = 80)
  private String manifestChecksum;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected MouseVideoExport() {}

  public MouseVideoExport(
      String owner,
      UUID sourceId,
      String requestId,
      String requestDigest,
      String contract,
      UUID analysisId,
      long reviewRevision,
      String manifestChecksum,
      long sizeBytes) {
    this.owner = owner;
    this.sourceId = sourceId;
    this.requestId = requestId;
    this.requestDigest = requestDigest;
    this.contract = contract;
    this.analysisId = analysisId;
    this.reviewRevision = reviewRevision;
    this.manifestChecksum = manifestChecksum;
    this.sizeBytes = sizeBytes;
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

  public String getRequestId() {
    return requestId;
  }

  public String getRequestDigest() {
    return requestDigest;
  }

  public String getContract() {
    return contract;
  }

  public UUID getAnalysisId() {
    return analysisId;
  }

  public long getReviewRevision() {
    return reviewRevision;
  }

  public String getManifestChecksum() {
    return manifestChecksum;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

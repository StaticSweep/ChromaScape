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
 * Owner-scoped external media source. Never represents a native tick session: external media keeps
 * its own UUID identity and acquisition provenance, and native ticks/physical clicks are never
 * inferred from it. See {@code V42__sc2150_mouse_video_sources.sql}.
 */
@Entity
@Table(name = "mouse_video_sources")
public class MouseVideoSource {

  public static final String KIND_UPLOAD = "UPLOAD";
  public static final String KIND_YOUTUBE = "YOUTUBE";

  public static final String ORIGIN_HUMAN = "HUMAN";
  public static final String ORIGIN_GENERATED = "GENERATED";
  public static final String ORIGIN_UNKNOWN = "UNKNOWN";

  public static final String REVIEW_UNREVIEWED = "UNREVIEWED";
  public static final String REVIEW_REVIEWED = "REVIEWED";
  public static final String REVIEW_REJECTED = "REJECTED";

  public static final String LIFECYCLE_OPEN = "OPEN";
  public static final String LIFECYCLE_DELETED = "DELETED";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "owner", nullable = false, length = 160)
  private String owner;

  @Column(name = "source_kind", nullable = false, length = 16)
  private String sourceKind;

  @Column(name = "provider_id", length = 160)
  private String providerId;

  @Column(name = "provider_url", length = 2048)
  private String providerUrl;

  @Column(name = "declared_origin", nullable = false, length = 16)
  private String declaredOrigin = ORIGIN_UNKNOWN;

  @Column(name = "origin_review", nullable = false, length = 20)
  private String originReview = REVIEW_UNREVIEWED;

  @Column(name = "task_key", length = 120)
  private String taskKey;

  @Column(name = "skill", length = 120)
  private String skill;

  @Column(name = "context_label", length = 120)
  private String contextLabel;

  @Column(name = "place_label", length = 120)
  private String placeLabel;

  @Column(name = "original_checksum", length = 80)
  private String originalChecksum;

  @Column(name = "original_size_bytes")
  private Long originalSizeBytes;

  @Column(name = "original_media_type", length = 100)
  private String originalMediaType;

  @Column(name = "verified_media_type", length = 100)
  private String verifiedMediaType;

  @Column(name = "original_qualified", nullable = false)
  private boolean originalQualified;

  @Column(name = "lifecycle", nullable = false, length = 20)
  private String lifecycle = LIFECYCLE_OPEN;

  @Column(name = "revision", nullable = false)
  private long revision = 1L;

  @Column(name = "review_revision", nullable = false)
  private long reviewRevision = 0L;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MouseVideoSource() {}

  public MouseVideoSource(String owner, String sourceKind) {
    this.owner = owner;
    this.sourceKind = sourceKind;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getOwner() {
    return owner;
  }

  public String getSourceKind() {
    return sourceKind;
  }

  public String getProviderId() {
    return providerId;
  }

  public void setProviderId(String providerId) {
    this.providerId = providerId;
  }

  public String getProviderUrl() {
    return providerUrl;
  }

  public void setProviderUrl(String providerUrl) {
    this.providerUrl = providerUrl;
  }

  public String getDeclaredOrigin() {
    return declaredOrigin;
  }

  public void setDeclaredOrigin(String declaredOrigin) {
    this.declaredOrigin = declaredOrigin;
  }

  public String getOriginReview() {
    return originReview;
  }

  public void setOriginReview(String originReview) {
    this.originReview = originReview;
  }

  public String getTaskKey() {
    return taskKey;
  }

  public void setTaskKey(String taskKey) {
    this.taskKey = taskKey;
  }

  public String getSkill() {
    return skill;
  }

  public void setSkill(String skill) {
    this.skill = skill;
  }

  public String getContextLabel() {
    return contextLabel;
  }

  public void setContextLabel(String contextLabel) {
    this.contextLabel = contextLabel;
  }

  public String getPlaceLabel() {
    return placeLabel;
  }

  public void setPlaceLabel(String placeLabel) {
    this.placeLabel = placeLabel;
  }

  public String getOriginalChecksum() {
    return originalChecksum;
  }

  public Long getOriginalSizeBytes() {
    return originalSizeBytes;
  }

  public void bindOriginal(String checksum, long sizeBytes, String mediaType) {
    this.originalChecksum = checksum;
    this.originalSizeBytes = sizeBytes;
    this.originalMediaType = mediaType;
    this.updatedAt = Instant.now();
  }

  public String getOriginalMediaType() {
    return originalMediaType;
  }

  public String getVerifiedMediaType() {
    return verifiedMediaType;
  }

  public boolean isOriginalQualified() {
    return originalQualified;
  }

  /**
   * Marks the original qualified with the server-derived media type chosen by a successful probe.
   */
  public void qualifyOriginal(String serverDerivedMediaType) {
    this.verifiedMediaType = serverDerivedMediaType;
    this.originalMediaType = serverDerivedMediaType;
    this.originalQualified = true;
    this.updatedAt = Instant.now();
  }

  public String getLifecycle() {
    return lifecycle;
  }

  public void setLifecycle(String lifecycle) {
    this.lifecycle = lifecycle;
    this.updatedAt = Instant.now();
  }

  public long getRevision() {
    return revision;
  }

  public long getReviewRevision() {
    return reviewRevision;
  }

  public void advanceReviewRevision() {
    this.reviewRevision += 1L;
    this.updatedAt = Instant.now();
  }

  public void setRevision(long revision) {
    this.revision = revision;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}

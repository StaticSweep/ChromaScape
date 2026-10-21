package com.chromascape.web.debug.tickdebugger.video.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only review revision. Corrections and source-use decisions never mutate earlier revisions
 * or the original predictions; a new revision links its predecessor and records the base revision
 * it was applied against so revision races are detectable.
 */
@Entity
@Table(name = "mouse_video_review_revisions")
public class MouseVideoReviewRevision {

  public static final String USE_INSPECTION_ONLY = "INSPECTION_ONLY";
  public static final String USE_TRAINING_REQUESTED = "TRAINING_REQUESTED";
  public static final String USE_TRAINING_DENIED = "TRAINING_DENIED";

  public static final String STATE_DRAFT = "DRAFT";
  public static final String STATE_REVIEWED = "REVIEWED";
  public static final String STATE_SUPERSEDED = "SUPERSEDED";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "source_id", columnDefinition = "uuid", nullable = false)
  private UUID sourceId;

  @Column(name = "analysis_id", columnDefinition = "uuid")
  private UUID analysisId;

  @Column(name = "predecessor_id", columnDefinition = "uuid")
  private UUID predecessorId;

  @Column(name = "request_id", length = 80)
  private String requestId;

  /** Canonical digest of the validated request body; null for pre-contract historical rows. */
  @Column(name = "request_digest", length = 80)
  private String requestDigest;

  /** Correction contract identity recorded with this revision. */
  @Column(name = "contract_version", nullable = false, length = 40)
  private String contractVersion = "corrections/v0";

  @Column(name = "actor", nullable = false, length = 160)
  private String actor;

  @Column(name = "reason", nullable = false, length = 1024)
  private String reason;

  @Column(name = "expected_base_revision", nullable = false)
  private long expectedBaseRevision;

  @Column(name = "source_use_review", nullable = false, length = 24)
  private String sourceUseReview = USE_INSPECTION_ONLY;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "corrections_json", nullable = false)
  private JsonNode correctionsJson;

  @Column(name = "manifest_checksum", length = 80)
  private String manifestChecksum;

  @Column(name = "state", nullable = false, length = 16)
  private String state = STATE_REVIEWED;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected MouseVideoReviewRevision() {}

  public MouseVideoReviewRevision(
      UUID sourceId,
      UUID analysisId,
      UUID predecessorId,
      String actor,
      String reason,
      long expectedBaseRevision,
      String sourceUseReview,
      JsonNode correctionsJson) {
    this.sourceId = sourceId;
    this.analysisId = analysisId;
    this.predecessorId = predecessorId;
    this.actor = actor;
    this.reason = reason;
    this.expectedBaseRevision = expectedBaseRevision;
    this.sourceUseReview = sourceUseReview;
    this.correctionsJson = correctionsJson;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getSourceId() {
    return sourceId;
  }

  public UUID getAnalysisId() {
    return analysisId;
  }

  public UUID getPredecessorId() {
    return predecessorId;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  public String getRequestDigest() {
    return requestDigest;
  }

  public void setRequestDigest(String requestDigest) {
    this.requestDigest = requestDigest;
  }

  public String getContractVersion() {
    return contractVersion;
  }

  public void setContractVersion(String contractVersion) {
    this.contractVersion = contractVersion;
  }

  public String getActor() {
    return actor;
  }

  public String getReason() {
    return reason;
  }

  public long getExpectedBaseRevision() {
    return expectedBaseRevision;
  }

  public String getSourceUseReview() {
    return sourceUseReview;
  }

  public JsonNode getCorrectionsJson() {
    return correctionsJson;
  }

  public String getManifestChecksum() {
    return manifestChecksum;
  }

  public void setManifestChecksum(String manifestChecksum) {
    this.manifestChecksum = manifestChecksum;
  }

  public String getState() {
    return state;
  }

  public void supersede() {
    this.state = STATE_SUPERSEDED;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

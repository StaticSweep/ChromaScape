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
 * Durable request identity for one scheduling call, including coalesced aliases that created no new
 * job or preview row. A changed body reusing the request identifier is a conflict even when the
 * original call coalesced onto existing work.
 */
@Entity
@Table(name = "mouse_video_scheduling_requests")
public class MouseVideoSchedulingRequest {

  public static final String KIND_ANALYSIS = "ANALYSIS";
  public static final String KIND_PREVIEW = "PREVIEW";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "owner", nullable = false, length = 160)
  private String owner;

  @Column(name = "request_id", nullable = false, length = 80)
  private String requestId;

  @Column(name = "request_digest", nullable = false, length = 80)
  private String requestDigest;

  @Column(name = "kind", nullable = false, length = 24)
  private String kind;

  @Column(name = "source_id", columnDefinition = "uuid", nullable = false)
  private UUID sourceId;

  @Column(name = "analysis_id", columnDefinition = "uuid")
  private UUID analysisId;

  @Column(name = "preview_id", columnDefinition = "uuid")
  private UUID previewId;

  @Column(name = "job_id", columnDefinition = "uuid")
  private UUID jobId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected MouseVideoSchedulingRequest() {}

  public MouseVideoSchedulingRequest(
      String owner,
      String requestId,
      String requestDigest,
      String kind,
      UUID sourceId,
      UUID analysisId,
      UUID previewId,
      UUID jobId) {
    this.owner = owner;
    this.requestId = requestId;
    this.requestDigest = requestDigest;
    this.kind = kind;
    this.sourceId = sourceId;
    this.analysisId = analysisId;
    this.previewId = previewId;
    this.jobId = jobId;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getOwner() {
    return owner;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getRequestDigest() {
    return requestDigest;
  }

  public String getKind() {
    return kind;
  }

  public UUID getSourceId() {
    return sourceId;
  }

  public UUID getAnalysisId() {
    return analysisId;
  }

  public UUID getPreviewId() {
    return previewId;
  }

  public UUID getJobId() {
    return jobId;
  }
}

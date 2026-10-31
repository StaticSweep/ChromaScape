package com.chromascape.web.debug.tickdebugger.video.entity;

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
 * One exact source-frame preview. Identity pins the frozen frame-index content address, the decode
 * ordinal, the source PTS and the extraction method/bounds. Only a READY row may be served; the
 * bytes are content-addressed and shared, so the sweeper must treat a preview checksum as a live
 * reference.
 */
@Entity
@Table(name = "mouse_video_frame_previews")
public class MouseVideoFramePreview {

  public static final String METHOD_FRAME_PNG = "frame-png/v1";

  public static final String STATE_PENDING = "PENDING";
  public static final String STATE_READY = "READY";
  public static final String STATE_FAILED = "FAILED";
  public static final String STATE_CANCELLED = "CANCELLED";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "owner", nullable = false, length = 160)
  private String owner;

  @Column(name = "source_id", columnDefinition = "uuid", nullable = false)
  private UUID sourceId;

  @Column(name = "analysis_id", columnDefinition = "uuid", nullable = false)
  private UUID analysisId;

  @Column(name = "request_id", nullable = false, length = 80)
  private String requestId;

  @Column(name = "request_digest", nullable = false, length = 80)
  private String requestDigest;

  @Column(name = "index_checksum", nullable = false, length = 80)
  private String indexChecksum;

  @Column(name = "frame_ordinal", nullable = false)
  private long frameOrdinal;

  @Column(name = "frame_pts", length = 40)
  private String framePts;

  @Column(name = "frame_pts_time", length = 80)
  private String framePtsTime;

  @Column(name = "time_base_numerator", length = 40)
  private String timeBaseNumerator;

  @Column(name = "time_base_denominator", length = 40)
  private String timeBaseDenominator;

  @Column(name = "preview_method", nullable = false, length = 40)
  private String previewMethod;

  @Column(name = "max_width", nullable = false)
  private int maxWidth;

  @Column(name = "max_height", nullable = false)
  private int maxHeight;

  @Column(name = "source_width")
  private Integer sourceWidth;

  @Column(name = "source_height")
  private Integer sourceHeight;

  @Column(name = "output_width")
  private Integer outputWidth;

  @Column(name = "output_height")
  private Integer outputHeight;

  @Column(name = "rotation_degrees")
  private Integer rotationDegrees;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "crop_json", columnDefinition = "jsonb")
  private String cropJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "transform_json", columnDefinition = "jsonb")
  private String transformJson;

  @Column(name = "checksum", length = 80)
  private String checksum;

  @Column(name = "size_bytes")
  private Long sizeBytes;

  @Column(name = "media_type", length = 100)
  private String mediaType;

  @Column(name = "state", nullable = false, length = 16)
  private String state = STATE_PENDING;

  @Column(name = "job_id", columnDefinition = "uuid")
  private UUID jobId;

  @Column(name = "failure_code", length = 64)
  private String failureCode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "ready_at")
  private Instant readyAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MouseVideoFramePreview() {}

  public MouseVideoFramePreview(
      String owner,
      UUID sourceId,
      UUID analysisId,
      String requestId,
      String requestDigest,
      String indexChecksum,
      long frameOrdinal,
      String framePts,
      String framePtsTime,
      String timeBaseNumerator,
      String timeBaseDenominator,
      String previewMethod,
      int maxWidth,
      int maxHeight) {
    this.owner = owner;
    this.sourceId = sourceId;
    this.analysisId = analysisId;
    this.requestId = requestId;
    this.requestDigest = requestDigest;
    this.indexChecksum = indexChecksum;
    this.frameOrdinal = frameOrdinal;
    this.framePts = framePts;
    this.framePtsTime = framePtsTime;
    this.timeBaseNumerator = timeBaseNumerator;
    this.timeBaseDenominator = timeBaseDenominator;
    this.previewMethod = previewMethod;
    this.maxWidth = maxWidth;
    this.maxHeight = maxHeight;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
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

  public String getRequestId() {
    return requestId;
  }

  public String getRequestDigest() {
    return requestDigest;
  }

  public String getIndexChecksum() {
    return indexChecksum;
  }

  public long getFrameOrdinal() {
    return frameOrdinal;
  }

  public String getFramePts() {
    return framePts;
  }

  public String getFramePtsTime() {
    return framePtsTime;
  }

  public String getTimeBaseNumerator() {
    return timeBaseNumerator;
  }

  public String getTimeBaseDenominator() {
    return timeBaseDenominator;
  }

  public String getPreviewMethod() {
    return previewMethod;
  }

  public int getMaxWidth() {
    return maxWidth;
  }

  public int getMaxHeight() {
    return maxHeight;
  }

  public Integer getSourceWidth() {
    return sourceWidth;
  }

  public Integer getSourceHeight() {
    return sourceHeight;
  }

  public Integer getOutputWidth() {
    return outputWidth;
  }

  public Integer getOutputHeight() {
    return outputHeight;
  }

  public Integer getRotationDegrees() {
    return rotationDegrees;
  }

  public String getCropJson() {
    return cropJson;
  }

  public String getTransformJson() {
    return transformJson;
  }

  public String getChecksum() {
    return checksum;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public String getMediaType() {
    return mediaType;
  }

  public String getState() {
    return state;
  }

  public UUID getJobId() {
    return jobId;
  }

  public void setJobId(UUID jobId) {
    this.jobId = jobId;
    this.updatedAt = Instant.now();
  }

  public String getFailureCode() {
    return failureCode;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getReadyAt() {
    return readyAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Re-opens a failed preview for one new fenced attempt under the same pinned identity. */
  public void reopen() {
    this.state = STATE_PENDING;
    this.failureCode = null;
    this.updatedAt = Instant.now();
  }

  /** Publishes the exact bytes only after the extractor and fence succeeded. */
  public void ready(
      String checksum,
      long sizeBytes,
      String mediaType,
      int sourceWidth,
      int sourceHeight,
      int outputWidth,
      int outputHeight,
      int rotationDegrees,
      String cropJson,
      String transformJson) {
    this.checksum = checksum;
    this.sizeBytes = sizeBytes;
    this.mediaType = mediaType;
    this.sourceWidth = sourceWidth;
    this.sourceHeight = sourceHeight;
    this.outputWidth = outputWidth;
    this.outputHeight = outputHeight;
    this.rotationDegrees = rotationDegrees;
    this.cropJson = cropJson;
    this.transformJson = transformJson;
    this.state = STATE_READY;
    this.failureCode = null;
    this.readyAt = Instant.now();
    this.updatedAt = this.readyAt;
  }

  public void fail(String failureCode) {
    this.state = STATE_FAILED;
    this.failureCode = failureCode;
    this.updatedAt = Instant.now();
  }

  public void cancel() {
    this.state = STATE_CANCELLED;
    this.updatedAt = Instant.now();
  }
}

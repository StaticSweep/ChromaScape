package com.chromascape.web.debug.tickdebugger.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable external-video job with an idempotency key, a monotonically increasing generation and a
 * lease used to fence stale workers. Stage is tracked independently of status. Status transitions
 * are guarded by the service; late workers cannot publish after a generation change or
 * cancellation.
 */
@Entity
@Table(name = "mouse_video_jobs")
public class MouseVideoJob {

  public static final String KIND_IMPORT = "IMPORT";
  public static final String KIND_ANALYSIS = "ANALYSIS";
  public static final String KIND_PREVIEW = "PREVIEW";

  public static final String STAGE_ACQUIRE = "ACQUIRE";
  public static final String STAGE_PROBE = "PROBE";
  public static final String STAGE_INDEX = "INDEX";
  public static final String STAGE_ANALYSIS = "ANALYSIS";
  public static final String STAGE_PREVIEW = "PREVIEW";
  public static final String STAGE_PUBLISH = "PUBLISH";
  public static final String STAGE_DONE = "DONE";

  public static final String STATUS_QUEUED = "QUEUED";
  public static final String STATUS_RUNNING = "RUNNING";
  public static final String STATUS_SUCCEEDED = "SUCCEEDED";
  public static final String STATUS_FAILED = "FAILED";
  public static final String STATUS_TIMED_OUT = "TIMED_OUT";
  public static final String STATUS_CANCELLING = "CANCELLING";
  public static final String STATUS_CANCELLED = "CANCELLED";
  public static final String STATUS_NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "source_id", columnDefinition = "uuid", nullable = false)
  private UUID sourceId;

  @Column(name = "owner", nullable = false, length = 160)
  private String owner;

  @Column(name = "kind", nullable = false, length = 24)
  private String kind;

  @Column(name = "analysis_id", columnDefinition = "uuid")
  private UUID analysisId;

  @Column(name = "preview_id", columnDefinition = "uuid")
  private UUID previewId;

  @Column(name = "subject_key", length = 160)
  private String subjectKey;

  @Column(name = "stage", nullable = false, length = 24)
  private String stage = STAGE_ACQUIRE;

  @Column(name = "status", nullable = false, length = 24)
  private String status = STATUS_QUEUED;

  @Column(name = "request_id", nullable = false, length = 80)
  private String requestId;

  @Column(name = "request_digest", nullable = false, length = 80)
  private String requestDigest;

  @Column(name = "config_digest", nullable = false, length = 80)
  private String configDigest = "";

  @Column(name = "toolchain_digest", nullable = false, length = 80)
  private String toolchainDigest = "";

  @Column(name = "generation", nullable = false)
  private long generation = 1L;

  @Column(name = "lease_owner", length = 160)
  private String leaseOwner;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "attempt", nullable = false)
  private int attempt = 1;

  @Column(name = "progress", nullable = false)
  private int progress;

  @Column(name = "cancellation_revision", nullable = false)
  private long cancellationRevision;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "error_message", length = 1024)
  private String errorMessage;

  @Column(name = "cleanup_reason", length = 512)
  private String cleanupReason;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MouseVideoJob() {}

  public MouseVideoJob(
      UUID sourceId, String owner, String kind, String requestId, String requestDigest) {
    this.sourceId = sourceId;
    this.owner = owner;
    this.kind = kind;
    this.requestId = requestId;
    this.requestDigest = requestDigest;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public MouseVideoJob(
      UUID sourceId,
      String owner,
      String kind,
      String requestId,
      String requestDigest,
      UUID analysisId,
      UUID previewId,
      String subjectKey) {
    this(sourceId, owner, kind, requestId, requestDigest);
    this.analysisId = analysisId;
    this.previewId = previewId;
    this.subjectKey = subjectKey;
  }

  /** True once the job reached a state from which no further worker publication is allowed. */
  public boolean isTerminal() {
    return STATUS_SUCCEEDED.equals(status)
        || STATUS_FAILED.equals(status)
        || STATUS_TIMED_OUT.equals(status)
        || STATUS_CANCELLED.equals(status);
  }

  public boolean isSettled() {
    return isTerminal() || STATUS_NEEDS_RECONCILIATION.equals(status);
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

  public String getKind() {
    return kind;
  }

  public UUID getAnalysisId() {
    return analysisId;
  }

  public void setAnalysisId(UUID analysisId) {
    this.analysisId = analysisId;
  }

  public UUID getPreviewId() {
    return previewId;
  }

  public void setPreviewId(UUID previewId) {
    this.previewId = previewId;
  }

  public String getSubjectKey() {
    return subjectKey;
  }

  public void setSubjectKey(String subjectKey) {
    this.subjectKey = subjectKey;
  }

  public String getStage() {
    return stage;
  }

  public void setStage(String stage) {
    this.stage = stage;
    this.updatedAt = Instant.now();
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
    this.updatedAt = Instant.now();
  }

  public String getRequestId() {
    return requestId;
  }

  public String getRequestDigest() {
    return requestDigest;
  }

  public String getConfigDigest() {
    return configDigest;
  }

  public void setConfigDigest(String configDigest) {
    this.configDigest = configDigest;
  }

  public String getToolchainDigest() {
    return toolchainDigest;
  }

  public void setToolchainDigest(String toolchainDigest) {
    this.toolchainDigest = toolchainDigest;
  }

  public long getGeneration() {
    return generation;
  }

  public String getLeaseOwner() {
    return leaseOwner;
  }

  public void setLeaseOwner(String leaseOwner) {
    this.leaseOwner = leaseOwner;
  }

  public Instant getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  public void setLeaseExpiresAt(Instant leaseExpiresAt) {
    this.leaseExpiresAt = leaseExpiresAt;
  }

  public int getAttempt() {
    return attempt;
  }

  public int getProgress() {
    return progress;
  }

  public void setProgress(int progress) {
    this.progress = progress;
    this.updatedAt = Instant.now();
  }

  public long getCancellationRevision() {
    return cancellationRevision;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public String getCleanupReason() {
    return cleanupReason;
  }

  public void setCleanupReason(String cleanupReason) {
    this.cleanupReason =
        cleanupReason == null
            ? null
            : (cleanupReason.length() <= 512 ? cleanupReason : cleanupReason.substring(0, 512));
    this.updatedAt = Instant.now();
  }

  public long getVersion() {
    return version;
  }

  public boolean isRunning() {
    return STATUS_RUNNING.equals(status);
  }

  public boolean isCancelling() {
    return STATUS_CANCELLING.equals(status);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Records one additional delivery attempt (for example after a lease-expiry reclaim). */
  public void incrementAttempt() {
    this.attempt += 1;
    this.updatedAt = Instant.now();
  }

  /** Bumps the generation so any in-flight worker loses its publishing fence. */
  public void bumpGeneration() {
    this.generation += 1L;
    this.leaseOwner = null;
    this.leaseExpiresAt = null;
    this.updatedAt = Instant.now();
  }

  /**
   * Bumps the generation while retaining the current lease. Cancellation uses this so another
   * instance can still observe that a live worker owns the attempt until owned cleanup settles.
   */
  public void bumpGenerationPreservingLease() {
    this.generation += 1L;
    this.updatedAt = Instant.now();
  }

  /** Records a cancellation request; the revision fence rejects later publication attempts. */
  public void requestCancellation() {
    this.cancellationRevision += 1L;
    this.updatedAt = Instant.now();
  }

  public void fail(String code, String message) {
    this.errorCode = code;
    this.errorMessage = truncate(message);
    this.status = STATUS_FAILED;
    this.leaseOwner = null;
    this.leaseExpiresAt = null;
    this.updatedAt = Instant.now();
  }

  public void timeout(String code, String message) {
    this.errorCode = code;
    this.errorMessage = truncate(message);
    this.status = STATUS_TIMED_OUT;
    this.leaseOwner = null;
    this.leaseExpiresAt = null;
    this.updatedAt = Instant.now();
  }

  public void reconciliationRequired(String code, String message) {
    this.errorCode = code;
    this.errorMessage = truncate(message);
    this.status = STATUS_NEEDS_RECONCILIATION;
    this.leaseOwner = null;
    this.leaseExpiresAt = null;
    this.updatedAt = Instant.now();
  }

  private static String truncate(String message) {
    if (message == null) {
      return null;
    }
    return message.length() <= 1024 ? message : message.substring(0, 1024);
  }
}

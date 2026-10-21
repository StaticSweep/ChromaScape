package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoPublicErrors;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEvent;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEventRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable external-video job lifecycle. Every mutation locks the job row, checks the observed
 * generation and status conditionally, and only then changes state. A stale or terminal worker can
 * never publish: claims require QUEUED or an expired RUNNING lease, and finalization re-validates
 * the generation, lease owner and cancellation revision under the same lock.
 */
@Service
public class MouseVideoJobService {

  private final MouseVideoJobRepository jobs;
  private final MouseVideoJobEventRepository events;
  private final MouseVideoFramePreviewRepository previews;
  private final ObjectMapper mapper;

  /**
   * Request-scoped persistence context. A controller can hold a managed job instance loaded before
   * a concurrent worker settlement; locking the row alone does not refresh that instance's version.
   */
  @PersistenceContext private EntityManager entityManager;

  public MouseVideoJobService(
      MouseVideoJobRepository jobs,
      MouseVideoJobEventRepository events,
      MouseVideoFramePreviewRepository previews,
      ObjectMapper mapper) {
    this.jobs = jobs;
    this.events = events;
    this.previews = java.util.Objects.requireNonNull(previews, "previews");
    this.mapper = mapper;
  }

  /** Serializable job view for the API. */
  public record JobView(
      String id,
      String sourceId,
      String kind,
      String stage,
      String status,
      int progress,
      int attempt,
      long generation,
      long cancellationRevision,
      String errorCode,
      String errorMessage,
      String cleanupReason,
      String createdAt,
      String updatedAt) {}

  /** Result of an attempted claim: the caller must not execute when {@code acquired} is false. */
  public record ClaimResult(boolean acquired, MouseVideoJob job) {}

  @Transactional
  public MouseVideoJob create(
      UUID sourceId, String owner, String kind, String requestId, String requestDigest) {
    Optional<MouseVideoJob> existing = jobs.findByOwnerAndRequestId(owner, requestId);
    if (existing.isPresent()) {
      MouseVideoJob job = existing.get();
      if (!job.getRequestDigest().equals(requestDigest)) {
        throw new MouseVideoException(
            MouseVideoException.CODE_CONFLICT,
            "This request identifier was already used with different content");
      }
      return job;
    }
    MouseVideoJob job =
        jobs.save(new MouseVideoJob(sourceId, owner, kind, requestId, requestDigest));
    record(job, "QUEUED", null);
    return job;
  }

  @Transactional(readOnly = true)
  public MouseVideoJob requireOwned(UUID jobId, String owner) {
    return jobs.findByIdAndOwner(jobId, owner)
        .orElseThrow(
            () ->
                new MouseVideoException(
                    MouseVideoException.CODE_NOT_FOUND, "Job is not available to this owner"));
  }

  @Transactional(readOnly = true)
  public Optional<MouseVideoJob> find(UUID jobId) {
    return jobs.findById(jobId);
  }

  /**
   * Atomically claims a queued or lease-expired running job. The row is locked with {@code SELECT
   * ... FOR UPDATE}; a live lease owned by another instance is never stolen.
   */
  @Transactional
  public ClaimResult claim(UUID jobId, String leaseOwner, long leaseSeconds) {
    MouseVideoJob job = lockFresh(jobId);
    boolean queued = MouseVideoJob.STATUS_QUEUED.equals(job.getStatus());
    boolean reclaimable =
        MouseVideoJob.STATUS_RUNNING.equals(job.getStatus()) && !jobs.leaseValid(jobId);
    if (!queued && !reclaimable) {
      return new ClaimResult(false, job);
    }
    if (reclaimable) {
      job.incrementAttempt();
    }
    job.bumpGeneration();
    job.setStatus(MouseVideoJob.STATUS_RUNNING);
    job.setStage(MouseVideoJob.STAGE_PROBE);
    setLease(job, leaseOwner, leaseSeconds);
    record(job, queued ? "CLAIMED" : "RECLAIMED", null);
    return new ClaimResult(true, jobs.save(job));
  }

  /**
   * Refreshes the lease only while this generation still owns an unexpired RUNNING claim. The job
   * row is locked for the whole read-check-write so a concurrent progress or finalization
   * transaction cannot collide on the optimistic version.
   */
  @Transactional
  public MouseVideoJob heartbeat(
      UUID jobId, long generation, String leaseOwner, long leaseSeconds) {
    MouseVideoJob job = requireGenerationForUpdate(jobId, generation);
    if (!job.isRunning()
        || leaseOwner == null
        || !leaseOwner.equals(job.getLeaseOwner())
        || !jobs.leaseValid(jobId)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job lease is no longer owned by this worker");
    }
    setLease(job, leaseOwner, leaseSeconds);
    return jobs.save(job);
  }

  @Transactional
  public MouseVideoJob progress(UUID jobId, long generation, String stage, int progressValue) {
    return progress(jobId, generation, null, stage, progressValue);
  }

  /**
   * Records stage progress only while this exact generation still holds a valid, unexpired lease. A
   * stale worker whose lease expired or was taken over cannot keep mutating the job.
   */
  @Transactional
  public MouseVideoJob progress(
      UUID jobId, long generation, String leaseOwner, String stage, int progressValue) {
    MouseVideoJob job = requireGenerationForUpdate(jobId, generation);
    if (!job.isRunning()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job is not running; progress is refused");
    }
    if (leaseOwner != null
        && (!leaseOwner.equals(job.getLeaseOwner()) || !jobs.leaseValid(jobId))) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job lease is no longer owned by this worker");
    }
    if (stage != null) {
      job.setStage(stage);
    }
    job.setProgress(Math.max(0, Math.min(100, progressValue)));
    return jobs.save(job);
  }

  @Transactional
  public MouseVideoJob fail(
      UUID jobId, long generation, String code, String message, boolean timeout) {
    MouseVideoJob job = requireGenerationForUpdate(jobId, generation);
    if (job.isTerminal()) {
      return job;
    }
    if (timeout) {
      job.timeout(code, message);
      record(job, "TIMED_OUT", Map.of("code", String.valueOf(code)));
    } else {
      job.fail(code, message);
      record(job, "FAILED", Map.of("code", String.valueOf(code)));
    }
    return jobs.save(job);
  }

  /**
   * Requests cancellation. A queued job becomes terminal immediately; a running or already
   * cancelling job enters CANCELLING with a bumped generation so its worker loses publication
   * rights. The caller must confirm owned cleanup before reporting CANCELLED.
   */
  @Transactional
  public MouseVideoJob requestCancellation(UUID jobId, String owner, long expectedRevision) {
    MouseVideoJob job = lockFreshForOwner(jobId, owner);
    if (job.isSettled()) {
      return job;
    }
    if (job.getCancellationRevision() != expectedRevision) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Cancellation revision is out of date");
    }
    job.requestCancellation();
    job.bumpGenerationPreservingLease();
    if (MouseVideoJob.STATUS_QUEUED.equals(job.getStatus())) {
      job.setStatus(MouseVideoJob.STATUS_CANCELLED);
      settleBoundPreview(job);
      record(job, "CANCELLED", null);
    } else {
      job.setStatus(MouseVideoJob.STATUS_CANCELLING);
      record(job, "CANCELLING", null);
    }
    return jobs.save(job);
  }

  /** Confirms that owned worker cleanup completed; only then is CANCELLED reported. */
  @Transactional
  public MouseVideoJob confirmCancelled(UUID jobId, String owner) {
    MouseVideoJob job = lockFreshForOwner(jobId, owner);
    return finishCancellation(job);
  }

  /** Confirms cleanup only for the exact cancelled execution, never a replacement claim. */
  @Transactional
  public MouseVideoJob confirmExecutionCancelled(
      UUID jobId, long generation, long cancellationRevision, String leaseOwner) {
    MouseVideoJob job =
        requireExecutionForUpdate(jobId, generation, cancellationRevision, leaseOwner);
    return finishCancellation(job);
  }

  private MouseVideoJob finishCancellation(MouseVideoJob job) {
    if (job.isSettled()) {
      return job;
    }
    if (!job.isCancelling()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job is not being cancelled");
    }
    job.setStatus(MouseVideoJob.STATUS_CANCELLED);
    job.setLeaseOwner(null);
    job.setLeaseExpiresAt(null);
    settleBoundPreview(job);
    record(job, "CANCELLED", null);
    return jobs.save(job);
  }

  /**
   * Settles the preview bound to this job in the same transaction as the job's CANCELLED state.
   * Only a still-PENDING preview still owned by this job is cancelled, so a reopened preview
   * assigned to a replacement job is never overwritten by a stale settlement.
   */
  private void settleBoundPreview(MouseVideoJob job) {
    if (job.getPreviewId() == null) {
      return;
    }
    previews
        .findForUpdate(job.getPreviewId())
        .filter(
            preview ->
                MouseVideoFramePreview.STATE_PENDING.equals(preview.getState())
                    && (preview.getJobId() == null || preview.getJobId().equals(job.getId())))
        .ifPresent(
            preview -> {
              preview.cancel();
              previews.save(preview);
            });
  }

  /**
   * Records an ambiguous cleanup outcome; the slot stays occupied until explicit reconciliation.
   */
  @Transactional
  public MouseVideoJob confirmReconciliation(UUID jobId, String owner, String reason) {
    MouseVideoJob job = lockFreshForOwner(jobId, owner);
    if (job.isSettled()) {
      return job;
    }
    job.reconciliationRequired(MouseVideoException.CODE_CLEANUP_UNCONFIRMED, reason);
    job.setCleanupReason(reason);
    record(job, "RECONCILE_REQUIRED", Map.of("reason", String.valueOf(reason)));
    return jobs.save(job);
  }

  /**
   * Records uncertain cleanup only for the reporting execution, under the job row lock. User
   * cancellation preserves that execution's lease while advancing generation and cancellation
   * revision together; a replacement claim advances generation independently and is never changed.
   */
  @Transactional
  public MouseVideoJob reconcileExecution(
      UUID jobId, long generation, long cancellationRevision, String leaseOwner, String reason) {
    MouseVideoJob job =
        requireExecutionForUpdate(jobId, generation, cancellationRevision, leaseOwner);
    if (job.isSettled()) {
      return job;
    }
    job.reconciliationRequired(MouseVideoException.CODE_CLEANUP_UNCONFIRMED, reason);
    job.setCleanupReason(reason);
    record(job, "RECONCILE_REQUIRED", Map.of("reason", String.valueOf(reason)));
    return jobs.save(job);
  }

  private MouseVideoJob requireExecutionForUpdate(
      UUID jobId, long generation, long cancellationRevision, String leaseOwner) {
    MouseVideoJob job = lockFresh(jobId);
    if (job.isSettled()) {
      return job;
    }
    boolean sameClaim =
        job.getGeneration() == generation && job.getCancellationRevision() == cancellationRevision;
    boolean cancelledClaim =
        job.isCancelling()
            && job.getCancellationRevision() > cancellationRevision
            && job.getGeneration() - generation
                == job.getCancellationRevision() - cancellationRevision;
    if (leaseOwner == null
        || !leaseOwner.equals(job.getLeaseOwner())
        || (!sameClaim && !cancelledClaim)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT,
          "Job execution changed; stale cleanup cannot settle its replacement");
    }
    return job;
  }

  @Transactional(readOnly = true)
  public List<MouseVideoJob> unsettled() {
    return jobs.findByStatusIn(
        List.of(
            MouseVideoJob.STATUS_QUEUED,
            MouseVideoJob.STATUS_RUNNING,
            MouseVideoJob.STATUS_CANCELLING));
  }

  /**
   * Restart reconciliation. Queued jobs are re-queued. A running or cancelling job whose lease is
   * still unexpired belongs to another live instance and is left completely untouched, including
   * its generation, lease and workspace. Only jobs whose lease has expired are fenced and marked
   * reconciliation-required until explicit recovery; they are never assumed successful.
   *
   * <p>Candidate selection projects IDs only, avoiding a stale managed entity before acquiring its
   * row lock. Each candidate is then read under the lock so a concurrent heartbeat/claim is
   * observed before any mutation. IDs are ordered consistently to avoid opposite lock acquisition
   * order.
   */
  @Transactional
  public int reconcileOnStartup() {
    int reconciled = 0;
    for (UUID jobId :
        jobs.findIdsByStatusIn(
            List.of(
                MouseVideoJob.STATUS_QUEUED,
                MouseVideoJob.STATUS_RUNNING,
                MouseVideoJob.STATUS_CANCELLING))) {
      MouseVideoJob job = lockFreshOrNull(jobId);
      if (job == null || job.isSettled()) {
        continue;
      }
      if (MouseVideoJob.STATUS_QUEUED.equals(job.getStatus())) {
        job.setLeaseOwner(null);
        job.setLeaseExpiresAt(null);
        jobs.save(job);
        reconciled++;
        continue;
      }
      if (jobs.leaseValid(job.getId())) {
        // A live foreign instance owns this attempt; do not fence or rewrite it.
        continue;
      }
      job.bumpGeneration();
      job.reconciliationRequired(
          MouseVideoException.CODE_INTERRUPTED,
          "Interrupted by a server restart; awaiting explicit reconciliation");
      record(job, "RECONCILE_REQUIRED", null);
      jobs.save(job);
      reconciled++;
    }
    return reconciled;
  }

  /**
   * Fences running jobs whose lease has expired without a live owner. This closes the recovery gap
   * left when startup deliberately preserved a foreign lease and that instance then disappeared.
   * Expiry proves only that renewal stopped, so the outcome is reconciliation, not an automatic
   * re-run: an expired lease does not prove the previous process tree is gone.
   *
   * <p>Cancelling jobs are deliberately excluded while the owned worker is still terminating its
   * processes. Fencing here would race truthful CANCELLED confirmation with false reconciliation,
   * regardless of the remaining lease. An abandoned cancelling job is fenced by startup
   * reconciliation instead.
   */
  @Transactional
  public int reconcileExpiredClaims() {
    int reconciled = 0;
    for (UUID jobId : jobs.findIdsByStatusIn(List.of(MouseVideoJob.STATUS_RUNNING))) {
      MouseVideoJob job = lockFreshOrNull(jobId);
      if (job == null
          || job.isSettled()
          || !MouseVideoJob.STATUS_RUNNING.equals(job.getStatus())
          || jobs.leaseValid(job.getId())) {
        continue;
      }
      job.bumpGeneration();
      job.reconciliationRequired(
          MouseVideoException.CODE_INTERRUPTED,
          "Lease expired without confirmed cleanup; awaiting explicit reconciliation");
      record(job, "RECONCILE_REQUIRED", null);
      jobs.save(job);
      reconciled++;
    }
    return reconciled;
  }

  public JobView view(MouseVideoJob job) {
    UUID jobId = job.getId();
    String publicError = null;
    String publicCleanup = null;
    if (MouseVideoJob.STATUS_FAILED.equals(job.getStatus())
        || MouseVideoJob.STATUS_TIMED_OUT.equals(job.getStatus())) {
      publicError = MouseVideoPublicErrors.jobFailure(job.getErrorCode(), jobId);
    } else if (MouseVideoJob.STATUS_NEEDS_RECONCILIATION.equals(job.getStatus())) {
      publicError = MouseVideoPublicErrors.reconciliation(jobId);
      publicCleanup = MouseVideoPublicErrors.reconciliation(jobId);
    }
    return new JobView(
        jobId.toString(),
        job.getSourceId().toString(),
        job.getKind(),
        job.getStage(),
        job.getStatus(),
        job.getProgress(),
        job.getAttempt(),
        job.getGeneration(),
        job.getCancellationRevision(),
        job.getErrorCode(),
        publicError,
        publicCleanup,
        job.getCreatedAt() == null ? null : job.getCreatedAt().toString(),
        job.getUpdatedAt() == null ? null : job.getUpdatedAt().toString());
  }

  /**
   * True for tests and diagnostics; publication rights are enforced by the fenced write methods.
   */
  @Transactional(readOnly = true)
  public boolean mayPublish(UUID jobId, long generation) {
    return jobs.findById(jobId)
        .map(job -> job.getGeneration() == generation && job.isRunning() && !job.isCancelling())
        .orElse(false);
  }

  /** True while the database clock still considers the stored lease unexpired. */
  @Transactional(readOnly = true)
  public boolean leaseValid(UUID jobId) {
    return jobs.leaseValid(jobId);
  }

  /**
   * Locks the job row and discards any request-scoped stale copy. Without the refresh, Hibernate
   * returns the already-managed instance from the first-level cache and flushes its old version,
   * turning a concurrent worker settlement into an optimistic-lock 500.
   */
  private MouseVideoJob lockFreshOrNull(UUID jobId) {
    MouseVideoJob job = jobs.findForUpdate(jobId).orElse(null);
    if (job != null && entityManager != null && entityManager.contains(job)) {
      entityManager.refresh(job);
    }
    return job;
  }

  private MouseVideoJob lockFresh(UUID jobId) {
    MouseVideoJob job = lockFreshOrNull(jobId);
    if (job == null) {
      throw new MouseVideoException(MouseVideoException.CODE_NOT_FOUND, "Job is missing");
    }
    return job;
  }

  private MouseVideoJob lockFreshForOwner(UUID jobId, String owner) {
    MouseVideoJob job = lockFreshOrNull(jobId);
    if (job == null || !job.getOwner().equals(owner)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_NOT_FOUND, "Job is not available to this owner");
    }
    return job;
  }

  private MouseVideoJob requireGenerationForUpdate(UUID jobId, long generation) {
    MouseVideoJob job = lockFresh(jobId);
    if (job.getGeneration() != generation) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job generation changed; publication is fenced");
    }
    return job;
  }

  private void setLease(MouseVideoJob job, String leaseOwner, long leaseSeconds) {
    job.setLeaseOwner(leaseOwner);
    java.sql.Timestamp databaseTime = jobs.databaseNow();
    Instant now = databaseTime == null ? Instant.now() : databaseTime.toInstant();
    job.setLeaseExpiresAt(now.plusSeconds(Math.max(1L, leaseSeconds)));
  }

  private void record(MouseVideoJob job, String type, Map<String, Object> metadata) {
    events.save(
        new MouseVideoJobEvent(
            job.getId(),
            job.getGeneration(),
            type,
            mapper.valueToTree(metadata == null ? new LinkedHashMap<>() : metadata)));
  }
}

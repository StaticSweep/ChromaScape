package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchValidationJob;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchValidationJobRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every locked validation-job mutation. Claims require a fresh transaction and a database
 * lock; stage and terminal updates require the current lease token and attempt number, so a stale
 * worker can never publish, terminalize, or overwrite a cancellation.
 */
@Service
public class PatchValidationDispatcher {

  public static final String STATE_QUEUED = "QUEUED";
  public static final String STATE_CLAIMED = "CLAIMED";
  public static final String STATE_RUNNING = "RUNNING";
  public static final String STATE_CANCELLING = "CANCELLING";
  public static final String STATE_SUCCEEDED = "SUCCEEDED";
  public static final String STATE_FAILED = "FAILED";
  public static final String STATE_CANCELLED = "CANCELLED";

  public record Claim(String jobId, int attemptNumber, String leaseToken) {}

  private final Micro6PatchValidationJobRepository repository;

  public PatchValidationDispatcher(Micro6PatchValidationJobRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public Claim claim(String jobId, Instant leaseExpiresAt) {
    Micro6PatchValidationJob job =
        repository
            .findWithLockByJobId(jobId)
            .orElseThrow(
                () -> RecoveryException.notFound("JOB_NOT_FOUND", "Validation job is unavailable"));
    if (STATE_QUEUED.equals(job.getState())
        || (STATE_CANCELLING.equals(job.getState()) && job.getAttemptNumber() == 0)) {
      job.setAttemptNumber(job.getAttemptNumber() + 1);
      job.setState(STATE_CLAIMED);
      job.setWorkerId("worker-" + UUID.randomUUID());
      job.setLeaseToken(UUID.randomUUID().toString());
      job.setLeaseExpiresAt(leaseExpiresAt);
      job.setStartedAt(Instant.now());
      repository.save(job);
      return new Claim(job.getJobId(), job.getAttemptNumber(), job.getLeaseToken());
    }
    return null;
  }

  @Transactional
  public boolean heartbeat(
      String jobId, String leaseToken, int attemptNumber, Instant leaseExpiresAt) {
    Micro6PatchValidationJob job = repository.findByJobId(jobId).orElse(null);
    if (job == null
        || leaseToken == null
        || !leaseToken.equals(job.getLeaseToken())
        || attemptNumber != job.getAttemptNumber()
        || !hasLiveLease(job)
        || !isOwnedState(job.getState())) {
      return false;
    }
    job.setLeaseExpiresAt(leaseExpiresAt);
    job.setHeartbeatAt(Instant.now());
    repository.save(job);
    return true;
  }

  @Transactional
  public boolean stage(String jobId, String leaseToken, int attemptNumber, String stage) {
    Micro6PatchValidationJob job = repository.findByJobId(jobId).orElse(null);
    if (job == null
        || leaseToken == null
        || !leaseToken.equals(job.getLeaseToken())
        || attemptNumber != job.getAttemptNumber()
        || !hasLiveLease(job)
        || job.getCancelRequestedAt() != null
        || !isOwnedState(job.getState())) {
      return false;
    }
    job.setCurrentStage(stage);
    job.setStageStartedAt(Instant.now());
    job.setHeartbeatAt(Instant.now());
    if (STATE_CLAIMED.equals(job.getState())) {
      job.setState(STATE_RUNNING);
    }
    repository.save(job);
    return true;
  }

  /** Returns true when the caller still owns the job (lease valid, not cancelled-terminal). */
  public boolean owns(String jobId, String leaseToken, int attemptNumber) {
    Micro6PatchValidationJob job = repository.findByJobId(jobId).orElse(null);
    return job != null
        && leaseToken != null
        && leaseToken.equals(job.getLeaseToken())
        && attemptNumber == job.getAttemptNumber()
        && hasLiveLease(job)
        && job.getCancelRequestedAt() == null
        && isOwnedState(job.getState());
  }

  public boolean isCancelRequested(String jobId) {
    Micro6PatchValidationJob job = repository.findByJobId(jobId).orElse(null);
    return job != null && job.getCancelRequestedAt() != null;
  }

  @Transactional
  public boolean requestCancel(String jobId) {
    Micro6PatchValidationJob job =
        repository
            .findWithLockByJobId(jobId)
            .orElseThrow(
                () -> RecoveryException.notFound("JOB_NOT_FOUND", "Validation job is unavailable"));
    if (STATE_SUCCEEDED.equals(job.getState())
        || STATE_FAILED.equals(job.getState())
        || STATE_CANCELLED.equals(job.getState())) {
      return false;
    }
    job.setCancelRequestedAt(Instant.now());
    if (STATE_QUEUED.equals(job.getState())
        || STATE_CLAIMED.equals(job.getState())
        || STATE_RUNNING.equals(job.getState())) {
      job.setState(STATE_CANCELLING);
    }
    repository.save(job);
    return true;
  }

  @Transactional
  public void complete(
      String jobId,
      String leaseToken,
      String candidateId,
      String resultingTreeHash,
      int attemptNumber) {
    Micro6PatchValidationJob job =
        repository
            .findWithLockByJobId(jobId)
            .orElseThrow(
                () -> RecoveryException.notFound("JOB_NOT_FOUND", "Validation job is unavailable"));
    requireOwnership(job, leaseToken, attemptNumber);
    if (job.getCancelRequestedAt() != null) {
      terminalizeCancelled(job);
      return;
    }
    job.setState(STATE_SUCCEEDED);
    job.setCandidateId(candidateId);
    job.setResultingTreeHash(resultingTreeHash);
    job.setCompletedAt(Instant.now());
    job.setLeaseToken(null);
    repository.save(job);
  }

  @Transactional
  public void fail(
      String jobId,
      String leaseToken,
      String failureCode,
      String failureDetail,
      int attemptNumber) {
    Micro6PatchValidationJob job = repository.findWithLockByJobId(jobId).orElse(null);
    if (job == null) {
      return;
    }
    if (leaseToken == null
        || !leaseToken.equals(job.getLeaseToken())
        || attemptNumber != job.getAttemptNumber()
        || !hasLiveLease(job)) {
      // A stale worker outcome is discarded; recovery owns the record from here.
      return;
    }
    if (job.getCancelRequestedAt() != null) {
      terminalizeCancelled(job);
      return;
    }
    job.setState(STATE_FAILED);
    job.setFailureCode(failureCode);
    job.setFailureDetail(failureDetail);
    job.setCompletedAt(Instant.now());
    job.setLeaseToken(null);
    repository.save(job);
  }

  @Transactional
  public void terminalizeCancelled(Micro6PatchValidationJob job) {
    job.setState(STATE_CANCELLED);
    job.setFailureCode(RecoveryFailureCodes.CANCELLED);
    job.setCompletedAt(Instant.now());
    job.setLeaseToken(null);
    repository.save(job);
  }

  @Transactional
  public boolean requeueExpiredLease(String jobId, Instant now) {
    Micro6PatchValidationJob job = repository.findWithLockByJobId(jobId).orElse(null);
    if (job == null
        || job.getLeaseExpiresAt() == null
        || job.getLeaseExpiresAt().isAfter(now)
        || !isOwnedState(job.getState())) {
      return false;
    }
    if (job.getAttemptNumber() >= job.getMaxAttempts()) {
      job.setState(STATE_FAILED);
      job.setFailureCode(RecoveryFailureCodes.LEASE_EXPIRED);
      job.setFailureDetail("Lease expired with no live worker after the attempt budget");
      job.setCompletedAt(Instant.now());
      job.setLeaseToken(null);
      repository.save(job);
      return false;
    }
    job.setState(STATE_QUEUED);
    job.setWorkerId(null);
    job.setLeaseToken(null);
    job.setLeaseExpiresAt(null);
    repository.save(job);
    return true;
  }

  private void requireOwnership(
      Micro6PatchValidationJob job, String leaseToken, int attemptNumber) {
    if (leaseToken == null
        || !leaseToken.equals(job.getLeaseToken())
        || attemptNumber != job.getAttemptNumber()
        || !hasLiveLease(job)) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.WORKER_LOST, "The validation job is no longer owned by this worker");
    }
    if (!isOwnedState(job.getState())) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.WORKER_LOST, "The validation job is not in an owned state");
    }
  }

  static boolean isOwnedState(String state) {
    return STATE_QUEUED.equals(state)
        || STATE_CLAIMED.equals(state)
        || STATE_RUNNING.equals(state)
        || STATE_CANCELLING.equals(state);
  }

  private static boolean hasLiveLease(Micro6PatchValidationJob job) {
    return job.getLeaseExpiresAt() != null && job.getLeaseExpiresAt().isAfter(Instant.now());
  }

  public static boolean isTerminal(String state) {
    return STATE_SUCCEEDED.equals(state)
        || STATE_FAILED.equals(state)
        || STATE_CANCELLED.equals(state);
  }
}

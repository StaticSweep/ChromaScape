package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchValidationJob;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchValidationJobRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Restart and lease recovery for validation jobs: requeues unclaimed work, reclaims expired leases
 * into the attempt budget, terminalizes cancellations, and never infers success from a missing
 * process.
 */
@Service
public class PatchValidationRecoveryService {

  private final Micro6PatchValidationJobRepository jobRepository;
  private final PatchValidationDispatcher dispatcher;
  private final PatchValidationJobService jobService;
  private final BuilderGoalRecoveryProperties properties;

  public PatchValidationRecoveryService(
      Micro6PatchValidationJobRepository jobRepository,
      PatchValidationDispatcher dispatcher,
      PatchValidationJobService jobService,
      BuilderGoalRecoveryProperties properties) {
    this.jobRepository = jobRepository;
    this.dispatcher = dispatcher;
    this.jobService = jobService;
    this.properties = properties;
  }

  @Scheduled(fixedDelay = 60_000L)
  public void recover() {
    if (!properties.isIsolatedValidationEnabled()) {
      return;
    }
    List<Micro6PatchValidationJob> active =
        jobRepository.findByStateIn(
            List.of(
                PatchValidationDispatcher.STATE_QUEUED,
                PatchValidationDispatcher.STATE_CLAIMED,
                PatchValidationDispatcher.STATE_RUNNING,
                PatchValidationDispatcher.STATE_CANCELLING));
    Instant now = Instant.now();
    for (Micro6PatchValidationJob job : active) {
      String jobId = job.getJobId();
      if (job.getCancelRequestedAt() != null
          && (job.getLeaseExpiresAt() == null || job.getLeaseExpiresAt().isBefore(now))) {
        jobRepository
            .findWithLockByJobId(jobId)
            .filter(current -> current.getCancelRequestedAt() != null)
            .filter(
                current ->
                    current.getLeaseExpiresAt() == null
                        || current.getLeaseExpiresAt().isBefore(Instant.now()))
            .ifPresent(dispatcher::terminalizeCancelled);
        continue;
      }
      if (PatchValidationDispatcher.STATE_QUEUED.equals(job.getState())) {
        jobService.offer(jobId);
        continue;
      }
      boolean leaseExpired =
          job.getLeaseExpiresAt() != null && job.getLeaseExpiresAt().isBefore(now);
      if (leaseExpired && dispatcher.requeueExpiredLease(jobId, Instant.now())) {
        jobService.offer(jobId);
      }
    }
  }
}

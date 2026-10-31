package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's way out of NEEDS_RECONCILIATION. Expiry and restart only ever create such jobs, and
 * each keeps an admission slot until resolved; this settles one after {@link
 * MouseVideoCleanupProof} shows nothing it may have started is still alive. It never re-runs the
 * job: the owner schedules new work as usual.
 */
@Service
public class MouseVideoReconciliationService {

  private final MouseVideoJobService jobService;
  private final MouseVideoCleanupProof cleanupProof;
  private final MouseVideoAnalysisRepository analyses;

  public MouseVideoReconciliationService(
      MouseVideoJobService jobService,
      MouseVideoCleanupProof cleanupProof,
      MouseVideoAnalysisRepository analyses) {
    this.jobService = jobService;
    this.cleanupProof = cleanupProof;
    this.analyses = analyses;
  }

  /**
   * Proves cleanup, then settles the job. The job's analysis is failed only while still PENDING and
   * not claimed by another unsettled job, so a replacement run that reused it is untouched.
   *
   * @throws MouseVideoException {@code CONFLICT} with the blocker when cleanup is not proven
   */
  @Transactional
  public MouseVideoJob reconcile(UUID jobId, String owner) {
    MouseVideoJob job = jobService.requireOwned(jobId, owner);
    if (!MouseVideoJob.STATUS_NEEDS_RECONCILIATION.equals(job.getStatus())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job does not need reconciliation");
    }
    MouseVideoCleanupProof.Result proof = cleanupProof.prove(jobId);
    if (!proof.proven()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Cleanup is not proven: " + proof.blocker());
    }
    MouseVideoJob settled = jobService.resolveReconciliation(jobId, owner, proof.evidence());
    UUID analysisId = settled.getAnalysisId();
    if (analysisId != null
        && jobService.unsettled().stream()
            .noneMatch(other -> analysisId.equals(other.getAnalysisId()))) {
      analyses
          .findById(analysisId)
          .filter(analysis -> MouseVideoAnalysis.STATUS_PENDING.equals(analysis.getStatus()))
          .ifPresent(
              analysis -> {
                analysis.fail();
                analyses.save(analysis);
              });
    }
    return settled;
  }
}

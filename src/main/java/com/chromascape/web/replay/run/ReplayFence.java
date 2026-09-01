package com.chromascape.web.replay.run;

import com.chromascape.web.replay.run.entity.ReplayAttempt;
import java.util.UUID;

/**
 * Immutable fencing token created exactly once at claim time. The worker must pass this token to
 * every worker-owned persistence operation; {@code ReplayWorkerPersistenceService} validates it
 * against a locked attempt row on the database clock before any dependent write.
 *
 * @param runId the claimed run
 * @param attemptId the attempt backing this execution
 * @param attemptNo monotonically increasing attempt number
 * @param fencingGeneration fencing generation; incremented by recovery so stale workers can never
 *     renew or write again
 * @param workerIdentity lease owner recorded at claim time
 */
public record ReplayFence(
    UUID runId, UUID attemptId, int attemptNo, int fencingGeneration, String workerIdentity) {

  /** Builds the token from the freshly created attempt row. */
  public static ReplayFence of(ReplayAttempt attempt) {
    return new ReplayFence(
        attempt.getRunId(),
        attempt.getId(),
        attempt.getAttemptNo(),
        attempt.getFencingGeneration(),
        attempt.getLeaseOwner());
  }
}

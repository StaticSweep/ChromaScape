package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.run.ReplayFence;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary for the scheduled heartbeat. The fenced renewal is a {@code @Modifying}
 * native query: without an active transaction real JPA/PostgreSQL rejects it, which previously made
 * every renewal fail while the heartbeat treated the exception as transient.
 *
 * <p>{@link Propagation#REQUIRES_NEW} isolates each renewal in its own transaction, so a successful
 * renewal commits even while other work is in flight. The result reports the database-clock
 * timestamps actually written, never wall-clock guesses.
 */
@Service
public class ReplayLeaseRenewalService {

  private final ReplayAttemptRepository attemptRepository;
  private final int leaseDurationSeconds;

  @Autowired
  public ReplayLeaseRenewalService(
      ReplayAttemptRepository attemptRepository, ReplayProperties properties) {
    this.attemptRepository = attemptRepository;
    this.leaseDurationSeconds = properties.getWorker().getLeaseDurationSeconds();
  }

  /** Test constructor with explicit lease duration. */
  ReplayLeaseRenewalService(ReplayAttemptRepository attemptRepository, int leaseDurationSeconds) {
    this.attemptRepository = attemptRepository;
    this.leaseDurationSeconds = leaseDurationSeconds;
  }

  /**
   * Result of one renewal against the live attempt row.
   *
   * @param renewed true only when exactly one row matched the fenced predicate
   * @param databaseHeartbeatAt heartbeat_at written by the database clock (null when lost)
   * @param databaseLeaseExpiresAt lease_expires_at written by the database clock (null when lost)
   */
  public record RenewalResult(
      boolean renewed, Instant databaseHeartbeatAt, Instant databaseLeaseExpiresAt) {

    public static RenewalResult lost() {
      return new RenewalResult(false, null, null);
    }
  }

  /**
   * Renews the lease inside a fresh transaction. Returns {@link RenewalResult#lost()} for zero
   * updated rows (authoritative stale-fence signal) and propagates database exceptions to the
   * caller — the heartbeat component decides retry versus poisoning from monotonic elapsed time.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RenewalResult renew(ReplayFence fence) {
    int updated =
        attemptRepository.renewLeaseFenced(
            fence.attemptId(),
            fence.runId(),
            fence.fencingGeneration(),
            fence.workerIdentity(),
            leaseDurationSeconds);
    if (updated != 1) {
      return RenewalResult.lost();
    }
    return attemptRepository
        .findById(fence.attemptId())
        .map(
            attempt ->
                new RenewalResult(
                    true,
                    toInstant(attempt.getHeartbeatAt()),
                    toInstant(attempt.getLeaseExpiresAt())))
        .orElse(RenewalResult.lost());
  }

  private static Instant toInstant(LocalDateTime localDateTime) {
    return localDateTime != null ? localDateTime.atZone(ZoneId.systemDefault()).toInstant() : null;
  }
}

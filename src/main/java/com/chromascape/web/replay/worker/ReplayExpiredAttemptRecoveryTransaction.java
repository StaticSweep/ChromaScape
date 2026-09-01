package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional core of lease-expiry recovery. Commits attempt expiry and run terminalization in
 * ONE transaction that deliberately does nothing else:
 *
 * <ol>
 *   <li>Lock the attempt row ({@code FOR UPDATE}); require {@code RUNNING}.
 *   <li>Expire it atomically on the database clock; zero updated rows means a heartbeat or another
 *       recovery won — abort with nothing written.
 *   <li>Lock the run row second (fixed lock order). Already-terminal runs abort here, leaving the
 *       attempt expiry committed.
 *   <li>Capture ONE PostgreSQL timestamp and resolve the terminal decision: {@code CANCELLING}
 *       &rarr; CANCELLED/CANCELLED/CANCELLED_AFTER_LEASE_EXPIRY; past the database-clock deadline
 *       &rarr; TIMED_OUT/TIMED_OUT/RUN_DEADLINE_EXCEEDED; otherwise
 *       FAILED/INFRASTRUCTURE_FAILURE/LEASE_EXPIRED.
 *   <li>Terminalize the run with the captured timestamp and return the decision.
 * </ol>
 *
 * This transaction never reads or writes mismatches, checkpoints, artifacts, or reports — those
 * belong to {@link ReplayRecoveryReportWriter}, which runs afterwards in its own transaction so a
 * report failure can never mark this transaction rollback-only and undo terminalization. Database
 * exceptions propagate to the orchestrator untouched.
 */
@Service
public class ReplayExpiredAttemptRecoveryTransaction {

  private static final Logger log =
      LoggerFactory.getLogger(ReplayExpiredAttemptRecoveryTransaction.class);

  private final ReplayAttemptRepository attemptRepository;
  private final ReplayRunRepository runRepository;
  private final ReplayRunService runService;
  private final ReplayProperties properties;

  public ReplayExpiredAttemptRecoveryTransaction(
      ReplayAttemptRepository attemptRepository,
      ReplayRunRepository runRepository,
      ReplayRunService runService,
      ReplayProperties properties) {
    this.attemptRepository = attemptRepository;
    this.runRepository = runRepository;
    this.runService = runService;
    this.properties = properties;
  }

  /**
   * Recovers one expired attempt together with its run inside one committed transaction.
   *
   * <p>{@code REQUIRES_NEW} makes the commit boundary EXPLICIT: the attempt-expiry and run
   * terminalization below ALWAYS commit in their own transaction, even when this bean is invoked
   * from a caller that itself runs inside a transaction. A plain {@code REQUIRED} method would join
   * that outer transaction and keep the run row locked until the caller commits — the recovery
   * report writer (also {@code REQUIRES_NEW}) would then block on the same row in the suspended
   * transaction, self-deadlocking. With {@code REQUIRES_NEW}, terminalization commits before the
   * report writer ever starts, and a report failure can never roll it back.
   *
   * @return the decision describing what was committed, or empty when another heartbeat/recovery
   *     won the race or the run was already terminal
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<RecoveredRunDecision> recover(UUID attemptId) {
    // 1. Lock the attempt row first (fixed lock order: attempt then run).
    ReplayAttempt locked = attemptRepository.findByIdForUpdate(attemptId).orElse(null);
    if (locked == null || !"RUNNING".equals(locked.getState())) {
      return Optional.empty();
    }
    // 2. Authoritative database-clock expiry gate; bumps the fencing generation and clears the
    // lease so no scheduler can renew a dead attempt.
    if (attemptRepository.expireAttemptAtomically(attemptId) != 1) {
      return Optional.empty();
    }
    // 3. Lock the run row second. If it is already terminal the attempt expiry still commits.
    ReplayRun run = runRepository.findByIdForUpdate(locked.getRunId()).orElse(null);
    if (run == null || run.isTerminal()) {
      return Optional.empty();
    }

    // 4. One captured PostgreSQL timestamp for every terminal stamp of this recovery.
    LocalDateTime dbNow = runRepository.currentTimestamp();
    boolean deadlineExceeded =
        Boolean.TRUE.equals(
            runRepository.isDeadlineExceeded(
                run.getId(), properties.getWorker().getRunTimeoutSeconds()));
    TerminalResolution resolution = resolveTerminalResult(run, deadlineExceeded);

    // 5. Terminalize with the exact captured timestamp (never the JVM clock).
    runService.applyTransition(run, resolution.state(), null, dbNow);
    run.setOutcome(resolution.outcome());
    run.setTerminalReason(resolution.reason());
    run.setTerminalAt(dbNow);
    runRepository.save(run);

    log.warn(
        "Recovered run {} attempt {} -> {} outcome={} reason={}",
        run.getId(),
        attemptId,
        resolution.state(),
        resolution.outcome(),
        resolution.reason());
    return Optional.of(
        new RecoveredRunDecision(
            run.getId(),
            attemptId,
            resolution.state(),
            resolution.outcome(),
            resolution.reason(),
            dbNow));
  }

  private record TerminalResolution(ReplayRunState state, ReplayOutcome outcome, String reason) {}

  private TerminalResolution resolveTerminalResult(ReplayRun run, boolean deadlineExceeded) {
    if (run.getState() == ReplayRunState.CANCELLING) {
      return new TerminalResolution(
          ReplayRunState.CANCELLED, ReplayOutcome.CANCELLED, "CANCELLED_AFTER_LEASE_EXPIRY");
    }
    if (deadlineExceeded) {
      return new TerminalResolution(
          ReplayRunState.TIMED_OUT, ReplayOutcome.TIMED_OUT, "RUN_DEADLINE_EXCEEDED");
    }
    return new TerminalResolution(
        ReplayRunState.FAILED, ReplayOutcome.INFRASTRUCTURE_FAILURE, "LEASE_EXPIRED");
  }
}

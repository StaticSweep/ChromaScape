package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.api.dto.MismatchLevel;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayMismatch;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.entity.StateHashSample;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY component allowed to insert a recovery/pre-execution-cancellation-adjacent recovery
 * report row, in its own {@code REQUIRES_NEW} transaction.
 *
 * <p>Per-run protocol (in order, inside ONE fresh transaction):
 *
 * <ol>
 *   <li>Acquire the transaction-scoped PostgreSQL advisory lock for the run; when another
 *       transaction already owns it, SKIP without touching anything.
 *   <li>Lock the run row ({@code findByIdForUpdate}).
 *   <li>Recheck that the run is still terminal and still has no report — both may have changed
 *       between candidate selection and this transaction.
 *   <li>Read COMMITTED evidence only (counts via aggregates, last sample bounded, FINALIZED
 *       artifact rows only).
 *   <li>Prepare the canonical report and INSERT it with {@code saveAndFlush}.
 * </ol>
 *
 * <p>Exception contract: database/constraint exceptions from {@code saveAndFlush} are NEVER caught
 * here. They propagate out of the transactional method so Spring marks the transaction rolled back
 * and the non-transactional orchestrator handles unique-race winner verification.
 *
 * <p>Must be invoked through the Spring proxy (injected bean), never through {@code this}.
 */
@Service
public class ReplayRecoveryReportTransaction {

  private static final Logger log = LoggerFactory.getLogger(ReplayRecoveryReportTransaction.class);

  /** Outcome of one attempted recovery-report write, fully classified. */
  public enum WriteResult {
    /** This transaction inserted the report. */
    INSERTED,
    /** A report already existed after locking (winner accepted inside the transaction). */
    ALREADY_PRESENT,
    /** The transaction-scoped advisory lock was held elsewhere; nothing written. */
    LOCK_CONTENDED,
    /** The run row vanished or is no longer terminal; not eligible for a report. */
    INELIGIBLE
  }

  private final ReplayRunRepository runRepository;
  private final ReplayAttemptRepository attemptRepository;
  private final StateHashSampleRepository hashSampleRepository;
  private final ReplayCheckpointRepository checkpointRepository;
  private final ArtifactRefRepository artifactRefRepository;
  private final ReplayReportRepository reportRepository;
  private final ReplayMismatchRepository mismatchRepository;
  private final ReplayReportService reportService;

  public ReplayRecoveryReportTransaction(
      ReplayRunRepository runRepository,
      ReplayAttemptRepository attemptRepository,
      StateHashSampleRepository hashSampleRepository,
      ReplayCheckpointRepository checkpointRepository,
      ArtifactRefRepository artifactRefRepository,
      ReplayReportRepository reportRepository,
      ReplayMismatchRepository mismatchRepository,
      ReplayReportService reportService) {
    this.runRepository = runRepository;
    this.attemptRepository = attemptRepository;
    this.hashSampleRepository = hashSampleRepository;
    this.checkpointRepository = checkpointRepository;
    this.artifactRefRepository = artifactRefRepository;
    this.reportRepository = reportRepository;
    this.mismatchRepository = mismatchRepository;
    this.reportService = reportService;
  }

  /**
   * Inserts the missing report for an already-terminal run, or reports why it did not.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException when a concurrent writer won
   *     the insert race; propagates untouched so the originating transaction stays rollback-only
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public WriteResult writeIfAbsent(UUID runId) {
    // 1. Transaction-scoped advisory lock: loser skips without blocking either instance.
    Boolean locked = runRepository.tryAdvisoryLockForRun(runId);
    if (!Boolean.TRUE.equals(locked)) {
      return WriteResult.LOCK_CONTENDED;
    }
    // 2. Lock the run row (attempt → run ordering is preserved by callers that also expire
    // attempts; here only the run is touched).
    ReplayRun run = runRepository.findByIdForUpdate(runId).orElse(null);
    if (run == null || !run.isTerminal()) {
      return WriteResult.INELIGIBLE;
    }
    // 3. Recheck absence AFTER locking: state may have advanced past the unlocked scan.
    if (reportRepository.findByRunId(runId).isPresent()) {
      return WriteResult.ALREADY_PRESENT;
    }

    // 4. Committed evidence only.
    ReplayAttempt attempt =
        attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId).orElse(null);
    long executedActions = hashSampleRepository.countByIdRunId(runId);
    StateHashSample last =
        hashSampleRepository.findTopByIdRunIdOrderByIdTickDescIdSequenceDesc(runId).orElse(null);
    int checkpointCount = (int) checkpointRepository.countByRunId(runId);
    List<String> finalizedChecksums =
        artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_FINALIZED).stream()
            .map(ArtifactRef::getChecksum)
            .distinct()
            .sorted()
            .toList();
    ReplayMismatch firstHard =
        mismatchRepository
            .findFirstByRunIdAndLevelOrderByFirstTickAscTickSequenceAscCreatedAtAscIdAsc(
                runId, MismatchLevel.HARD)
            .orElse(null);

    ReplayOutcome outcome =
        run.getOutcome() != null ? run.getOutcome() : fallbackOutcome(run.getState());
    String reason =
        run.getTerminalReason() != null ? run.getTerminalReason() : fallbackReason(run.getState());

    var prepared =
        reportService.prepareReport(
            run,
            attempt,
            outcome,
            reason,
            (int) Math.min(executedActions, Integer.MAX_VALUE),
            checkpointCount,
            last != null ? last.getStateHash() : null,
            last != null ? last.getChainHash() : null,
            finalizedChecksums,
            run.getTerminalAt(),
            firstHard);

    // 5. Insert. Any constraint violation escapes WITHOUT being caught in this transaction.
    reportRepository.saveAndFlush(reportService.materialize(prepared, run, attempt));
    log.info(
        "Inserted recovery report for run {} outcome={} reason={}", run.getId(), outcome, reason);
    return WriteResult.INSERTED;
  }

  private static ReplayOutcome fallbackOutcome(
      com.chromascape.web.replay.api.dto.ReplayRunState state) {
    return switch (state) {
      case SUCCEEDED -> ReplayOutcome.PASSED;
      case FAILED -> ReplayOutcome.INFRASTRUCTURE_FAILURE;
      case CANCELLED -> ReplayOutcome.CANCELLED;
      case TIMED_OUT -> ReplayOutcome.TIMED_OUT;
      default -> ReplayOutcome.INFRASTRUCTURE_FAILURE;
    };
  }

  private static String fallbackReason(com.chromascape.web.replay.api.dto.ReplayRunState state) {
    return switch (state) {
      case SUCCEEDED -> null;
      case CANCELLED -> "CANCELLED_AFTER_LEASE_EXPIRY";
      case TIMED_OUT -> "RUN_DEADLINE_EXCEEDED";
      default -> "LEASE_EXPIRED";
    };
  }
}

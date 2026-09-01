package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.api.dto.ReplayRunState;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-transactional lease-expiry recovery orchestrator.
 *
 * <p>{@link #recover} is annotated {@code NOT_SUPPORTED} so the orchestrator itself is NEVER
 * executing inside a caller transaction: even when a scheduler or test wraps the call in its own
 * {@code TransactionTemplate}, the caller transaction is suspended for the duration of this method.
 * The transactional core ({@link ReplayExpiredAttemptRecoveryTransaction}) then runs in its own
 * {@code REQUIRES_NEW} transaction and commits attempt expiry and run terminalization first; only
 * after that transaction returns does this orchestrator invoke {@link ReplayRecoveryReportWriter}
 * (a separate bean, so {@code REQUIRES_NEW} proxying applies). A report failure therefore leaves
 * the committed terminal state intact and is logged with run and attempt ids; missing-report
 * reconciliation retries the report later. Database errors from the core transaction propagate to
 * the scheduler untouched so the affected attempt is retried on the next scan.
 */
@Service
public class ReplayExpiredAttemptRecoveryService {

  private static final Logger log =
      LoggerFactory.getLogger(ReplayExpiredAttemptRecoveryService.class);

  private final ReplayExpiredAttemptRecoveryTransaction recoveryTransaction;
  private final ReplayRecoveryReportWriter reportWriter;

  public ReplayExpiredAttemptRecoveryService(
      ReplayExpiredAttemptRecoveryTransaction recoveryTransaction,
      ReplayRecoveryReportWriter reportWriter) {
    this.recoveryTransaction = recoveryTransaction;
    this.reportWriter = reportWriter;
  }

  /**
   * Recovers one expired attempt together with its run.
   *
   * <p>{@code NOT_SUPPORTED}: any caller transaction is suspended while this orchestrator runs, so
   * the terminalizing {@code REQUIRES_NEW} core and the report writer can never join a caller
   * transaction that still holds the run lock.
   *
   * @return the terminal state applied, or empty when another heartbeat/recovery won the race or
   *     the run was already terminal
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Optional<ReplayRunState> recover(UUID attemptId) {
    Optional<RecoveredRunDecision> committed = recoveryTransaction.recover(attemptId);
    if (committed.isEmpty()) {
      return Optional.empty();
    }
    RecoveredRunDecision decision = committed.get();
    try {
      reportWriter.write(decision);
    } catch (Exception e) {
      // The run stays terminal regardless: reconciliation repairs the missing report.
      log.warn(
          "Recovery report could not be written for run {} attempt {}: {}",
          decision.runId(),
          decision.attemptId(),
          e.getMessage());
    }
    return Optional.of(decision.state());
  }
}

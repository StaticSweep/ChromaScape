package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * NON-transactional orchestrator for recovery-report writes. Delegates the actual insert to {@link
 * ReplayRecoveryReportTransaction} (one {@code REQUIRES_NEW} transaction per run) so this bean
 * never holds a database transaction while report rows are written.
 *
 * <p>Unique-race protocol: when the insert transaction loses to a concurrent writer, the constraint
 * exception propagates OUT of that transaction untouched (it is rollback-only by the time anything
 * could catch it). THIS orchestrator catches {@code DataIntegrityViolationException} / {@code
 * UnexpectedRollbackException}, fetches the committed winner in a FRESH read-only transaction via
 * {@link ReplayRecoveryReportWinnerReader}, and accepts it ONLY when canonical bytes, logical
 * checksum, run id, outcome, and terminal timestamp all match expectations derived from committed
 * run state. Anything else fails closed.
 */
@Service
public class ReplayRecoveryReportWriter {

  private static final Logger log = LoggerFactory.getLogger(ReplayRecoveryReportWriter.class);

  private final ReplayRecoveryReportTransaction writeTransaction;
  private final ReplayRecoveryReportWinnerReader winnerReader;
  private final ReplayRunRepository runRepository;
  private final CanonicalProjectionService projectionService;
  private final ObjectMapper mapper;

  public ReplayRecoveryReportWriter(
      ReplayRecoveryReportTransaction writeTransaction,
      ReplayRecoveryReportWinnerReader winnerReader,
      ReplayRunRepository runRepository,
      CanonicalProjectionService projectionService,
      ObjectMapper mapper) {
    this.writeTransaction = writeTransaction;
    this.winnerReader = winnerReader;
    this.runRepository = runRepository;
    this.projectionService = projectionService;
    this.mapper = mapper;
  }

  /**
   * Fully classified outcome of one repair attempt at the orchestration boundary.
   *
   * <ul>
   *   <li>{@link #INSERTED}: this invocation inserted the report.
   *   <li>{@link #CONCURRENT_WINNER_ACCEPTED}: a concurrent writer inserted an identical, verified
   *       winner; treated as success, never as failure.
   *   <li>{@link #ALREADY_PRESENT}: a report existed once the writer held the locks.
   *   <li>{@link #LOCK_CONTENDED}: another instance owns the per-run advisory lock right now; a
   *       skip, NOT a failure — a later pass retries.
   *   <li>{@link #INELIGIBLE}: the run is missing or no longer terminal; reported separately.
   * </ul>
   */
  public enum RepairOutcome {
    INSERTED,
    CONCURRENT_WINNER_ACCEPTED,
    ALREADY_PRESENT,
    LOCK_CONTENDED,
    INELIGIBLE
  }

  /**
   * Ensures exactly one recovery report exists for a terminal run.
   *
   * @return the classified outcome; only real exceptions map to {@code failed} upstream
   * @throws ReplayException CHECKSUM_MISMATCH when a race winner exists but does not match
   *     committed run evidence, or REPORT_PREPARATION_FAILED on any other failure
   */
  public RepairOutcome repair(UUID runId) {
    try {
      ReplayRecoveryReportTransaction.WriteResult result = writeTransaction.writeIfAbsent(runId);
      // Enum switch labels must be unqualified; map the transaction result explicitly.
      return switch (result) {
        case INSERTED -> RepairOutcome.INSERTED;
        case ALREADY_PRESENT -> RepairOutcome.ALREADY_PRESENT;
        case LOCK_CONTENDED -> RepairOutcome.LOCK_CONTENDED;
        case INELIGIBLE -> RepairOutcome.INELIGIBLE;
      };
    } catch (DataIntegrityViolationException | UnexpectedRollbackException race) {
      // The losing transaction is already rolled back; verify the committed winner freshly.
      return acceptIdenticalWinnerOrThrow(runId, race);
    }
  }

  /** Legacy entry point retained for the lease-expiry recovery path. */
  public void write(RecoveredRunDecision decision) {
    repair(decision.runId());
  }

  private RepairOutcome acceptIdenticalWinnerOrThrow(UUID runId, RuntimeException cause) {
    ReplayReport winner = winnerReader.findCommittedReport(runId).orElse(null);
    if (winner == null) {
      // Constraint fired on something else (e.g. checksum uniqueness across runs): fail closed.
      throw new ReplayException(
          ReplayErrorCode.REPORT_PREPARATION_FAILED,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Recovery report insert failed without a usable concurrent winner",
          "Missing-report reconciliation will retry",
          null,
          cause);
    }
    if (!isWinnerConsistentWithRun(winner)) {
      throw new ReplayException(
          ReplayErrorCode.CHECKSUM_MISMATCH,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Concurrent recovery report for run " + runId + " does not match committed run evidence",
          null,
          null,
          cause);
    }
    log.debug("Recovery report for run {} already existed (race winner accepted)", runId);
    return RepairOutcome.CONCURRENT_WINNER_ACCEPTED;
  }

  /**
   * Winner acceptance criteria, ALL mandatory: stored canonical JSON parses and its logical
   * checksum recomputes exactly; run id matches; outcome and terminal timestamp equal the committed
   * run row.
   */
  private boolean isWinnerConsistentWithRun(ReplayReport winner) {
    ReplayRun run = runRepository.findById(winner.getRunId()).orElse(null);
    if (run == null || !run.isTerminal()) {
      return false;
    }
    if (!winner.getRunId().equals(run.getId())) {
      return false;
    }
    // Database timestamps are UTC by contract; never convert through the JVM zone.
    var expectedOutcome = run.getOutcome();
    if (expectedOutcome == null || winner.getOutcome() != expectedOutcome) {
      return false;
    }
    if (winner.getTerminalAt() == null
        || run.getTerminalAt() == null
        || !winner
            .getTerminalAt()
            .equals(run.getTerminalAt().toInstant(java.time.ZoneOffset.UTC))) {
      return false;
    }
    String stored = winner.getReportJson();
    if (stored == null || winner.getReportChecksum() == null) {
      return false;
    }
    try {
      JsonNode doc = mapper.readTree(stored);
      if (!(doc instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
        return false;
      }
      JsonNode checksumField = obj.remove("reportChecksum");
      if (checksumField == null || !checksumField.isTextual()) {
        return false;
      }
      String recomputed = projectionService.sha256Hex(projectionService.canonicalJsonBytes(obj));
      return recomputed.equals(checksumField.asText())
          && winner.getReportChecksum().equals(checksumField.asText());
    } catch (Exception e) {
      return false;
    }
  }
}

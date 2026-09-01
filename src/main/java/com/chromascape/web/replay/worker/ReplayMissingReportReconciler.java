package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded scheduled repair for terminal replay runs that have no {@code replay_reports} row —
 * typically runs whose recovery report write failed, or worker finalizations interrupted before
 * report persistence.
 *
 * <p>Topology contract: this orchestrator is deliberately NOT {@code @Transactional}. Candidate
 * selection is an UNLOCKED bounded query, and each candidate is repaired through one independent
 * {@code REQUIRES_NEW} writer transaction ({@link ReplayRecoveryReportTransaction}) that first
 * acquires a transaction-scoped PostgreSQL advisory lock for the run, then locks and rechecks the
 * run row. Holding no outer transaction means a REQUIRES_NEW writer can never self-deadlock against
 * us, and two server instances contend on the advisory lock instead of row locks.
 */
@Component
@ConditionalOnProperty(prefix = "replay", name = "enabled", havingValue = "true")
public class ReplayMissingReportReconciler {

  private static final Logger log = LoggerFactory.getLogger(ReplayMissingReportReconciler.class);

  private final ReplayRunRepository runRepository;
  private final ReplayRecoveryReportWriter reportWriter;
  private final ReplayProperties properties;

  public ReplayMissingReportReconciler(
      ReplayRunRepository runRepository,
      ReplayRecoveryReportWriter reportWriter,
      ReplayProperties properties) {
    this.runRepository = runRepository;
    this.reportWriter = reportWriter;
    this.properties = properties;
  }

  /**
   * Fully classified per-pass counters.
   *
   * @param attempted candidates selected for repair this pass
   * @param repaired reports inserted by THIS instance plus verified concurrent winners accepted
   * @param alreadyPresent runs whose report already existed once the writer held the locks
   * @param skipped advisory-lock contention: another instance owns the run right now — a skip,
   *     never a failure, because a later pass retries
   * @param ineligible candidates that were missing or no longer terminal when examined
   * @param failed actual exceptions thrown while repairing
   */
  public record RepairStats(
      int attempted, int repaired, int alreadyPresent, int skipped, int ineligible, int failed) {}

  @Scheduled(
      fixedDelayString =
          "#{T(java.time.Duration).ofSeconds("
              + "${replay.worker.missing-report-repair-interval-seconds:30}"
              + ").toMillis()}")
  public void reconcile() {
    if (!properties.isEnabled() || !properties.getWorker().isMissingReportRepairEnabled()) {
      return;
    }
    try {
      repairBatch();
    } catch (Exception e) {
      log.warn("Missing-report reconciliation pass failed: {}", e.getMessage());
    }
  }

  /**
   * One bounded reconciliation pass. No surrounding transaction: every candidate runs through the
   * per-run advisory-locked writer independently and failures never stop the batch.
   */
  public RepairStats repairBatch() {
    int limit = Math.max(1, properties.getWorker().getMissingReportRepairBatchSize());
    List<UUID> candidates = runRepository.findTerminalRunIdsMissingReports(limit);
    int repaired = 0;
    int alreadyPresent = 0;
    int skipped = 0;
    int ineligible = 0;
    int failed = 0;
    for (UUID runId : candidates) {
      try {
        switch (reportWriter.repair(runId)) {
          case INSERTED -> repaired++;
          case CONCURRENT_WINNER_ACCEPTED -> repaired++;
          case ALREADY_PRESENT -> alreadyPresent++;
          case LOCK_CONTENDED -> skipped++;
          case INELIGIBLE -> ineligible++;
        }
      } catch (Exception e) {
        failed++;
        log.warn("Reconciliation could not repair run {}: {}", runId, e.getMessage());
      }
    }
    if (!candidates.isEmpty()) {
      log.info(
          "Missing-report reconciliation: scanned={} repaired={} alreadyPresent={}"
              + " skipped={} ineligible={} failed={}",
          candidates.size(),
          repaired,
          alreadyPresent,
          skipped,
          ineligible,
          failed);
    }
    return new RepairStats(
        candidates.size(), repaired, alreadyPresent, skipped, ineligible, failed);
  }
}

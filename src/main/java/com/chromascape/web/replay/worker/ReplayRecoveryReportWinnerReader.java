package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fresh-transaction winner reader for recovery-report unique races. After a losing insert
 * transaction has rolled back, the orchestrator MUST read the winning row in a NEW read-only
 * transaction — never inside the poisoned one and never through a stale persistence context.
 *
 * <p>Must be invoked through the Spring proxy (injected bean), never through {@code this}.
 */
@Service
public class ReplayRecoveryReportWinnerReader {

  private final ReplayReportRepository reportRepository;

  public ReplayRecoveryReportWinnerReader(ReplayReportRepository reportRepository) {
    this.reportRepository = reportRepository;
  }

  /**
   * Reads the committed report row for the run in a fresh {@code REQUIRES_NEW} read-only
   * transaction.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<ReplayReport> findCommittedReport(UUID runId) {
    return reportRepository.findByRunId(runId);
  }
}

package com.chromascape.web.replay.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The per-run recovery-report write transaction: advisory-lock losers skip, absence is rechecked
 * under the run lock, and constraint exceptions ESCAPE the transaction untouched.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplayRecoveryReportTransactionTest {

  @Mock ReplayRunRepository runRepository;
  @Mock ReplayAttemptRepository attemptRepository;
  @Mock StateHashSampleRepository hashSampleRepository;
  @Mock ReplayCheckpointRepository checkpointRepository;
  @Mock ArtifactRefRepository artifactRefRepository;
  @Mock ReplayReportRepository reportRepository;
  @Mock ReplayMismatchRepository mismatchRepository;
  @Mock ReplayReportService reportService;

  private ReplayRecoveryReportTransaction transaction;

  private final UUID runId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    transaction =
        new ReplayRecoveryReportTransaction(
            runRepository,
            attemptRepository,
            hashSampleRepository,
            checkpointRepository,
            artifactRefRepository,
            reportRepository,
            mismatchRepository,
            reportService);
    lenient().when(hashSampleRepository.countByIdRunId(runId)).thenReturn(0L);
    lenient().when(checkpointRepository.countByRunId(runId)).thenReturn(0L);
    lenient()
        .when(artifactRefRepository.findByRunIdAndState(any(), any()))
        .thenReturn(java.util.List.of());
  }

  private ReplayRun terminalRun() {
    ReplayRun run = new ReplayRun();
    run.setId(runId);
    run.setState(ReplayRunState.FAILED);
    run.setOutcome(ReplayOutcome.INFRASTRUCTURE_FAILURE);
    run.setTerminalReason("LEASE_EXPIRED");
    run.setTerminalAt(LocalDateTime.of(2026, 8, 25, 12, 0));
    run.setManifestChecksum("sha256:" + "0".repeat(64));
    return run;
  }

  @Test
  void advisoryLockLoserSkipsWithoutTouchingAnything() {
    when(runRepository.tryAdvisoryLockForRun(runId)).thenReturn(false);

    var result = transaction.writeIfAbsent(runId);

    assertEquals(
        ReplayRecoveryReportTransaction.WriteResult.LOCK_CONTENDED,
        result,
        "advisory-lock contention must be its own classified outcome");
    verify(runRepository, never()).findByIdForUpdate(any());
    verify(reportRepository, never()).saveAndFlush(any());
  }

  @Test
  void nonTerminalRunsAreSkippedAfterLocking() {
    when(runRepository.tryAdvisoryLockForRun(runId)).thenReturn(true);
    ReplayRun running = terminalRun();
    running.setState(ReplayRunState.RUNNING);
    when(runRepository.findByIdForUpdate(runId)).thenReturn(Optional.of(running));

    assertEquals(
        ReplayRecoveryReportTransaction.WriteResult.INELIGIBLE,
        transaction.writeIfAbsent(runId),
        "a non-terminal run is ineligible, not merely skipped");
    verify(reportRepository, never()).saveAndFlush(any());
  }

  @Test
  void existingReportsShortCircuitAsAlreadyPresent() {
    when(runRepository.tryAdvisoryLockForRun(runId)).thenReturn(true);
    when(runRepository.findByIdForUpdate(runId)).thenReturn(Optional.of(terminalRun()));
    when(reportRepository.findByRunId(runId)).thenReturn(Optional.of(new ReplayReport()));

    assertEquals(
        ReplayRecoveryReportTransaction.WriteResult.ALREADY_PRESENT,
        transaction.writeIfAbsent(runId));
    verify(reportRepository, never()).saveAndFlush(any());
  }

  @Test
  void insertsThePreparedReportWhenEverythingIsEligible() {
    when(runRepository.tryAdvisoryLockForRun(runId)).thenReturn(true);
    ReplayRun run = terminalRun();
    when(runRepository.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
    when(reportRepository.findByRunId(runId)).thenReturn(Optional.empty());
    ReplayAttempt attempt = new ReplayAttempt();
    attempt.setId(UUID.randomUUID());
    when(attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId))
        .thenReturn(Optional.of(attempt));

    byte[] canonical = "{\"reportVersion\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    ReplayReport entity = new ReplayReport();
    when(reportService.prepareReport(
            eq(run),
            eq(attempt),
            eq(ReplayOutcome.INFRASTRUCTURE_FAILURE),
            eq("LEASE_EXPIRED"),
            eq(0),
            eq(0),
            any(),
            any(),
            any(),
            eq(run.getTerminalAt()),
            any()))
        .thenReturn(prepared(canonical, false));
    when(reportService.materialize(any(), eq(run), eq(attempt))).thenReturn(entity);

    var result = transaction.writeIfAbsent(runId);

    assertEquals(ReplayRecoveryReportTransaction.WriteResult.INSERTED, result);
    ArgumentCaptor<ReplayReport> saved = ArgumentCaptor.forClass(ReplayReport.class);
    verify(reportRepository).saveAndFlush(saved.capture());
    assertEquals(entity, saved.getValue());
  }

  @Test
  void constraintViolationsEscapeWithoutBeingCaughtInsideTheTransaction() {
    when(runRepository.tryAdvisoryLockForRun(runId)).thenReturn(true);
    ReplayRun run = terminalRun();
    when(runRepository.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
    when(reportRepository.findByRunId(runId)).thenReturn(Optional.empty());
    when(attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId))
        .thenReturn(Optional.empty());

    byte[] canonical = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(reportService.prepareReport(
            any(), any(), any(), any(), eq(0), eq(0), any(), any(), any(), any(), any()))
        .thenReturn(prepared(canonical, false));
    when(reportService.materialize(any(), eq(run), eq(null)))
        .thenThrow(new DataIntegrityViolationException("unique race"));

    // The exception MUST propagate: catching it here would poison the transaction.
    assertThrows(DataIntegrityViolationException.class, () -> transaction.writeIfAbsent(runId));
  }

  /** Builds real immutable preparations without re-implementing schema validation. */
  private static ReplayReportService.PreparedReplayReport prepared(byte[] bytes, boolean preExec) {
    LocalDateTime dbNow = LocalDateTime.of(2026, 8, 25, 12, 0);
    return new ReplayReportService.PreparedReplayReport(
        bytes,
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        preExec ? ReplayOutcome.CANCELLED : ReplayOutcome.INFRASTRUCTURE_FAILURE,
        preExec ? "CANCEL_REQUESTED" : "LEASE_EXPIRED",
        0,
        0,
        0,
        0,
        0,
        null,
        null,
        null,
        dbNow.toInstant(java.time.ZoneOffset.UTC),
        preExec);
  }
}

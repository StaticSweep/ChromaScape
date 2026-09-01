package com.chromascape.web.replay.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.artifact.ArtifactPublicationReceipt;
import com.chromascape.web.replay.artifact.ArtifactRegistration;
import com.chromascape.web.replay.artifact.PreparedArtifact;
import com.chromascape.web.replay.artifact.PublishedArtifact;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.contract.RuntimeIdentity;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.ReplayFence;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import com.chromascape.web.replay.run.entity.ReplayEvent;
import com.chromascape.web.replay.run.entity.ReplayMismatch;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.entity.StateHashSample;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayEventRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The transactional fenced persistence boundary: every worker-owned write must lock the attempt
 * fence and then the run row in the same unit of work, execution writes must reject CANCELLING, and
 * a stale or expired attempt must be able to persist nothing at all.
 */
@ExtendWith(MockitoExtension.class)
class ReplayWorkerPersistenceServiceTest {

  @Mock private ReplayRunService runService;
  @Mock private ReplayRunRepository runRepository;
  @Mock private ReplayAttemptRepository attemptRepository;
  @Mock private ReplayEventRepository eventRepository;
  @Mock private StateHashSampleRepository hashSampleRepository;
  @Mock private ReplayCheckpointRepository checkpointRepository;
  @Mock private ReplayMismatchRepository mismatchRepository;
  @Mock private ArtifactRefRepository artifactRefRepository;
  @Mock private ReplayReportRepository reportRepository;
  @Mock private jakarta.persistence.EntityManager entityManager;

  private ReplayProperties properties;
  private ReplayWorkerPersistenceService service;

  private UUID runId;
  private UUID attemptId;
  private ReplayFence fence;
  private ReplayAttempt lockedAttempt;
  private ReplayRun runningRun;

  @BeforeEach
  void setUp() {
    properties = new ReplayProperties();
    service =
        new ReplayWorkerPersistenceService(
            runService,
            runRepository,
            attemptRepository,
            eventRepository,
            hashSampleRepository,
            checkpointRepository,
            mismatchRepository,
            artifactRefRepository,
            reportRepository,
            properties,
            entityManager);
    runId = UUID.randomUUID();
    attemptId = UUID.randomUUID();
    lockedAttempt = new ReplayAttempt();
    lockedAttempt.setId(attemptId);
    lockedAttempt.setRunId(runId);
    lockedAttempt.setAttemptNo(2);
    lockedAttempt.setFencingGeneration(3);
    lockedAttempt.setLeaseOwner("worker-a");
    lockedAttempt.setState("RUNNING");
    fence = new ReplayFence(runId, attemptId, 2, 3, "worker-a");
    runningRun = new ReplayRun();
    runningRun.setId(runId);
    runningRun.setState(ReplayRunState.RUNNING);
    runningRun.setPhase(ReplayRunPhase.EXECUTE);
    runningRun.setManifestChecksum("sha256:" + "0".repeat(64));

    lenient()
        .when(runService.requireValidFence(runId, attemptId, 3, "worker-a"))
        .thenReturn(lockedAttempt);
    lenient()
        .doAnswer(
            inv -> {
              ReplayRun run = inv.getArgument(0);
              ReplayRunState target = inv.getArgument(1);
              ReplayRunPhase phase = inv.getArgument(2);
              if (run.isTerminal()) {
                throw ReplayException.conflict(
                    ReplayErrorCode.RUN_STATE_INVALID, "terminal runs are immutable");
              }
              if (target == ReplayRunState.RUNNING && run.getState() == ReplayRunState.RUNNING) {
                throw ReplayException.conflict(
                    ReplayErrorCode.RUN_STATE_INVALID, "RUNNING -> RUNNING is illegal");
              }
              run.setState(target);
              if (phase != null) {
                run.setPhase(phase);
              }
              if (target == ReplayRunState.STARTING && run.getStartedAt() == null) {
                run.setStartedAt(LocalDateTime.now());
              }
              java.util.EnumSet<ReplayRunState> terminal =
                  java.util.EnumSet.of(
                      ReplayRunState.SUCCEEDED, ReplayRunState.FAILED,
                      ReplayRunState.CANCELLED, ReplayRunState.TIMED_OUT);
              if (terminal.contains(target)) {
                run.setTerminalAt(LocalDateTime.now());
              }
              return null;
            })
        .when(runService)
        .applyTransition(any(), any(), any());
    lenient()
        .doAnswer(
            inv -> {
              ReplayRun run = inv.getArgument(0);
              ReplayRunState target = inv.getArgument(1);
              ReplayRunPhase phase = inv.getArgument(2);
              LocalDateTime dbNow = inv.getArgument(3);
              if (run.isTerminal()) {
                throw ReplayException.conflict(
                    ReplayErrorCode.RUN_STATE_INVALID, "terminal runs are immutable");
              }
              run.setState(target);
              if (phase != null) {
                run.setPhase(phase);
              }
              if (target == ReplayRunState.STARTING && run.getStartedAt() == null) {
                run.setStartedAt(dbNow);
              }
              java.util.EnumSet<ReplayRunState> terminal =
                  java.util.EnumSet.of(
                      ReplayRunState.SUCCEEDED, ReplayRunState.FAILED,
                      ReplayRunState.CANCELLED, ReplayRunState.TIMED_OUT);
              if (terminal.contains(target)) {
                run.setTerminalAt(dbNow);
              }
              return null;
            })
        .when(runService)
        .applyTransition(any(), any(), any(), any());
    lenient().when(runRepository.findByIdForUpdate(runId)).thenReturn(Optional.of(runningRun));
  }

  // ── valid fence writes ──

  @Test
  void appendEventsWritesUnderValidFence() {
    List<ReplayEvent> events = List.of(event(1), event(2));
    service.appendEvents(fence, events);
    verify(eventRepository).saveAll(events);
  }

  @Test
  void appendActionResultWritesSampleOnlyAfterEveryEventPartition() {
    properties.getWorker().setMaxEventBatch(2);
    List<ReplayEvent> events = List.of(event(1), event(2), event(3));
    StateHashSample sample = new StateHashSample(runId, 42L, 0, 1, "state", "chain");

    service.appendActionResult(fence, events, sample);

    ArgumentCaptor<List<ReplayEvent>> partitions = partitionCaptor();
    verify(eventRepository, org.mockito.Mockito.times(2)).saveAll(partitions.capture());
    assertEquals(2, partitions.getAllValues().get(0).size());
    assertEquals(1, partitions.getAllValues().get(1).size());
    InOrder order = inOrder(eventRepository, hashSampleRepository);
    order.verify(eventRepository).saveAll(partitions.getAllValues().get(0));
    order.verify(eventRepository).saveAll(partitions.getAllValues().get(1));
    order.verify(hashSampleRepository).save(sample);
  }

  @Test
  void appendEventsRejectsForeignAttemptAndNegativeOrdinals() {
    assertStale(() -> service.appendEvents(fence, List.of(foreignEvent())));
    assertThrows(ReplayException.class, () -> service.appendEvents(fence, List.of(event(-1))));
    verify(eventRepository, never()).saveAll(anyList());
  }

  @Test
  void storeRuntimeIdentitySetsAllFourFields() {
    RuntimeIdentity identity =
        new RuntimeIdentity("adapter-9", "commit-abc", "cache-1", "content-7");

    service.storeRuntimeIdentity(fence, identity);

    ArgumentCaptor<ReplayRun> captor = ArgumentCaptor.forClass(ReplayRun.class);
    verify(runRepository).save(captor.capture());
    assertEquals("adapter-9", captor.getValue().getXrspsAdapterVersion());
    assertEquals("commit-abc", captor.getValue().getXrspsBuildCommit());
    assertEquals("cache-1", captor.getValue().getCacheRevision());
    assertEquals("content-7", captor.getValue().getContentRevision());
  }

  @Test
  void registerCheckpointPersistsReferenceAndRowTogether() {
    PreparedArtifact prepared = snapshotPrepared();
    when(artifactRefRepository.findByRunIdAndKindAndChecksum(
            runId, ArtifactKind.SNAPSHOT, prepared.checksum()))
        .thenReturn(Optional.empty());
    when(artifactRefRepository.save(any(ArtifactRef.class))).thenAnswer(inv -> inv.getArgument(0));
    ReplayCheckpoint checkpoint = checkpoint();

    ArtifactRef ref = service.registerCheckpoint(fence, prepared, checkpoint).reference();

    assertEquals(prepared.checksum(), ref.getChecksum());
    assertEquals(prepared.checksum(), checkpoint.getSnapshotArtifactChecksum());
    verify(checkpointRepository).save(checkpoint);
  }

  @Test
  void registerCheckpointRejectsMissingInternalSnapshotChecksum() {
    PreparedArtifact prepared = snapshotPrepared();
    ReplayCheckpoint checkpoint = checkpoint();
    checkpoint.setSnapshotChecksum(null);

    assertThrows(
        ReplayException.class, () -> service.registerCheckpoint(fence, prepared, checkpoint));
    verify(checkpointRepository, never()).save(any());
  }

  @Test
  void registerArtifactInsertsStagingReferenceAndCopiesMediaType() {
    PreparedArtifact prepared = prepared();
    when(artifactRefRepository.findByRunIdAndKindAndChecksum(
            runId, ArtifactKind.TRACE, prepared.checksum()))
        .thenReturn(Optional.empty());
    when(artifactRefRepository.save(any(ArtifactRef.class))).thenAnswer(inv -> inv.getArgument(0));

    ArtifactRegistration registration = service.registerArtifact(fence, prepared);
    ArtifactRef ref = registration.reference();

    assertEquals(prepared.checksum(), ref.getChecksum());
    assertEquals(ArtifactRef.STATE_STAGING, ref.getState());
    assertEquals("application/json", ref.getMediaType());
    verify(artifactRefRepository).save(ref);
  }

  @Test
  void registerArtifactReusesExistingReferenceAndVerifiesSize() {
    PreparedArtifact prepared = prepared();
    ArtifactRef existing =
        new ArtifactRef(
            UUID.randomUUID(),
            runId,
            ArtifactKind.TRACE,
            prepared.checksum(),
            prepared.sizeBytes());
    existing.setMediaType("application/json");
    when(artifactRefRepository.findByRunIdAndKindAndChecksum(
            runId, ArtifactKind.TRACE, prepared.checksum()))
        .thenReturn(Optional.of(existing));

    ArtifactRegistration registration = service.registerArtifact(fence, prepared);

    assertEquals(existing.getId(), registration.reference().getId());
    org.junit.jupiter.api.Assertions.assertTrue(registration.publicationRequired());
    verify(artifactRefRepository, never()).save(any());
  }

  @Test
  void registerArtifactReuseOfFinalizedReferenceNeverMutatesAndSkipsRepublish() {
    PreparedArtifact prepared = prepared();
    ArtifactRef finalized =
        new ArtifactRef(
            UUID.randomUUID(),
            runId,
            ArtifactKind.TRACE,
            prepared.checksum(),
            prepared.sizeBytes());
    finalized.setMediaType("application/json");
    finalized.setState(ArtifactRef.STATE_FINALIZED);
    when(artifactRefRepository.findByRunIdAndKindAndChecksum(
            runId, ArtifactKind.TRACE, prepared.checksum()))
        .thenReturn(Optional.of(finalized));

    ArtifactRegistration registration = service.registerArtifact(fence, prepared);

    org.junit.jupiter.api.Assertions.assertFalse(registration.publicationRequired());
    assertEquals(ArtifactRef.STATE_FINALIZED, registration.reference().getState());
    verify(artifactRefRepository, never()).save(any());
  }

  @Test
  void registerArtifactRejectsCorruptAndTombstonedReferences() {
    for (String deadState :
        new String[] {ArtifactRef.STATE_CORRUPT, ArtifactRef.STATE_TOMBSTONED}) {
      PreparedArtifact prepared = prepared();
      ArtifactRef dead =
          new ArtifactRef(
              UUID.randomUUID(),
              runId,
              ArtifactKind.TRACE,
              prepared.checksum(),
              prepared.sizeBytes());
      dead.setMediaType("application/json");
      dead.setState(deadState);
      when(artifactRefRepository.findByRunIdAndKindAndChecksum(
              runId, ArtifactKind.TRACE, prepared.checksum()))
          .thenReturn(Optional.of(dead));

      ReplayException e =
          assertThrows(ReplayException.class, () -> service.registerArtifact(fence, prepared));
      assertEquals(ReplayErrorCode.CHECKSUM_MISMATCH, e.getErrorCode());
    }
  }

  @Test
  void saveMismatchesStampsEveryRowWithExactlyOneDatabaseTimestamp() {
    LocalDateTime firstRead = LocalDateTime.of(2026, 8, 26, 9, 0, 0);
    // Successive reads would return DIFFERENT timestamps; the batch must capture only the first.
    when(runRepository.currentTimestamp())
        .thenReturn(firstRead, firstRead.plusSeconds(1), firstRead.plusSeconds(2));
    List<ReplayMismatch> mismatches = List.of(mismatch(0), mismatch(1), mismatch(2), mismatch(3));

    service.saveMismatches(fence, mismatches);

    org.mockito.Mockito.verify(runRepository, org.mockito.Mockito.times(1)).currentTimestamp();
    java.time.Instant expected = dbUtcInstant(firstRead);
    for (ReplayMismatch mismatch : mismatches) {
      assertEquals(
          expected,
          mismatch.getCreatedAt(),
          "every row of one saveMismatches action shares ONE database timestamp");
    }
    verify(mismatchRepository).saveAll(mismatches);
  }

  private ReplayMismatch mismatch(int seq) {
    return new ReplayMismatch.Builder(
            runId, attemptId, com.chromascape.web.replay.api.dto.MismatchLevel.HARD, "rule-" + seq)
        .firstTick(seq * 10L)
        .tickSequence(seq)
        .domain("position")
        .build();
  }

  @Test
  void appendEventsFlushesAndClearsBetweenPartitions() {
    properties.getWorker().setMaxEventBatch(1);
    List<ReplayEvent> events = List.of(event(1), event(2));

    service.appendEvents(fence, events);

    InOrder order = inOrder(eventRepository, entityManager);
    ArgumentCaptor<List<ReplayEvent>> partitions = partitionCaptor();
    order.verify(eventRepository).saveAll(partitions.capture());
    order.verify(eventRepository).flush();
    order.verify(entityManager).clear();
    order.verify(eventRepository).saveAll(partitions.capture());
    order.verify(eventRepository).flush();
    order.verify(entityManager).clear();
    assertEquals(1, partitions.getAllValues().get(0).size());
    assertEquals(1, partitions.getAllValues().get(1).size());
  }

  @Test
  void updatePhaseAppliesPhaseOnlyChange() {
    service.updatePhase(fence, ReplayRunPhase.EXECUTE, ReplayRunPhase.COMPARE);

    ArgumentCaptor<ReplayRun> captor = ArgumentCaptor.forClass(ReplayRun.class);
    verify(runRepository).save(captor.capture());
    assertEquals(ReplayRunState.RUNNING, captor.getValue().getState());
    assertEquals(ReplayRunPhase.COMPARE, captor.getValue().getPhase());
  }

  // ── cancellation: execution writes are rejected ──

  @Test
  void executionWritesRejectCancellingRun() {
    runningRun.setState(ReplayRunState.CANCELLING);

    assertThrows(
        ReplayCancellationRequested.class,
        () -> service.updatePhase(fence, null, ReplayRunPhase.COMPARE));
    assertThrows(
        ReplayCancellationRequested.class,
        () -> service.storeRuntimeIdentity(fence, new RuntimeIdentity("a", "b", "c", "d")));
    assertThrows(
        ReplayCancellationRequested.class, () -> service.appendEvents(fence, List.of(event(1))));
    assertThrows(
        ReplayCancellationRequested.class,
        () ->
            service.appendActionResult(
                fence, List.of(), new StateHashSample(runId, 1, 0, 1, "s", "c")));
    assertThrows(
        ReplayCancellationRequested.class,
        () -> service.registerCheckpoint(fence, snapshotPrepared(), checkpoint()));
    assertThrows(ReplayCancellationRequested.class, () -> service.saveMismatches(fence, List.of()));
    assertThrows(
        ReplayCancellationRequested.class, () -> service.registerArtifact(fence, prepared()));

    verifyNoWrites();
  }

  // ── stale fence: zero writes ──

  @Test
  void staleFenceBlocksEveryWriteType() {
    when(runService.requireValidFence(runId, attemptId, 3, "worker-a")).thenThrow(staleException());

    assertStale(() -> service.transitionStarting(fence));
    assertStale(() -> service.transitionRunning(fence, ReplayRunPhase.RESET));
    assertStale(() -> service.updatePhase(fence, null, ReplayRunPhase.EXECUTE));
    assertStale(() -> service.storeRuntimeIdentity(fence, new RuntimeIdentity("a", "b", "c", "d")));
    assertStale(() -> service.appendEvents(fence, List.of(event(1))));
    assertStale(
        () ->
            service.appendActionResult(
                fence, List.of(), new StateHashSample(runId, 1, 0, 1, "s", "c")));
    assertStale(() -> service.registerCheckpoint(fence, snapshotPrepared(), checkpoint()));
    assertStale(() -> service.saveMismatches(fence, List.of()));
    assertStale(() -> service.registerArtifact(fence, prepared()));

    verifyNoWrites();
  }

  @Test
  void staleFenceCannotReserveOrFinalizeFailureOrFinalizeSuccess() {
    when(runService.requireValidFence(runId, attemptId, 3, "worker-a")).thenThrow(staleException());

    assertStale(
        () ->
            service.reserveFailureFinalization(
                fence, ReplayRunState.FAILED, ReplayOutcome.INFRASTRUCTURE_FAILURE, "X"));
    var staleDecision =
        new ReplayWorkerPersistenceService.TerminalDecision(
            ReplayRunState.FAILED, ReplayOutcome.INFRASTRUCTURE_FAILURE, "X", LocalDateTime.now());
    assertStale(
        () -> service.finalizeFailure(fence, staleDecision, alignedReport(staleDecision), null));
    assertStale(
        () ->
            service.finalizeSuccessfulRun(
                fence,
                reservation(ReplayOutcome.PASSED),
                new ArtifactPublicationReceipt(runId, List.of()),
                new ReplayReport(),
                ReplayOutcome.PASSED));

    verifyNoWrites();
  }

  // ── failure reservation protocol ──

  @Test
  void reservationOnCancellingRunForcesCancelledDecisionAndSetsCutoff() {
    runningRun.setState(ReplayRunState.CANCELLING);
    LocalDateTime dbNow = LocalDateTime.now().plusSeconds(5);
    when(runRepository.currentTimestamp()).thenReturn(dbNow);

    var decision =
        service.reserveFailureFinalization(
            fence,
            ReplayRunState.FAILED,
            ReplayOutcome.INFRASTRUCTURE_FAILURE,
            "INFRASTRUCTURE_FAILURE");

    assertEquals(ReplayRunState.CANCELLED, decision.state());
    assertEquals(ReplayOutcome.CANCELLED, decision.outcome());
    assertEquals("CANCEL_REQUESTED", decision.reason());
    assertEquals(dbNow, decision.terminalAt());
    // Run is NOT terminal yet — only the cutoff phase is committed.
    assertEquals(ReplayRunState.CANCELLING, runningRun.getState());
    assertEquals(ReplayRunPhase.FINALIZE_REPORT, runningRun.getPhase());

    ArgumentCaptor<ReplayRun> captor = ArgumentCaptor.forClass(ReplayRun.class);
    verify(runRepository).save(captor.capture());
    assertEquals(ReplayRunPhase.FINALIZE_REPORT, captor.getValue().getPhase());
    verify(attemptRepository, never()).save(any());
    verify(reportRepository, never()).save(any());
  }

  @Test
  void reservationPreservesRequestedOutcomeWhenNotCancelling() {
    LocalDateTime dbNow = LocalDateTime.now();
    when(runRepository.currentTimestamp()).thenReturn(dbNow);

    var decision =
        service.reserveFailureFinalization(
            fence, ReplayRunState.TIMED_OUT, ReplayOutcome.TIMED_OUT, "RUN_DEADLINE_EXCEEDED");

    assertEquals(ReplayRunState.TIMED_OUT, decision.state());
    assertEquals(ReplayOutcome.TIMED_OUT, decision.outcome());
    assertEquals("RUN_DEADLINE_EXCEEDED", decision.reason());
    assertEquals(ReplayRunState.RUNNING, runningRun.getState());
  }

  @Test
  void finalizeFailureTerminalizesWithExactlyTheReservedDecision() {
    LocalDateTime dbNow = LocalDateTime.now();
    var decision =
        new ReplayWorkerPersistenceService.TerminalDecision(
            ReplayRunState.TIMED_OUT, ReplayOutcome.TIMED_OUT, "RUN_DEADLINE_EXCEEDED", dbNow);
    ReplayReport report = alignedReport(decision);

    service.finalizeFailure(fence, decision, report, null);

    assertEquals(ReplayRunState.TIMED_OUT, runningRun.getState());
    assertEquals(ReplayOutcome.TIMED_OUT, runningRun.getOutcome());
    assertEquals("RUN_DEADLINE_EXCEEDED", runningRun.getTerminalReason());
    assertEquals(dbNow, runningRun.getTerminalAt());
    assertEquals("TIMED_OUT", lockedAttempt.getState());
    assertNull(lockedAttempt.getLeaseExpiresAt());
    assertEquals(dbNow.atZone(java.time.ZoneOffset.UTC).toInstant(), report.getTerminalAt());
    verify(reportRepository).save(report);
    verify(artifactRefRepository, org.mockito.Mockito.atLeastOnce())
        .findByRunIdAndState(runId, ArtifactRef.STATE_STAGING);
  }

  @Test
  void finalizeFailureSkipsAlreadyTerminalRuns() {
    runningRun.setState(ReplayRunState.FAILED);
    runningRun.setPhase(ReplayRunPhase.FINALIZE_REPORT);
    var decision =
        new ReplayWorkerPersistenceService.TerminalDecision(
            ReplayRunState.FAILED, ReplayOutcome.INFRASTRUCTURE_FAILURE, "X", LocalDateTime.now());

    service.finalizeFailure(fence, decision, alignedReport(decision), null);

    verify(runRepository, never()).save(any());
    verify(attemptRepository, never()).save(any());
    verify(reportRepository, never()).save(any());
  }

  @Test
  void finalizeFailureRejectsNullReportWithoutAnyWrites() {
    var decision =
        new ReplayWorkerPersistenceService.TerminalDecision(
            ReplayRunState.TIMED_OUT,
            ReplayOutcome.TIMED_OUT,
            "RUN_DEADLINE_EXCEEDED",
            LocalDateTime.now());

    ReplayException e =
        assertThrows(
            ReplayException.class, () -> service.finalizeFailure(fence, decision, null, null));
    assertEquals(ReplayErrorCode.REPORT_PERSISTENCE_FAILED, e.getErrorCode());
    verifyNoWrites();
  }

  @Test
  void finalizeFailureWithoutReceiptTombstonesUnresolvedStagingReferences() {
    var decision =
        new ReplayWorkerPersistenceService.TerminalDecision(
            ReplayRunState.FAILED,
            ReplayOutcome.INFRASTRUCTURE_FAILURE,
            "LEASE_EXPIRED",
            LocalDateTime.now());
    ArtifactRef unresolvedTrace =
        stagedRef(UUID.randomUUID(), ArtifactKind.TRACE, "sha256:" + "e".repeat(64));
    when(artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING))
        .thenReturn(List.of(unresolvedTrace));

    service.finalizeFailure(fence, decision, alignedReport(decision), null);

    assertEquals(ArtifactRef.STATE_TOMBSTONED, unresolvedTrace.getState());
    assertEquals(ReplayRunState.FAILED, runningRun.getState());
    assertEquals(dbUtcInstant(decision.terminalAt()), reportSavedOnce().getFinalizedAt());
  }

  @Test
  void finalizeFailureRejectsReportMismatchedWithDecision() {
    var decision =
        new ReplayWorkerPersistenceService.TerminalDecision(
            ReplayRunState.TIMED_OUT,
            ReplayOutcome.TIMED_OUT,
            "RUN_DEADLINE_EXCEEDED",
            LocalDateTime.now());
    ReplayReport wrongOutcome = alignedReport(decision);
    wrongOutcome.setOutcome(ReplayOutcome.CANCELLED);

    assertThrows(
        ReplayException.class, () -> service.finalizeFailure(fence, decision, wrongOutcome, null));
    verify(reportRepository, never()).save(any());
  }

  // ── success finalization with receipt verification ──

  @Test
  void finalizeSuccessfulRunMarksArtifactsInsertsReportAndTerminals() {
    runningRun.setPhase(ReplayRunPhase.FINALIZE_ARTIFACTS);
    ReplayWorkerPersistenceService.FinalizationReservation reservation =
        reservation(ReplayOutcome.PASSED);
    ReplayReport report = reportWithJson(ReplayOutcome.PASSED);
    ArtifactRef reportRef = stagedReportRef(report);

    when(artifactRefRepository.findById(reportRef.getId())).thenReturn(Optional.of(reportRef));
    when(artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING))
        .thenReturn(List.of(reportRef));
    when(reportRepository.findByRunId(runId)).thenReturn(Optional.empty());

    ArtifactPublicationReceipt receipt =
        new ArtifactPublicationReceipt(
            runId,
            List.of(
                new PublishedArtifact(
                    reportRef.getId(),
                    runId,
                    ArtifactKind.REPORT,
                    reportRef.getChecksum(),
                    reportRef.getSizeBytes(),
                    reportRef.getMediaType())));

    service.finalizeSuccessfulRun(fence, reservation, receipt, report, ReplayOutcome.PASSED);

    verify(reportRepository).save(report);
    assertEquals(ArtifactRef.STATE_FINALIZED, reportRef.getState());
    assertNotNull(reportRef.getFinalizedAt());

    ArgumentCaptor<ReplayRun> runCaptor = ArgumentCaptor.forClass(ReplayRun.class);
    verify(runRepository).save(runCaptor.capture());
    assertEquals(ReplayRunState.SUCCEEDED, runCaptor.getValue().getState());
    assertEquals(ReplayOutcome.PASSED, runCaptor.getValue().getOutcome());
    assertEquals(ReplayRunPhase.FINALIZE_REPORT, runCaptor.getValue().getPhase());
    assertNotNull(runCaptor.getValue().getTerminalAt());

    assertEquals("SUCCEEDED", lockedAttempt.getState());
    assertNotNull(lockedAttempt.getTerminalAt());
    assertNull(lockedAttempt.getLeaseExpiresAt());
  }

  @Test
  void hardMismatchStillSucceedsWithFailedOutcome() {
    runningRun.setPhase(ReplayRunPhase.FINALIZE_ARTIFACTS);
    ReplayWorkerPersistenceService.FinalizationReservation failedReservation =
        reservation(ReplayOutcome.FAILED);
    ReplayReport report = reportWithJson(ReplayOutcome.FAILED);
    ArtifactRef reportRef = stagedReportRef(report);
    when(artifactRefRepository.findById(reportRef.getId())).thenReturn(Optional.of(reportRef));
    when(artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING))
        .thenReturn(List.of(reportRef));
    when(reportRepository.findByRunId(runId)).thenReturn(Optional.empty());

    service.finalizeSuccessfulRun(
        fence, failedReservation, receiptFor(reportRef), report, ReplayOutcome.FAILED);

    assertEquals(ReplayRunState.SUCCEEDED, runningRun.getState());
    assertEquals(ReplayOutcome.FAILED, runningRun.getOutcome());
  }

  @Test
  void receiptThatMissesAStagedReferenceIsRejected() {
    runningRun.setPhase(ReplayRunPhase.FINALIZE_ARTIFACTS);
    ReplayReport report = reportWithJson(ReplayOutcome.PASSED);
    ArtifactRef reportRef = stagedReportRef(report);
    ArtifactRef traceRef =
        stagedRef(UUID.randomUUID(), ArtifactKind.TRACE, "sha256:" + "b".repeat(64));

    when(artifactRefRepository.findById(reportRef.getId())).thenReturn(Optional.of(reportRef));
    when(artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING))
        .thenReturn(List.of(reportRef, traceRef));
    lenient().when(reportRepository.findByRunId(runId)).thenReturn(Optional.empty());

    assertThrows(
        ReplayException.class,
        () ->
            service.finalizeSuccessfulRun(
                fence,
                runningRun.getFinalizationReservedAt() != null
                    ? new ReplayWorkerPersistenceService.FinalizationReservation(
                        runId,
                        attemptId,
                        ReplayOutcome.PASSED,
                        runningRun.getFinalizationReservedAt())
                    : reservation(ReplayOutcome.PASSED),
                receiptFor(reportRef),
                report,
                ReplayOutcome.PASSED));
    assertEquals(ArtifactRef.STATE_STAGING, reportRef.getState()); // nothing marked finalized
    verify(reportRepository, never()).save(any());
  }

  @Test
  void forgedWrongRunReceiptIsRejected() {
    runningRun.setPhase(ReplayRunPhase.FINALIZE_ARTIFACTS);
    ReplayReport report = reportWithJson(ReplayOutcome.PASSED);
    // Constructed directly against another run: the receipt lies about ownership.
    ArtifactRef foreignRef =
        new ArtifactRef(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ArtifactKind.REPORT,
            report.getReportChecksum(),
            10);
    lenient()
        .when(artifactRefRepository.findById(foreignRef.getId()))
        .thenReturn(Optional.of(foreignRef));
    lenient()
        .when(artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING))
        .thenReturn(List.of());

    assertThrows(
        ReplayException.class,
        () ->
            service.finalizeSuccessfulRun(
                fence,
                reservation(ReplayOutcome.PASSED),
                receiptFor(foreignRef),
                report,
                ReplayOutcome.PASSED));
    verify(reportRepository, never()).save(any());
  }

  @Test
  void reportArtifactChecksumMismatchIsRejected() {
    runningRun.setPhase(ReplayRunPhase.FINALIZE_ARTIFACTS);
    ReplayReport report = reportWithJson(ReplayOutcome.PASSED);
    String wrongChecksum = "sha256:" + "f".repeat(64);
    int jsonLength = report.getReportJson().getBytes(StandardCharsets.UTF_8).length;
    ArtifactRef reportRef =
        new ArtifactRef(UUID.randomUUID(), runId, ArtifactKind.REPORT, wrongChecksum, jsonLength);
    reportRef.setMediaType("application/json");
    when(artifactRefRepository.findById(reportRef.getId())).thenReturn(Optional.of(reportRef));
    when(artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING))
        .thenReturn(List.of(reportRef));
    lenient().when(reportRepository.findByRunId(runId)).thenReturn(Optional.empty());

    assertThrows(
        ReplayException.class,
        () ->
            service.finalizeSuccessfulRun(
                fence,
                runningRun.getFinalizationReservedAt() != null
                    ? new ReplayWorkerPersistenceService.FinalizationReservation(
                        runId,
                        attemptId,
                        ReplayOutcome.PASSED,
                        runningRun.getFinalizationReservedAt())
                    : reservation(ReplayOutcome.PASSED),
                receiptFor(reportRef),
                report,
                ReplayOutcome.PASSED));
    assertEquals(ArtifactRef.STATE_STAGING, reportRef.getState());
    verify(reportRepository, never()).save(any());
  }

  @Test
  void finalizeSuccessfulRunRequiresRunningInFinalizeArtifacts() {
    runningRun.setPhase(ReplayRunPhase.COMPARE); // cutoff not yet established
    var unreserved =
        new ReplayWorkerPersistenceService.FinalizationReservation(
            runId,
            attemptId,
            ReplayOutcome.PASSED,
            LocalDateTime.now().plusSeconds(3)); // does NOT match the unset persisted capture
    ReplayReport report = reportWithJson(ReplayOutcome.PASSED);
    ArtifactRef reportRef = stagedReportRef(report);
    lenient()
        .when(artifactRefRepository.findById(reportRef.getId()))
        .thenReturn(Optional.of(reportRef));
    lenient()
        .when(artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING))
        .thenReturn(List.of(reportRef));

    assertStale(
        () ->
            service.finalizeSuccessfulRun(
                fence, unreserved, receiptFor(reportRef), report, ReplayOutcome.PASSED));
    verify(reportRepository, never()).save(any());
  }

  @Test
  void finalizeSuccessfulRunRejectsSecondReport() {
    runningRun.setPhase(ReplayRunPhase.FINALIZE_ARTIFACTS);
    ReplayReport report = reportWithJson(ReplayOutcome.PASSED);
    ArtifactRef reportRef = stagedReportRef(report);
    when(artifactRefRepository.findById(reportRef.getId())).thenReturn(Optional.of(reportRef));
    when(artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING))
        .thenReturn(List.of(reportRef));
    when(reportRepository.findByRunId(runId)).thenReturn(Optional.of(new ReplayReport()));

    assertThrows(
        ReplayException.class,
        () ->
            service.finalizeSuccessfulRun(
                fence,
                runningRun.getFinalizationReservedAt() != null
                    ? new ReplayWorkerPersistenceService.FinalizationReservation(
                        runId,
                        attemptId,
                        ReplayOutcome.PASSED,
                        runningRun.getFinalizationReservedAt())
                    : reservation(ReplayOutcome.PASSED),
                receiptFor(reportRef),
                report,
                ReplayOutcome.PASSED));
    verify(runRepository, never()).save(any());
  }

  // ── helpers ──

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<List<ReplayEvent>> partitionCaptor() {
    return ArgumentCaptor.forClass((Class) List.class);
  }

  private ReplayException staleException() {
    return new ReplayException(
        ReplayErrorCode.ATTEMPT_STALE_FENCE,
        org.springframework.http.HttpStatus.CONFLICT,
        false,
        "stale",
        null,
        null,
        null);
  }

  private void assertStale(Runnable action) {
    ReplayException e = assertThrows(ReplayException.class, action::run);
    assertEquals(ReplayErrorCode.ATTEMPT_STALE_FENCE, e.getErrorCode());
  }

  private void verifyNoWrites() {
    verify(eventRepository, never()).saveAll(any());
    verify(hashSampleRepository, never()).save(any());
    verify(checkpointRepository, never()).save(any());
    verify(mismatchRepository, never()).saveAll(any());
    verify(artifactRefRepository, never()).save(any());
    verify(reportRepository, never()).save(any());
    verify(runRepository, never()).save(any());
    verify(attemptRepository, never()).save(any());
  }

  private ReplayEvent event(long ordinal) {
    ReplayEvent e = new ReplayEvent();
    e.setRunId(runId);
    e.setAttemptId(attemptId);
    e.setOrdinal(ordinal);
    e.setTick(ordinal * 10);
    e.setSequence(0);
    e.setEventType("TICK_ADVANCED");
    e.setCategory("SIMULATION");
    return e;
  }

  private ReplayEvent foreignEvent() {
    ReplayEvent e = event(9);
    e.setAttemptId(UUID.randomUUID());
    return e;
  }

  private ReplayCheckpoint checkpoint() {
    return new ReplayCheckpoint(
        UUID.randomUUID(), runId, attemptId, 100L, "sha256:snapinternal", "state", "chain", "main");
  }

  private PreparedArtifact prepared() {
    return new PreparedArtifact(
        UUID.randomUUID(),
        runId,
        ArtifactKind.TRACE,
        "sha256:" + "a".repeat(64),
        128,
        "application/json",
        Path.of("unused"));
  }

  private PreparedArtifact snapshotPrepared() {
    return new PreparedArtifact(
        UUID.randomUUID(),
        runId,
        ArtifactKind.SNAPSHOT,
        "sha256:" + "c".repeat(64),
        256,
        "application/json",
        Path.of("unused"));
  }

  /** A report row whose identity matches the fence and whose JSON hashes into its checksum. */
  private ReplayReport reportWithJson(ReplayOutcome outcome) {
    ReplayReport report = reportWithIdentity(outcome);
    String json = "{\"outcome\":\"" + outcome.name() + "\"}";
    report.setReportJson(json);
    report.setReportChecksum("sha256:" + sha256Hex(json.getBytes(StandardCharsets.UTF_8)));
    return report;
  }

  private ReplayReport reportWithIdentity(ReplayOutcome outcome) {
    ReplayReport report = new ReplayReport();
    report.setRunId(runId);
    report.setAttemptId(attemptId);
    report.setManifestChecksum(runningRun.getManifestChecksum());
    report.setOutcome(outcome);
    report.setControlAllowed(false);
    report.setReportJson("{}");
    report.setReportChecksum("sha256:" + sha256Hex("{}".getBytes(StandardCharsets.UTF_8)));
    return report;
  }

  private ArtifactRef stagedReportRef(ReplayReport report) {
    byte[] jsonBytes = report.getReportJson().getBytes(StandardCharsets.UTF_8);
    ArtifactRef ref =
        new ArtifactRef(
            UUID.randomUUID(),
            runId,
            ArtifactKind.REPORT,
            report.getReportChecksum(),
            jsonBytes.length);
    ref.setMediaType("application/json");
    return ref;
  }

  private ArtifactRef stagedRef(UUID id, ArtifactKind kind, String checksum) {
    ArtifactRef ref = new ArtifactRef(id, runId, kind, checksum, 10);
    ref.setMediaType("application/json");
    return ref;
  }

  private ArtifactPublicationReceipt receiptFor(ArtifactRef... refs) {
    List<PublishedArtifact> entries = new ArrayList<>();
    for (ArtifactRef ref : refs) {
      entries.add(
          new PublishedArtifact(
              ref.getId(),
              ref.getRunId(),
              ref.getKind(),
              ref.getChecksum(),
              ref.getSizeBytes(),
              ref.getMediaType()));
    }
    return new ArtifactPublicationReceipt(runId, entries);
  }

  /** Establishes the successful-finalization cutoff on the run and returns the matching record. */
  private ReplayWorkerPersistenceService.FinalizationReservation reservation(
      ReplayOutcome outcome) {
    LocalDateTime reservedAt = LocalDateTime.now().plusSeconds(7);
    runningRun.setPhase(ReplayRunPhase.FINALIZE_ARTIFACTS);
    runningRun.setFinalizationReservedAt(reservedAt);
    return new ReplayWorkerPersistenceService.FinalizationReservation(
        runId, attemptId, outcome, reservedAt);
  }

  /** A report whose identity matches the fence AND the given decision exactly. */
  private ReplayReport alignedReport(ReplayWorkerPersistenceService.TerminalDecision decision) {
    ReplayReport report = reportWithIdentity(decision.outcome());
    report.setTerminalReason(decision.reason());
    report.setTerminalAt(dbUtcInstant(decision.terminalAt()));
    String json = "{\"aligned\":true}";
    report.setReportJson(json);
    report.setReportChecksum("sha256:" + sha256Hex(json.getBytes(StandardCharsets.UTF_8)));
    return report;
  }

  private static java.time.Instant dbUtcInstant(LocalDateTime t) {
    return t.atZone(java.time.ZoneOffset.UTC).toInstant();
  }

  private ReplayReport reportSavedOnce() {
    ArgumentCaptor<ReplayReport> captor = ArgumentCaptor.forClass(ReplayReport.class);
    org.mockito.Mockito.verify(reportRepository).save(captor.capture());
    return captor.getValue();
  }

  private static String sha256Hex(byte[] content) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(content);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

package com.chromascape.web.replay.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * State/phase separation: the state machine stays strict (RUNNING → RUNNING is illegal) while
 * phase-only progression during execution goes through a fenced update keyed by attempt,
 * generation, owner and lease.
 */
@ExtendWith(MockitoExtension.class)
class ReplayRunStatePhaseFencingTest {

  @Mock private ReplayRunRepository runRepository;
  @Mock private ReplayAttemptRepository attemptRepository;
  @Mock private ReplayCheckpointRepository checkpointRepository;
  @Mock private ReplayEventRepository eventRepository;
  @Mock private ReplayReportRepository reportRepository;
  @Mock private ScenarioManifestRepository manifestRepository;
  @Mock private com.chromascape.web.replay.report.ReplayReportService reportService;
  @Mock private Environment environment;

  private ReplayRunService service;

  @BeforeEach
  void setUp() {
    lenient().when(environment.getProperty(anyString(), eq(Integer.class))).thenReturn(30);
    service =
        new ReplayRunService(
            runRepository,
            attemptRepository,
            checkpointRepository,
            eventRepository,
            reportRepository,
            manifestRepository,
            reportService,
            environment);
    ReflectionTestUtils.setField(service, "leaseDurationSeconds", 30);
  }

  private ReplayRun runIn(ReplayRunState state) {
    ReplayRun run = new ReplayRun();
    run.setId(UUID.randomUUID());
    run.setState(state);
    return run;
  }

  // ── state machine strictness ──

  @Test
  void runningToRunningIsIllegalForPhaseChanges() {
    ReplayRun running = runIn(ReplayRunState.RUNNING);
    ReplayException e =
        assertThrows(
            ReplayException.class,
            () -> service.applyTransition(running, ReplayRunState.RUNNING, ReplayRunPhase.EXECUTE));
    assertEquals(ReplayErrorCode.RUN_STATE_INVALID, e.getErrorCode());
  }

  @Test
  void startingToRunningIsLegalAndSetsStartedAtOnce() {
    ReplayRun starting = runIn(ReplayRunState.STARTING);
    service.applyTransition(starting, ReplayRunState.RUNNING, ReplayRunPhase.RESET);
    assertEquals(ReplayRunState.RUNNING, starting.getState());
    assertEquals(ReplayRunPhase.RESET, starting.getPhase());
  }

  @Test
  void terminalStatesRejectEveryTransition() {
    for (ReplayRunState terminal :
        new ReplayRunState[] {
          ReplayRunState.SUCCEEDED, ReplayRunState.FAILED,
          ReplayRunState.CANCELLED, ReplayRunState.TIMED_OUT
        }) {
      ReplayRun run = runIn(terminal);
      assertThrows(
          ReplayException.class,
          () -> service.applyTransition(run, ReplayRunState.FAILED, null),
          terminal + " must be immutable");
    }
  }

  @Test
  void cancellingMayOnlyTerminalize() {
    ReplayRun cancelling = runIn(ReplayRunState.CANCELLING);
    service.applyTransition(cancelling, ReplayRunState.CANCELLED, null);
    assertEquals(ReplayRunState.CANCELLED, cancelling.getState());
    assertThrows(
        ReplayException.class,
        () ->
            service.applyTransition(
                runIn(ReplayRunState.CANCELLING), ReplayRunState.RUNNING, null));
  }

  // ── phase-only fenced updates ──

  @Test
  void updatePhaseFencedSucceedsWhenLeaseMatches() {
    UUID runId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    ReplayAttempt attempt = attempt(attemptId, runId, 3);

    when(runRepository.updatePhaseFenced(
            eq(runId), eq(attemptId), eq(3), eq("w1"), eq(ReplayRunPhase.EXECUTE.name())))
        .thenReturn(1);

    assertTrue(service.tryUpdatePhaseFenced(runId, attempt, "w1", ReplayRunPhase.EXECUTE));
  }

  @Test
  void updatePhaseFencedThrowsStaleFenceWhenNoRowsUpdated() {
    UUID runId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    ReplayAttempt attempt = attempt(attemptId, runId, 3);

    when(runRepository.updatePhaseFenced(any(), any(), anyInt(), anyString(), anyString()))
        .thenReturn(0);

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                service.updatePhaseFenced(runId, attemptId, 3, "w1", null, ReplayRunPhase.COMPARE));
    assertEquals(ReplayErrorCode.ATTEMPT_STALE_FENCE, e.getErrorCode());
  }

  @Test
  void updatePhaseFencedRejectsWrongGeneration() {
    UUID runId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();

    // Generation 2 does not match the current fence generation 3: the
    // conditional update matches zero rows.
    when(runRepository.updatePhaseFenced(any(), any(), anyInt(), anyString(), anyString()))
        .thenReturn(0);

    assertThrows(
        ReplayException.class,
        () -> service.updatePhaseFenced(runId, attemptId, 2, "w1", null, ReplayRunPhase.EXECUTE),
        "stale generation must be rejected");
  }

  @Test
  void updatePhaseFencedRequiresRunningState() {
    UUID runId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    // Run still STARTING: phase-only updates are forbidden there too.
    when(runRepository.findById(runId)).thenReturn(Optional.of(runIn(ReplayRunState.STARTING)));

    assertThrows(
        ReplayException.class,
        () ->
            service.updatePhaseFenced(
                runId, attemptId, 3, "w1", ReplayRunPhase.START_KERNEL, ReplayRunPhase.EXECUTE),
        "phase-only change requires state=RUNNING");
    verify(runRepository, never())
        .updatePhaseFenced(any(), any(), anyInt(), anyString(), anyString());
  }

  // ── heartbeat fencing ──

  @Test
  void heartbeatRenewsViaAtomicConditionalUpdate() {
    UUID runId = UUID.randomUUID();
    ReplayAttempt attempt = attempt(UUID.randomUUID(), runId, 4);
    when(attemptRepository.renewLeaseFenced(attempt.getId(), runId, 4, "w1", 30)).thenReturn(1);

    service.heartbeatOrThrow(runId, attempt, "w1");
    verify(attemptRepository).renewLeaseFenced(attempt.getId(), runId, 4, "w1", 30);
  }

  @Test
  void heartbeatFromStaleWorkerThrowsStaleFenceWithoutSaving() {
    UUID runId = UUID.randomUUID();
    ReplayAttempt attempt = attempt(UUID.randomUUID(), runId, 4);
    when(attemptRepository.renewLeaseFenced(attempt.getId(), runId, 4, "stale-worker", 30))
        .thenReturn(0);

    ReplayException e =
        assertThrows(
            ReplayException.class, () -> service.heartbeatOrThrow(runId, attempt, "stale-worker"));
    assertEquals(ReplayErrorCode.ATTEMPT_STALE_FENCE, e.getErrorCode());
    verify(attemptRepository, never()).save(any());
  }

  // ── fence validation before durable writes ──

  @Test
  void requireValidFenceDelegatesToDatabaseClockLockingQuery() {
    UUID runId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    int generation = 5;
    ReplayAttempt locked = attempt(attemptId, runId, generation);

    // The native query evaluates every predicate (attempt, run,
    // generation, owner, RUNNING state, unexpired lease, live run
    // state) on the database clock and locks the row FOR UPDATE.
    when(attemptRepository.findValidFencedAttempt(attemptId, runId, generation, "w1"))
        .thenReturn(Optional.of(locked));

    assertEquals(locked, service.requireValidFence(runId, attemptId, generation, "w1"));
  }

  @Test
  void requireValidFenceThrowsStaleFenceWhenQueryReturnsNoRow() {
    UUID runId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();

    // Expired lease, foreign owner, wrong generation, EXPIRED attempt
    // state, or a terminal run all make the query return zero rows.
    when(attemptRepository.findValidFencedAttempt(any(), any(), anyInt(), anyString()))
        .thenReturn(Optional.empty());

    assertThrows(ReplayException.class, () -> service.requireValidFence(runId, attemptId, 5, "w1"));
  }

  @Test
  void requireValidFencePassesExactFencePredicates() {
    UUID runId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();

    assertThrows(
        ReplayException.class,
        () -> service.requireValidFence(runId, attemptId, 6, "other-worker"));

    verify(attemptRepository).findValidFencedAttempt(attemptId, runId, 6, "other-worker");
  }

  // ── atomic claim with database-clock attempt insert ──

  @Test
  void claimCreatesAttemptAndImmutableFence() {
    UUID runId = UUID.randomUUID();
    when(runRepository.claimQueuedRun(runId)).thenReturn(1);
    ReplayRun claimed = runIn(ReplayRunState.CLAIMED);
    when(runRepository.findById(runId)).thenReturn(Optional.of(claimed));

    ReplayAttempt latest = attempt(UUID.randomUUID(), runId, 5);
    latest.setAttemptNo(3);
    lenient()
        .when(attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId))
        .thenReturn(Optional.of(latest));

    ReplayAttempt inserted = attempt(UUID.randomUUID(), runId, 6);
    inserted.setAttemptNo(4);
    inserted.setLeaseOwner("w1");
    when(attemptRepository.findById(any())).thenReturn(Optional.of(inserted));
    when(attemptRepository.insertAttemptWithDatabaseClock(
            any(), eq(runId), eq(4), eq(6), eq("w1"), eq(30)))
        .thenReturn(1);

    ReplayRunService.ClaimResult claim = service.claimAtomically(runId, "w1");

    assertNotNull(claim);
    assertEquals(ReplayRunState.CLAIMED, claim.run().getState());
    assertEquals(inserted.getId(), claim.attempt().getId());
    assertEquals(4, claim.attempt().getAttemptNo());
    assertEquals(6, claim.attempt().getFencingGeneration());
    assertEquals("w1", claim.fence().workerIdentity());
    assertEquals(6, claim.fence().fencingGeneration());
    assertEquals(claim.attempt().getId(), claim.fence().attemptId());
  }

  @Test
  void claimInsertReturningZeroAbortsTheClaim() {
    UUID runId = UUID.randomUUID();
    when(runRepository.claimQueuedRun(runId)).thenReturn(1);
    when(runRepository.findById(runId)).thenReturn(Optional.of(runIn(ReplayRunState.CLAIMED)));
    lenient()
        .when(attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId))
        .thenReturn(Optional.empty());

    // Exactly one row must be inserted; anything else rolls back the
    // entire QUEUED -> CLAIMED transaction in production.
    when(attemptRepository.insertAttemptWithDatabaseClock(
            any(), eq(runId), eq(1), eq(1), eq("w1"), eq(30)))
        .thenReturn(0);

    assertThrows(ReplayException.class, () -> service.claimAtomically(runId, "w1"));
  }

  @Test
  void updatePhaseFencedWithExpectedPhaseRejectsMismatchedCurrentPhase() {
    UUID runId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    ReplayRun starting = runIn(ReplayRunState.STARTING);
    when(runRepository.findById(runId)).thenReturn(Optional.of(starting));

    assertThrows(
        ReplayException.class,
        () ->
            service.updatePhaseFenced(
                runId, attemptId, 3, "w1", ReplayRunPhase.EXECUTE, ReplayRunPhase.COMPARE),
        "expected-phase mismatch is a stale fence");
  }

  private static ReplayAttempt attempt(UUID id, UUID runId, int generation) {
    ReplayAttempt a = new ReplayAttempt();
    a.setId(id);
    a.setRunId(runId);
    a.setAttemptNo(1);
    a.setFencingGeneration(generation);
    a.setLeaseOwner("w1");
    a.setState("RUNNING");
    a.setCreatedAt(java.time.LocalDateTime.now());
    return a;
  }
}

package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.error.ReplayException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class XrspsRecordedActionRunnerTest {

  private final XrspsSessionStore store = new XrspsSessionStore();

  private XrspsSession runningSession(String key) {
    XrspsSession session = store.create(XrspsSessionMode.RECORDED_ACTIONS, key, "scenario:fish-1");
    session.markReady();
    session.start();
    return session;
  }

  private XrspsRecordedAction action(long tick, String id, long expectedRevision, String hash) {
    return new XrspsRecordedAction(tick, id, "INTERACT_FISHING_SPOT", hash, expectedRevision);
  }

  @Test
  void happyPathSubmitsInTickOrder() {
    XrspsSession session = runningSession("runner-key-001");
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(
            store,
            session.getId(),
            List.of(
                action(0, UUID.randomUUID().toString(), 0, "hash-a"),
                action(5, UUID.randomUUID().toString(), 1, "hash-b"),
                action(10, UUID.randomUUID().toString(), 2, "hash-c")));

    XrspsRecordedRunReport report = runner.runRemaining();

    assertTrue(report.completed());
    assertEquals(3, report.submitted());
    assertEquals(10L, report.lastTick());
    assertEquals(3L, report.lastRevision());
    assertNull(report.firstMismatch());
    assertEquals(3, runner.cursor());
  }

  @Test
  void staleExpectationHaltsAtFirstMismatch() {
    XrspsSession session = runningSession("runner-key-002");
    String lateId = UUID.randomUUID().toString();
    String unreachedId = UUID.randomUUID().toString();
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(
            store,
            session.getId(),
            List.of(
                action(0, UUID.randomUUID().toString(), 0, "hash-a"),
                action(2, UUID.randomUUID().toString(), 1, "hash-b"),
                action(4, lateId, 0, "hash-c"),
                action(6, unreachedId, 3, "hash-d")));

    XrspsRecordedRunReport report = runner.runRemaining();

    assertEquals(XrspsRunHaltReason.MISMATCH, report.haltReason());
    assertEquals(lateId, report.firstMismatch().commandId());
    assertEquals(4L, report.firstMismatch().expectedTick());
    assertEquals(0L, report.firstMismatch().expectedRevision());
    assertEquals(2L, report.firstMismatch().actualRevision());
    assertEquals(XrspsReceiptOutcome.REJECTED_STALE_REVISION, report.firstMismatch().outcome());
    assertEquals(2L, session.getObservationRevision());
  }

  @Test
  void pauseHaltsAndResumeCompletes() {
    XrspsSession session = runningSession("runner-key-003");
    String firstId = UUID.randomUUID().toString();
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(
            store,
            session.getId(),
            List.of(
                action(0, firstId, 0, "hash-a"),
                action(3, UUID.randomUUID().toString(), 1, "hash-b")));

    store.submit(
        new XrspsSessionCommand(
            firstId,
            session.getId(),
            0,
            "INTERACT_FISHING_SPOT",
            "hash-a",
            XrspsCommandSource.RECORDED_ACTION,
            session.getLeaseId()));
    session.pause();

    XrspsRecordedRunReport halted = runner.runRemaining();
    assertEquals(XrspsRunHaltReason.SESSION_HALTED, halted.haltReason());
    assertEquals(0, halted.submitted());

    session.resume();
    XrspsRecordedRunReport done = runner.runRemaining();
    assertTrue(done.completed());
    assertEquals(2, done.submitted());
    assertEquals(XrspsReceiptOutcome.DUPLICATE, done.receipts().get(0).outcome());
    assertEquals(XrspsReceiptOutcome.ACCEPTED, done.receipts().get(1).outcome());
  }

  @Test
  void identicalRetryInsideJournalIsBenign() {
    XrspsSession session = runningSession("runner-key-004");
    String id = UUID.randomUUID().toString();
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(
            store,
            session.getId(),
            List.of(action(0, id, 0, "hash-a"), action(0, id, 1, "hash-a")));

    XrspsRecordedRunReport report = runner.runRemaining();

    assertTrue(report.completed());
    assertEquals(XrspsReceiptOutcome.ACCEPTED, report.receipts().get(0).outcome());
    assertEquals(XrspsReceiptOutcome.DUPLICATE, report.receipts().get(1).outcome());
    assertEquals(1L, session.getObservationRevision());
  }

  @Test
  void conflictingReuseHaltsWithMismatch() {
    XrspsSession session = runningSession("runner-key-005");
    String id = UUID.randomUUID().toString();
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(
            store,
            session.getId(),
            List.of(action(0, id, 0, "hash-a"), action(1, id, 1, "hash-b")));

    XrspsRecordedRunReport report = runner.runRemaining();

    assertEquals(XrspsRunHaltReason.MISMATCH, report.haltReason());
    assertEquals(XrspsReceiptOutcome.REJECTED_CONFLICT, report.firstMismatch().outcome());
    assertEquals(1L, session.getObservationRevision());
  }

  @Test
  void invalidJournalsFailFast() {
    XrspsSession session = runningSession("runner-key-006");
    UUID sessionId = session.getId();
    assertThrows(
        ReplayException.class,
        () ->
            new XrspsRecordedActionRunner(
                store,
                sessionId,
                List.of(
                    action(5, UUID.randomUUID().toString(), 0, "h"),
                    action(2, UUID.randomUUID().toString(), 1, "h"))));
    assertThrows(
        ReplayException.class,
        () ->
            new XrspsRecordedActionRunner(
                store, sessionId, List.of(action(-1, UUID.randomUUID().toString(), 0, "h"))));
    assertThrows(
        ReplayException.class,
        () -> new XrspsRecordedActionRunner(store, sessionId, List.of(action(0, " ", 0, "h"))));
    assertThrows(
        ReplayException.class, () -> new XrspsRecordedActionRunner(store, sessionId, List.of()));
  }

  @Test
  void mismatchStaysLatchedAndLaterActionsNeverExecute() {
    XrspsSession session = runningSession("runner-key-007");
    String failedId = UUID.randomUUID().toString();
    String unreachedId = UUID.randomUUID().toString();
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(
            store,
            session.getId(),
            List.of(
                action(0, UUID.randomUUID().toString(), 0, "hash-a"),
                action(1, failedId, 0, "hash-b"),
                action(2, unreachedId, 2, "hash-c")));

    XrspsRecordedRunReport first = runner.runRemaining();
    assertEquals(XrspsRunHaltReason.MISMATCH, first.haltReason());
    assertEquals(failedId, first.firstMismatch().commandId());
    int cursorAfterMismatch = runner.cursor();
    assertEquals(1, cursorAfterMismatch);
    long revisionAfterMismatch = session.getObservationRevision();

    XrspsRecordedRunReport second = runner.runRemaining();
    assertEquals(XrspsRunHaltReason.MISMATCH, second.haltReason());
    assertEquals(failedId, second.firstMismatch().commandId());
    assertEquals(0, second.submitted());
    assertEquals(cursorAfterMismatch, runner.cursor());
    assertEquals(revisionAfterMismatch, session.getObservationRevision());

    XrspsRecordedRunReport third = runner.runRemaining();
    assertEquals(failedId, third.firstMismatch().commandId());
    assertEquals(0, third.submitted());
    assertEquals(revisionAfterMismatch, session.getObservationRevision());
  }

  @Test
  void stepPausedExecutesDueActionsWithoutGoingLate() {
    XrspsSession session = runningSession("runner-key-008");
    session.pause();
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(
            store,
            session.getId(),
            List.of(
                action(0, UUID.randomUUID().toString(), 0, "hash-a"),
                action(3, UUID.randomUUID().toString(), 1, "hash-b"),
                action(5, UUID.randomUUID().toString(), 2, "hash-c")));

    XrspsRecordedRunReport stepped = runner.stepPaused(5);
    assertTrue(stepped.completed());
    assertEquals(3, stepped.submitted());
    assertEquals(5L, session.getTick());
    assertEquals(3L, session.getObservationRevision());
    assertEquals(3, runner.cursor());
  }

  @Test
  void directStepPastPendingActionLeavesItLate() {
    XrspsSession session = runningSession("runner-key-009");
    session.pause();
    session.stepTicks(5);
    session.resume();
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(
            store, session.getId(), List.of(action(3, UUID.randomUUID().toString(), 0, "hash-a")));

    XrspsRecordedRunReport report = runner.runRemaining();
    assertEquals(XrspsRunHaltReason.MISMATCH, report.haltReason());
    assertNull(report.firstMismatch().outcome());
  }

  @Test
  void freshWorkerRestoresViaJournalReplayWithoutDuplicateEffects() {
    XrspsSession session = runningSession("runner-key-010");
    String firstId = UUID.randomUUID().toString();
    String secondId = UUID.randomUUID().toString();
    String thirdId = UUID.randomUUID().toString();
    List<XrspsRecordedAction> journal =
        List.of(
            action(0, firstId, 0, "hash-a"),
            action(2, secondId, 1, "hash-b"),
            action(4, thirdId, 2, "hash-c"));
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(store, session.getId(), journal);
    XrspsRecordedRunReport partial = runner.runRemaining();
    assertTrue(partial.completed());
    assertEquals(3L, session.getObservationRevision());

    String oldLease = session.getLeaseId();
    session.pause();
    session.reportWorkerLoss();
    session.recoverFromCheckpoint(oldLease, "new-lease-010");
    session.resume();

    XrspsRecordedActionRunner freshRunner =
        new XrspsRecordedActionRunner(store, session.getId(), journal);
    XrspsRecordedRunReport replayed = freshRunner.runRemaining();
    assertTrue(replayed.completed());
    assertEquals(3, replayed.submitted());
    assertEquals(XrspsReceiptOutcome.DUPLICATE, replayed.receipts().get(0).outcome());
    assertEquals(XrspsReceiptOutcome.DUPLICATE, replayed.receipts().get(1).outcome());
    assertEquals(XrspsReceiptOutcome.DUPLICATE, replayed.receipts().get(2).outcome());
    assertEquals(3L, session.getObservationRevision());
  }

  @Test
  void oldRunnerCannotAdoptReplacementLeaseOrAdvanceTicks() {
    for (boolean paused : List.of(false, true)) {
      XrspsSession session = runningSession("old-runner-" + paused);
      XrspsRecordedActionRunner runner =
          new XrspsRecordedActionRunner(
              store, session.getId(), List.of(action(5, UUID.randomUUID().toString(), 0, "hash")));
      String oldLease = session.getLeaseId();
      session.reportWorkerLoss();
      session.recoverFromCheckpoint(oldLease, "replacement-" + paused);
      if (!paused) session.resume();
      XrspsRecordedRunReport report = paused ? runner.stepPaused(5) : runner.runRemaining();
      assertEquals(XrspsReceiptOutcome.REJECTED_STALE_LEASE, report.firstMismatch().outcome());
      assertEquals(0, report.submitted());
      assertEquals(0, session.getTick());
      assertEquals(0, session.getObservationRevision());
    }
  }

  @Test
  void pausedDuplicateReplayStillRejectsLaterUnappliedOverdueAction() {
    XrspsSession session = runningSession("paused-late-001");
    XrspsRecordedAction first = action(0, UUID.randomUUID().toString(), 0, "hash-a");
    store.submit(
        new XrspsSessionCommand(
            first.commandId(),
            session.getId(),
            0,
            first.actionType(),
            first.payloadHash(),
            XrspsCommandSource.RECORDED_ACTION,
            session.getLeaseId()));
    session.pause();
    session.stepTicks(5);
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(
            store,
            session.getId(),
            List.of(first, action(2, UUID.randomUUID().toString(), 1, "hash-b")));
    XrspsRecordedRunReport report = runner.stepPaused(1);
    assertEquals(XrspsRunHaltReason.MISMATCH, report.haltReason());
    assertEquals(1, report.submitted());
    assertEquals(XrspsReceiptOutcome.DUPLICATE, report.receipts().get(0).outcome());
    assertEquals(2, report.firstMismatch().expectedTick());
    assertEquals(5, session.getTick());
    assertEquals(1, session.getObservationRevision());
  }
}

package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.error.ReplayException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** In-memory journal scheduling and receipt tests; no kernel or Fishing outcome proof. */
class XrspsSessionExecutionServiceTest {

  private final XrspsSessionExecutionService service = new XrspsSessionExecutionService();

  private XrspsSession runningFishingSession(String key) {
    XrspsSession session =
        service.createAndReady(XrspsSessionMode.RECORDED_ACTIONS, key, "scenario:fish-1");
    service.start(session.getId());
    return session;
  }

  private XrspsRecordedAction action(long tick, String id, long expectedRevision) {
    return new XrspsRecordedAction(tick, id, "INTERACT_FISHING_SPOT", "hash", expectedRevision);
  }

  @Test
  void journalCompletesWithOrderedReceipts() {
    XrspsSession session = runningFishingSession("exec-fish-001");
    List<XrspsRecordedAction> journal =
        List.of(
            action(0, UUID.randomUUID().toString(), 0),
            action(5, UUID.randomUUID().toString(), 1),
            action(10, UUID.randomUUID().toString(), 2));

    XrspsRecordedRunReport report = service.runJournal(session.getId(), journal);

    assertTrue(report.completed());
    assertEquals(3, report.submitted());
    assertEquals(10L, session.getTick());
    assertEquals(3L, session.getObservationRevision());
    assertEquals(
        List.of(
            XrspsReceiptOutcome.ACCEPTED,
            XrspsReceiptOutcome.ACCEPTED,
            XrspsReceiptOutcome.ACCEPTED),
        report.receipts().stream().map(XrspsSessionReceipt::outcome).toList());
  }

  @Test
  void unsupportedActionTypesAreRefusedBeforeMutation() {
    XrspsSession session = runningFishingSession("exec-fish-002");
    List<XrspsRecordedAction> journal =
        List.of(
            new XrspsRecordedAction(0, UUID.randomUUID().toString(), "CAST_BANK_SPELL", "hash", 0));

    assertThrows(ReplayException.class, () -> service.runJournal(session.getId(), journal));
    assertEquals(0L, session.getTick());
    assertEquals(0L, session.getObservationRevision());
  }

  @Test
  void rerunningTheJournalReplaysDuplicatesWithoutNewEffects() {
    XrspsSession session = runningFishingSession("exec-fish-003");
    List<XrspsRecordedAction> journal =
        List.of(
            action(0, UUID.randomUUID().toString(), 0), action(2, UUID.randomUUID().toString(), 1));

    XrspsRecordedRunReport first = service.runJournal(session.getId(), journal);
    assertTrue(first.completed());

    XrspsRecordedRunReport second = service.runJournal(session.getId(), journal);
    assertTrue(second.completed());
    assertEquals(
        List.of(XrspsReceiptOutcome.DUPLICATE, XrspsReceiptOutcome.DUPLICATE),
        second.receipts().stream().map(XrspsSessionReceipt::outcome).toList());
    assertEquals(2L, session.getObservationRevision());
  }

  @Test
  void mismatchReproducesDeterministicallyAcrossRuns() {
    XrspsSession session = runningFishingSession("exec-fish-004");
    String failedId = UUID.randomUUID().toString();
    List<XrspsRecordedAction> journal =
        List.of(
            action(0, UUID.randomUUID().toString(), 0),
            new XrspsRecordedAction(1, failedId, "INTERACT_FISHING_SPOT", "hash", 0),
            action(2, UUID.randomUUID().toString(), 2, "hash"));

    XrspsRecordedRunReport first = service.runJournal(session.getId(), journal);
    assertEquals(XrspsRunHaltReason.MISMATCH, first.haltReason());
    assertEquals(failedId, first.firstMismatch().commandId());

    XrspsRecordedRunReport second = service.runJournal(session.getId(), journal);
    assertEquals(XrspsRunHaltReason.MISMATCH, second.haltReason());
    assertEquals(first.firstMismatch(), second.firstMismatch());
    assertEquals(0, second.submitted());
    assertEquals(1L, session.getObservationRevision());
  }

  private XrspsRecordedAction action(long tick, String id, long expectedRevision, String hash) {
    return new XrspsRecordedAction(tick, id, "INTERACT_FISHING_SPOT", hash, expectedRevision);
  }

  @Test
  void observationExposesSessionCountersAndActionVocabulary() {
    XrspsSession session = runningFishingSession("exec-fish-005");
    service.runJournal(session.getId(), List.of(action(0, UUID.randomUUID().toString(), 0)));

    XrspsSessionExecutionService.Observation observation = service.observe(session.getId());

    assertEquals(session.getId(), observation.sessionId());
    assertEquals(XrspsSessionStatus.RUNNING, observation.status());
    assertEquals(0L, observation.tick());
    assertEquals(1L, observation.observationRevision());
    assertTrue(observation.legalActions().contains("INTERACT_FISHING_SPOT"));
  }

  @Test
  void pausedRunCachesTheJournalAndStepExecutesDueActions() {
    XrspsSession session = runningFishingSession("exec-fish-006");
    service.pause(session.getId());
    List<XrspsRecordedAction> journal =
        List.of(
            action(0, UUID.randomUUID().toString(), 0),
            action(4, UUID.randomUUID().toString(), 1),
            action(6, UUID.randomUUID().toString(), 2));

    XrspsRecordedRunReport halted = service.runJournal(session.getId(), journal);
    assertEquals(XrspsRunHaltReason.SESSION_HALTED, halted.haltReason());
    assertEquals(0, halted.submitted());

    XrspsRecordedRunReport stepped = service.stepJournal(session.getId(), 6);
    assertTrue(stepped.completed());
    assertEquals(3, stepped.submitted());
    assertEquals(6L, session.getTick());
    assertEquals(3L, session.getObservationRevision());
  }

  @Test
  void stepAfterPartialRunReplaysAppliedActionsAsDuplicates() {
    XrspsSessionStore store = new XrspsSessionStore();
    XrspsSessionExecutionService service = new XrspsSessionExecutionService(store);
    XrspsSession session =
        service.createAndReady(
            XrspsSessionMode.RECORDED_ACTIONS, "exec-fish-007", "scenario:fish-1");
    service.start(session.getId());
    String firstId = UUID.randomUUID().toString();
    // Simulate a command already accepted by this session before the runner attaches.
    store.submit(
        new XrspsSessionCommand(
            firstId,
            session.getId(),
            0,
            "INTERACT_FISHING_SPOT",
            "hash",
            XrspsCommandSource.RECORDED_ACTION,
            session.getLeaseId()));
    service.pause(session.getId());
    List<XrspsRecordedAction> full =
        List.of(
            action(0, firstId, 0),
            action(4, UUID.randomUUID().toString(), 1),
            action(6, UUID.randomUUID().toString(), 2));
    XrspsRecordedRunReport halted = service.runJournal(session.getId(), full);
    assertEquals(XrspsRunHaltReason.SESSION_HALTED, halted.haltReason());

    XrspsRecordedRunReport stepped = service.stepJournal(session.getId(), 6);
    assertTrue(stepped.completed());
    assertEquals(XrspsReceiptOutcome.DUPLICATE, stepped.receipts().get(0).outcome());
    assertEquals(6L, session.getTick());
    assertEquals(3L, session.getObservationRevision());
  }

  @Test
  void changedJournalCannotBypassMismatchAndStepReturnsOriginalMismatch() {
    XrspsSession session = runningFishingSession("review-key-001");
    XrspsRecordedAction first = action(0, UUID.randomUUID().toString(), 0);
    XrspsRecordedAction failed = action(1, UUID.randomUUID().toString(), 0);
    XrspsRecordedAction later = action(2, UUID.randomUUID().toString(), 2);
    List<XrspsRecordedAction> journal = List.of(first, failed, later);
    XrspsRecordedRunReport mismatch = service.runJournal(session.getId(), journal);
    XrspsRecordedAction corrected = action(1, failed.commandId(), 1);
    ReplayException conflict =
        assertThrows(
            ReplayException.class,
            () -> service.runJournal(session.getId(), List.of(first, corrected, later)));
    assertEquals(409, conflict.getHttpStatus().value());
    XrspsRecordedRunReport retried = service.runJournal(session.getId(), journal);
    assertEquals(mismatch.firstMismatch(), retried.firstMismatch());
    assertEquals(0, retried.submitted());
    service.pause(session.getId());
    XrspsRecordedRunReport stepped = service.stepJournal(session.getId(), 10);
    assertEquals(mismatch.firstMismatch(), stepped.firstMismatch());
    assertEquals(0, stepped.submitted());
    assertEquals(1, session.getTick());
    assertEquals(1, session.getObservationRevision());
  }

  @Test
  void invalidReplacementPreservesCachedRunnerAndCursor() {
    XrspsSession session = runningFishingSession("review-key-002");
    service.pause(session.getId());
    List<XrspsRecordedAction> journal =
        List.of(
            action(0, UUID.randomUUID().toString(), 0), action(4, UUID.randomUUID().toString(), 1));
    service.runJournal(session.getId(), journal);
    service.stepJournal(session.getId(), 1);
    assertThrows(
        ReplayException.class,
        () ->
            service.runJournal(
                session.getId(),
                List.of(
                    action(5, UUID.randomUUID().toString(), 0),
                    action(2, UUID.randomUUID().toString(), 1))));
    XrspsRecordedRunReport report = service.stepJournal(session.getId(), 3);
    assertTrue(report.completed());
    assertEquals(1, report.submitted());
    assertEquals(4, report.lastTick());
    assertEquals(2, report.lastRevision());
  }

  @Test
  void pausedExecutionRejectsUnappliedOverdueActions() {
    XrspsSession session = runningFishingSession("review-key-003");
    service.pause(session.getId());
    service.runJournal(
        session.getId(),
        List.of(
            action(0, UUID.randomUUID().toString(), 0),
            action(2, UUID.randomUUID().toString(), 1)));
    service.stepTicks(session.getId(), 5);
    XrspsRecordedRunReport report = service.stepJournal(session.getId(), 1);
    assertEquals(XrspsRunHaltReason.MISMATCH, report.haltReason());
    assertEquals(0, report.submitted());
    assertEquals(5, session.getTick());
    assertEquals(0, session.getObservationRevision());
  }

  @Test
  void concurrentIdenticalJournalRequestsApplyOnlyOnce() throws Exception {
    XrspsSession session = runningFishingSession("review-key-004");
    List<XrspsRecordedAction> journal =
        List.of(
            action(0, UUID.randomUUID().toString(), 0), action(4, UUID.randomUUID().toString(), 1));
    var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<XrspsRecordedRunReport>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return service.runJournal(session.getId(), journal);
                }));
      start.countDown();
      long accepted = 0;
      for (var future : futures) {
        var report = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(report.completed());
        accepted +=
            report.receipts().stream()
                .filter(r -> r.outcome() == XrspsReceiptOutcome.ACCEPTED)
                .count();
      }
      assertEquals(2, accepted);
      assertEquals(2, session.getObservationRevision());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void ownerScopedIdempotencyDoesNotShareSessions() {
    XrspsSession alice =
        service.createAndReady(
            XrspsSessionMode.RECORDED_ACTIONS, "owner-key-001", "scenario", "alice");
    XrspsSession bob =
        service.createAndReady(
            XrspsSessionMode.RECORDED_ACTIONS, "owner-key-001", "scenario", "bob");
    org.junit.jupiter.api.Assertions.assertNotEquals(alice.getId(), bob.getId());
    assertEquals(
        alice.getId(),
        service
            .createAndReady(XrspsSessionMode.RECORDED_ACTIONS, "owner-key-001", "scenario", "alice")
            .getId());
    assertEquals(
        404,
        assertThrows(ReplayException.class, () -> service.getOwned(alice.getId(), "bob"))
            .getHttpStatus()
            .value());
  }

  @Test
  void newCoordinatorSharingStoreCannotResetMismatchOrReplaceJournal() {
    XrspsSessionStore store = new XrspsSessionStore();
    XrspsSessionExecutionService first = new XrspsSessionExecutionService(store);
    XrspsSession session =
        first.createAndReady(XrspsSessionMode.RECORDED_ACTIONS, "shared-store-001", "scenario");
    first.start(session.getId());
    List<XrspsRecordedAction> journal =
        List.of(
            action(0, UUID.randomUUID().toString(), 0), action(1, UUID.randomUUID().toString(), 0));
    XrspsRecordedRunReport mismatch = first.runJournal(session.getId(), journal);
    XrspsSessionExecutionService second = new XrspsSessionExecutionService(store);
    var replay = second.runJournal(session.getId(), journal);
    assertEquals(mismatch.firstMismatch(), replay.firstMismatch());
    assertEquals(0, replay.submitted());
    assertThrows(
        ReplayException.class,
        () ->
            second.runJournal(
                session.getId(), List.of(action(1, UUID.randomUUID().toString(), 1))));
  }
}

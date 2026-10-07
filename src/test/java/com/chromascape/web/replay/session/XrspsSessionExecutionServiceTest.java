package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.error.ReplayException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Headless Fishing execution through the authoritative session state: real tick advancement,
 * supported-action dispatch, durable journal replay and lease-fenced recovery.
 */
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
  void headlessFishingJournalCompletesWithOrderedEffects() {
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
    assertEquals(failedId, second.firstMismatch().commandId());
    assertEquals(1L, session.getObservationRevision());
  }

  private XrspsRecordedAction action(long tick, String id, long expectedRevision, String hash) {
    return new XrspsRecordedAction(tick, id, "INTERACT_FISHING_SPOT", hash, expectedRevision);
  }

  @Test
  void observationExposesAuthoritativeStateAndLegalActions() {
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
    XrspsSession session = runningFishingSession("exec-fish-007");
    String firstId = UUID.randomUUID().toString();
    List<XrspsRecordedAction> firstOnly = List.of(action(0, firstId, 0));
    XrspsRecordedRunReport partial = service.runJournal(session.getId(), firstOnly);
    assertTrue(partial.completed());
    service.pause(session.getId());

    List<XrspsRecordedAction> full =
        List.of(
            new XrspsRecordedAction(0, firstId, "INTERACT_FISHING_SPOT", "hash", 0),
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
}

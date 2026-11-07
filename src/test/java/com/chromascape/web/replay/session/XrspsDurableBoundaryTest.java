package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.security.ReplayOwnershipService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit boundary checks; mocked protocol replies do not establish gameplay parity. */
class XrspsDurableBoundaryTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalProjectionService canonical = new CanonicalProjectionService();
  private final UUID id = UUID.randomUUID();
  private XrspsSessionDocument doc;
  private XrspsSessionRepository repository;
  private XrspsSessionWorker workers;
  private XrspsSessionWorker.Boundary worker;
  private XrspsDurableSessionService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    var session =
        new XrspsSession(
            id, XrspsSessionMode.RECORDED_ACTIONS, "boundary-test", "scenario", "alice");
    session.markReady();
    session.start();
    doc = new XrspsSessionDocument();
    doc.lifecycle = session.snapshot();
    var payload =
        mapper
            .createObjectNode()
            .put("type", "INTERACT_NPC")
            .put("targetStableId", "spot")
            .put("option", "lure");
    doc.manifest = mapper.createObjectNode().set("actions", mapper.createArrayNode().add(payload));
    ((com.fasterxml.jackson.databind.node.ObjectNode) doc.manifest)
        .putObject("recording")
        .put("startTick", 20)
        .put("endTick", 230);
    doc.legalActions = List.of("INTERACT_NPC");
    doc.observation = mapper.createObjectNode().put("tick", 20);
    var action =
        new XrspsRecordedAction(
            0,
            UUID.randomUUID().toString(),
            "INTERACT_NPC",
            canonical.actionDigest(payload),
            0,
            payload);
    doc.journal = List.of(action);
    doc.receipts.put(
        action.commandId(),
        new XrspsSessionReceipt(action.commandId(), id, XrspsReceiptOutcome.ACCEPTED, 0, 1, null));
    session.applyAcceptedCommand();
    doc.lifecycle = session.snapshot();
    repository = mock(XrspsSessionRepository.class);
    when(repository.get(id)).thenAnswer(i -> doc);
    when(repository.update(eq(id), any()))
        .thenAnswer(i -> ((Function<XrspsSessionDocument, Object>) i.getArgument(1)).apply(doc));
    workers = mock(XrspsSessionWorker.class);
    worker = mock(XrspsSessionWorker.Boundary.class);
    when(workers.open(any())).thenReturn(worker);
    when(workers.openPaced(any())).thenReturn(worker);
    when(worker.call(eq("step"), any()))
        .thenAnswer(
            i -> {
              long tick =
                  ((com.fasterxml.jackson.databind.JsonNode) i.getArgument(1))
                          .path("expectedTick")
                          .asLong()
                      + 1;
              return mapper
                  .createObjectNode()
                  .put("terminalTick", tick)
                  .set("observation", mapper.createObjectNode().put("tick", tick));
            });
    service =
        new XrspsDurableSessionService(
            repository,
            mock(ScenarioManifestRepository.class),
            workers,
            mapper,
            canonical,
            mock(ReplayOwnershipService.class));
  }

  @Test
  void finishReachesEndTickAndRetryHasNoRepeatedStepsOrTaskSuccessClaim() {
    var result = service.finishRecording(id);
    assertEquals(210, result.tick());
    assertEquals(XrspsSessionStatus.SUCCEEDED, result.status());
    assertTrue(result.recordingExecuted());
    assertFalse(result.taskVerified());
    assertEquals(230, doc.observation.path("tick").asLong());
    assertEquals(result, service.finishRecording(id));
    verify(worker, times(210)).call(eq("step"), any());
    verify(worker, times(3)).checkpoint(any());
  }

  @Test
  void pauseBetweenCommittedSegmentsStopsFinishingUntilResume() {
    doAnswer(
            i -> {
              var s = XrspsSession.restore(doc.lifecycle);
              s.pause();
              doc.lifecycle = s.snapshot();
              return null;
            })
        .when(worker)
        .checkpoint(any());
    var result = service.finishRecording(id);
    assertEquals(100, result.tick());
    assertEquals(XrspsSessionStatus.PAUSED, result.status());
    assertFalse(result.recordingExecuted());
    verify(worker, times(100)).call(eq("step"), any());
  }

  @Test
  void missingReceiptOrOmittedPinnedActionRefusesBeforeWorkerExecution() {
    doc.receipts.clear();
    assertThrows(ReplayException.class, () -> service.finishRecording(id));
    verifyNoInteractions(workers);
  }

  @Test
  void alteredSourceOrderAndSourceTimingCannotFinish() {
    ((com.fasterxml.jackson.databind.node.ObjectNode) doc.manifest.path("actions").get(0))
        .put("sourceActionTick", 23);
    var original = doc.journal.get(0);
    var payload = doc.manifest.path("actions").get(0).deepCopy();
    doc.journal =
        List.of(
            new XrspsRecordedAction(
                0,
                original.commandId(),
                original.actionType(),
                canonical.actionDigest(payload),
                0,
                payload));
    assertThrows(ReplayException.class, () -> service.finishRecording(id));
    verifyNoInteractions(workers);
  }

  @Test
  void omittedPinnedActionsCannotBeReportedExecuted() {
    ((com.fasterxml.jackson.databind.node.ArrayNode) doc.manifest.path("actions"))
        .add(doc.manifest.path("actions").get(0).deepCopy());
    assertThrows(ReplayException.class, () -> service.finishRecording(id));
    verifyNoInteractions(workers);
  }

  @Test
  void cancelledRecordingCannotAdvanceOrClaimExecution() {
    var s = XrspsSession.restore(doc.lifecycle);
    s.cancel("user stopped");
    doc.lifecycle = s.snapshot();
    var result = service.finishRecording(id);
    assertEquals(XrspsSessionStatus.CANCELLED, result.status());
    assertFalse(result.recordingExecuted());
    verifyNoInteractions(workers);
  }

  @Test
  void mismatchMakesLifecycleTerminalAndCannotBeResetByRetry() {
    doc.receipts.clear();
    var first = service.run(id, doc.journal);
    assertEquals(XrspsRunHaltReason.MISMATCH, first.haltReason());
    assertEquals(XrspsSessionStatus.FAILED, service.get(id).getStatus());
    var retry = service.run(id, doc.journal);
    assertEquals(first.firstMismatch(), retry.firstMismatch());
    assertEquals(0, retry.submitted());
    verifyNoInteractions(workers);
  }

  @Test
  void timedJournalDispatchesExplicitSourceIndexesWithoutProvenancePayloadAndRetriesNoEffects() {
    var journal = timedJournal();
    when(worker.call(eq("session.apply"), any()))
        .thenAnswer(
            i -> {
              var params = (com.fasterxml.jackson.databind.JsonNode) i.getArgument(1);
              assertFalse(params.path("action").has("sourceActionTick"));
              return mapper
                  .createObjectNode()
                  .put("actionApplied", true)
                  .put("terminalTick", params.path("expectedTick").asLong())
                  .set(
                      "observation",
                      mapper.createObjectNode().put("tick", params.path("expectedTick").asLong()));
            });
    var result = service.run(id, journal);
    assertEquals(XrspsRunHaltReason.COMPLETED, result.haltReason());
    var params = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
    verify(worker, times(3)).call(eq("session.apply"), params.capture());
    assertEquals(
        List.of(0, 1, 2),
        params.getAllValues().stream().map(p -> p.path("sourceActionIndex").asInt(-1)).toList());
    assertEquals(
        List.of(20L, 20L, 23L),
        params.getAllValues().stream().map(p -> p.path("expectedTick").asLong()).toList());
    assertEquals(3, doc.receipts.size());
    service.run(id, journal);
    verify(worker, times(3)).call(eq("session.apply"), any());
    assertTrue(service.finishRecording(id).recordingExecuted());
  }

  @Test
  void timedJournalWrongTickPayloadOrOmittedSourcePrefixRefusesBeforeAnyWorkerCall() {
    for (int variant = 0; variant < 3; variant++) {
      var journal = new java.util.ArrayList<>(timedJournal());
      var first = journal.get(0);
      if (variant == 0)
        journal.set(
            0,
            new XrspsRecordedAction(
                1, first.commandId(), first.actionType(), first.payloadHash(), 0, first.payload()));
      if (variant == 1) {
        var altered = first.payload().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) altered).put("option", "bait");
        journal.set(
            0,
            new XrspsRecordedAction(
                0,
                first.commandId(),
                first.actionType(),
                canonical.actionDigest(altered),
                0,
                altered));
      }
      if (variant == 2) journal.remove(0);
      assertThrows(ReplayException.class, () -> service.run(id, journal));
      verifyNoInteractions(workers);
      assertTrue(doc.receipts.isEmpty());
    }
  }

  private List<XrspsRecordedAction> timedJournal() {
    var session =
        new XrspsSession(id, XrspsSessionMode.RECORDED_ACTIONS, "timed", "scenario", "alice");
    session.markReady();
    session.start();
    doc.lifecycle = session.snapshot();
    doc.receipts.clear();
    doc.journal = List.of();
    doc.mismatch = null;
    ((com.fasterxml.jackson.databind.node.ObjectNode) doc.manifest).put("manifestVersion", 2);
    var actions =
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc.manifest).putArray("actions");
    var journal = new java.util.ArrayList<XrspsRecordedAction>();
    for (int index = 0; index < 3; index++) {
      int tick = index < 2 ? 20 : 23;
      var source =
          actions
              .addObject()
              .put("type", "INTERACT_NPC")
              .put("targetStableId", "spot")
              .put("option", "lure")
              .put("sourceActionTick", tick)
              .put("sourceActionSequence", index);
      var payload = com.chromascape.web.replay.worker.ReplaySourceSchedule.semanticAction(source);
      journal.add(
          new XrspsRecordedAction(
              tick - 20,
              UUID.randomUUID().toString(),
              "INTERACT_NPC",
              canonical.actionDigest(payload),
              index,
              payload));
    }
    return journal;
  }
}

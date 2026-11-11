package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.gameplay.contract.*;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.*;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.*;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class XrspsAiTaskCoordinatorTest {
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  final CanonicalGameplayJson json = new CanonicalGameplayJson(mapper);
  final GameplayContractValidator contracts = new GameplayContractValidator(mapper);
  final XrspsDurableSessionService sessions = mock(XrspsDurableSessionService.class);
  final GameplayProviderRuntime runtime = mock(GameplayProviderRuntime.class);
  final XrspsAiTaskCoordinator coordinator =
      new XrspsAiTaskCoordinator(
          mock(XrspsSessionRepository.class),
          sessions,
          runtime,
          new ProviderRuntimeValidator(
            contracts, new com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator(mapper), json),
          mock(ReplayPrincipalService.class),
          mapper,
          json);

  XrspsSessionDocument document() throws Exception {
    var session =
        new XrspsSessionStore()
            .create(XrspsSessionMode.AI_TASK, "ai-fixture-key", "scenario", "owner");
    session.markReady();
    var doc = new XrspsSessionDocument();
    doc.lifecycle = session.snapshot();
    doc.legalActions.add("INTERACT_NPC");
    doc.manifest =
        mapper.readTree(
            """
    {"recording":{"recordingId":"recording-native","startTick":0,"endTick":200},"runtime":{"xrspsBuildCommit":"abcd1234"},
    "initialState":{"npcs":[{"stableId":"spot:1","spotId":"sea_small_net","position":{"x":3081}}]},
    "actions":[{"type":"INTERACT_NPC","targetStableId":"spot:1","option":"net"}]}
    """);
    doc.observation =
        mapper.readTree(
            """
    {"tick":0,"player":{"position":{"x":3080,"y":3227,"plane":0},"skills":{"fishing":{"level":1,"xp":0}},
    "inventory":[{"slot":0,"itemId":303,"quantity":1}]},"taskState":{"fishingActive":false},"npcs":[{"stableId":"spot:1","position":{"x":3081,"y":3227,"plane":0}}],"objects":[]}
    """);
    return doc;
  }

  void prepare(XrspsSessionDocument doc) {
    coordinator.prepare(
        doc,
        new XrspsAiTaskCoordinator.FishingGoal(Provider.CODEX, "configured-model", 2, 100, 180));
  }

  @Test
  void readySessionDeserializedFromDatabaseStillAcceptsTaskConfiguration() throws Exception {
    var doc = mapper.readValue(mapper.writeValueAsString(document()), XrspsSessionDocument.class);
    assertTrue(doc.aiTask.isNull());
    prepare(doc);
    assertEquals(XrspsSessionStatus.RUNNING, doc.lifecycle.status());
    assertTrue(doc.aiProviderTurn.isObject());
    var persisted = mapper.readValue(mapper.writeValueAsString(doc), XrspsSessionDocument.class);
    var request = mapper.convertValue(persisted.aiProviderSession, SessionRequest.class);
    var turn = mapper.convertValue(persisted.aiProviderTurn, TurnRequest.class);
    new ProviderRuntimeValidator(
            contracts, new com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator(mapper), json).validateTurn(request, turn);
  }

  @Test
  void providerReceivesSchemaValidNativeObservationAndExactBoundedTask() throws Exception {
    var doc = document();
    prepare(doc);
    var request = mapper.convertValue(doc.aiProviderSession, SessionRequest.class);
    var turn = mapper.convertValue(doc.aiProviderTurn, TurnRequest.class);
    new ProviderRuntimeValidator(
            contracts, new com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator(mapper), json).validateTurn(request, turn);
    assertEquals(
        "xrsps-offline", turn.observation().path("identities").path("environmentId").asText());
    assertEquals(0, turn.observation().path("skills").path("fishingXp").asInt());
    assertEquals(
        "DRAYNOR", turn.eligibleActions().get(0).path("settings").path("areaPolicy").asText());
    assertEquals(XrspsSessionStatus.RUNNING, doc.lifecycle.status());
    verifyNoInteractions(runtime, sessions);
  }

  @Test
  void decisionFromDifferentConfiguredModelCannotDispatch() throws Exception {
    var doc = document();
    prepare(doc);
    var request = mapper.convertValue(doc.aiProviderSession, SessionRequest.class);
    var turn = mapper.convertValue(doc.aiProviderTurn, TurnRequest.class);
    var decision =
        new GameplayContracts.ProviderDecision(
            "sc-1429/v1",
            Provider.CODEX,
            "different-model",
            "decision-" + doc.lifecycle.id(),
            1.0,
            turn.eligibleActions().get(0),
            java.util.List.of());
    String digest = request.promptDigest();
    var persisted =
        new ProviderDecisionHandoff.PersistedDecision(
            request.sessionId(),
            turn.turnId(),
            turn.requestId(),
            Provider.CODEX,
            "different-model",
            digest,
            digest,
            turn.observationDigest(),
            request.promptDigest(),
            request.toolsetDigest(),
            request.knowledgeDigest(),
            request.goalPlanDigest(),
            request.taskSkillDigests(),
            digest,
            digest,
            TurnState.DECIDED,
            FailureCode.NONE,
            decision);
    assertTrue(persisted.bindable());
    assertThrows(ReplayException.class, () -> coordinator.accept(doc, persisted));
    assertTrue(doc.journal.isEmpty());
    verifyNoInteractions(sessions);
  }

  @Test
  void acceptedReceiptCannotCompleteTaskWithoutNativeOutcome() throws Exception {
    var doc = document();
    prepare(doc);
    ((ObjectNode) doc.observation.path("taskState")).put("fishingActive", true);
    coordinator.advanceTask(doc);
    assertNull(doc.aiVerification);
    assertEquals(XrspsSessionStatus.RUNNING, doc.lifecycle.status());
    verify(sessions).advance(doc, 1, false);
    verify(sessions, never()).verifyAiJournal(any());
  }

  @Test
  void nativeGoalRequiresMatchingJournalReplayBeforeSuccess() throws Exception {
    var doc = document();
    prepare(doc);
    ((ObjectNode) doc.observation.path("player").path("skills").path("fishing"))
        .put("level", 2)
        .put("xp", 100);
    ((com.fasterxml.jackson.databind.node.ArrayNode)
            doc.observation.path("player").path("inventory"))
        .addObject()
        .put("itemId", 317)
        .put("quantity", 1);
    when(sessions.verifyAiJournal(doc)).thenReturn("sha256:verified");
    coordinator.advanceTask(doc);
    assertEquals(XrspsSessionStatus.SUCCEEDED, doc.lifecycle.status());
    assertTrue(doc.aiVerification.path("journalReproduced").asBoolean());
    verify(sessions).verifyAiJournal(doc);
  }

  @Test
  void staleLeaseAndUnsupportedSourceNeverAdvanceWorld() throws Exception {
    var doc = document();
    prepare(doc);
    ((ObjectNode) doc.aiTask).put("lease", UUID.randomUUID().toString());
    assertThrows(ReplayException.class, () -> coordinator.advanceTask(doc));
    verifyNoInteractions(sessions);
    var unsupported = document();
    ((ObjectNode) unsupported.manifest).putArray("actions");
    assertThrows(ReplayException.class, () -> prepare(unsupported));
    assertEquals(XrspsSessionStatus.READY, unsupported.lifecycle.status());
  }
}

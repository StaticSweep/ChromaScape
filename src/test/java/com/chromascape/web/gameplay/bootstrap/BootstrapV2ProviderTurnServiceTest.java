package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.GameplayProviderRuntime;
import com.chromascape.web.gameplay.provider.runtime.GameplayProviderRuntime.Submission;
import com.chromascape.web.gameplay.provider.runtime.ProviderProposalSchemaV2;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetLimits;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The turn service must ask the provider about exactly the observation the draft will be assembled
 * from, and must pin only digests it can derive from attested artifacts.
 */
class BootstrapV2ProviderTurnServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-11T02:00:00Z");

  private ObjectMapper objectMapper;
  private CanonicalGameplayJson canonicalJson;
  private BootstrapV2Wire wire;
  private BootstrapV2SnapshotMapper mapper;
  private GameplayProviderRuntime runtime;
  private BootstrapV2DraftService drafts;
  private BootstrapV2ProviderTurnService service;

  private JsonNode catalogSnapshot;
  private JsonNode observation;
  private JsonNode planningInput;
  private JsonNode eligibilityResult;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    canonicalJson = new CanonicalGameplayJson(objectMapper);
    wire = mock(BootstrapV2Wire.class);
    mapper = mock(BootstrapV2SnapshotMapper.class);
    runtime = mock(GameplayProviderRuntime.class);
    drafts = mock(BootstrapV2DraftService.class);
    service =
        new BootstrapV2ProviderTurnService(
            wire, mapper, runtime, drafts, canonicalJson, Clock.fixed(NOW, ZoneOffset.UTC));

    JsonNode catalogWire =
        objectMapper.readTree(
            "{\"enabled\":true,\"operations\":[\"CATALOG\",\"OBSERVE\",\"RESERVE\"],"
                + "\"buildId\":\"build-1\",\"catalogRevision\":2}");
    JsonNode observeWire =
        objectMapper.readTree(
            "{\"clientId\":\"client-1\",\"buildId\":\"build-1\",\"observationRevision\":1,"
                + "\"taskRevision\":1}");
    JsonNode observationWire = objectMapper.readTree("{\"capturedTick\":10}");
    catalogSnapshot =
        objectMapper.readTree(
            "{\"contractVersion\":\"sc-1429/v2\",\"catalogDigest\":\"sha256:"
                + "a".repeat(64)
                + "\",\"tasks\":[{\"taskKey\":\"FISHING_TRAINING\"}]}");
    observation =
        objectMapper.readTree(
            "{\"contractVersion\":\"sc-1429/v2\",\"revision\":1,\"capturedTick\":10}");
    planningInput = objectMapper.readTree("{\"contractVersion\":\"sc-1429/v2\",\"mode\":\"LIVE_TASK\"}");
    eligibilityResult = objectMapper.readTree("{\"contractVersion\":\"sc-1429/v2\",\"entries\":[]}");
    when(wire.catalog()).thenReturn(catalogWire);
    when(wire.observe()).thenReturn(observeWire);
    when(wire.observation()).thenReturn(observationWire);
    when(mapper.catalogSnapshot(any(), any())).thenReturn(catalogSnapshot);
    when(mapper.semanticObservation(any(), any(), any(), any(), any())).thenReturn(observation);
    when(mapper.planningInput(any(), any(), any(), any(), any())).thenReturn(planningInput);
    when(mapper.eligibilityResult(any(), any(), any(), any())).thenReturn(eligibilityResult);
    when(runtime.submitTurn(any(), any()))
        .thenReturn(new Submission(turn(TurnState.SUBMITTED), true));
    when(runtime.turn(any(), any())).thenReturn(turn(TurnState.DECIDED));
    when(drafts.preview(any(), any()))
        .thenReturn(
            new BootstrapV2DraftService.Preview(
                "s",
                "t",
                "req-1",
                "idem-1",
                "recording-1",
                null,
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                java.util.Map.of(),
                NOW));
  }

  @Test
  void asksTheProviderAboutTheSameObservationTheDraftIsAssembledFrom() {
    var prepared = service.prepare(preparation());

    ArgumentCaptor<TurnRequest> turnCaptor = ArgumentCaptor.forClass(TurnRequest.class);
    verify(runtime).submitTurn(any(), turnCaptor.capture());
    assertThat(turnCaptor.getValue().observation())
        .as("the turn carries the captured observation")
        .isEqualTo(observation);
    verify(drafts).preview(any(), eq(observation));
    assertThat(prepared.turnState()).isEqualTo("DECIDED");
    assertThat(prepared.sessionId()).isNotBlank();
  }

  @Test
  void pinsOnlyDigestsDerivedFromAttestedArtifacts() {
    service.prepare(preparation());

    ArgumentCaptor<SessionRequest> sessionCaptor = ArgumentCaptor.forClass(SessionRequest.class);
    verify(runtime).createSession(sessionCaptor.capture());
    SessionRequest session = sessionCaptor.getValue();
    assertThat(session.gameplayContractVersion()).isEqualTo("sc-1429/v2");
    assertThat(session.promptDigest()).isEqualTo(canonicalJson.digest(ProviderProposalSchemaV2.JSON));
    assertThat(session.toolsetDigest())
        .isEqualTo(canonicalJson.digest(wire.catalog().path("operations")));
    assertThat(session.knowledgeDigest())
        .isEqualTo(canonicalJson.digest(catalogSnapshot.path("tasks")));
    assertThat(session.goalPlanDigest()).isEqualTo(canonicalJson.digest(planningInput));
    assertThat(session.taskSkillDigests())
        .containsExactly(canonicalJson.digest(catalogSnapshot.path("tasks").path(0)));
  }

  @Test
  void aTerminalNonDecisionIsRefusedWithoutAssemblingADraft() {
    when(runtime.turn(any(), any())).thenReturn(turn(TurnState.INVALID_OUTPUT));

    assertThatThrownBy(() -> service.prepare(preparation()))
        .isInstanceOf(BootstrapV2ProviderTurnService.TurnException.class)
        .hasMessageContaining("INVALID_OUTPUT");
    verify(drafts, never()).preview(any(), any());
  }

  @Test
  void anExpiredDeadlineIsRefusedBeforeAnyWireOrProviderCall() {
    BootstrapV2ProviderTurnService.TurnPreparation expired =
        new BootstrapV2ProviderTurnService.TurnPreparation(
            Provider.MISTRAL,
            "model-1",
            "FISHING_TRAINING",
            "run-1",
            "recording-1",
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode(),
            limits(),
            NOW.minusSeconds(1),
            "req-1",
            "idem-1",
            "authority-1",
            "operator-1",
            NOW.plusSeconds(600),
            1,
            1,
            "source-1",
            100,
            100);

    assertThatThrownBy(() -> service.prepare(expired))
        .isInstanceOf(BootstrapV2ProviderTurnService.TurnException.class)
        .hasMessageContaining("deadline");
    verify(wire, never()).catalog();
    verify(runtime, never()).createSession(any());
  }

  private BootstrapV2ProviderTurnService.TurnPreparation preparation() {
    return new BootstrapV2ProviderTurnService.TurnPreparation(
        Provider.MISTRAL,
        "model-1",
        "FISHING_TRAINING",
        "run-1",
        "recording-1",
        objectMapper.createObjectNode(),
        objectMapper.createObjectNode(),
        limits(),
        NOW.plusSeconds(120),
        "req-1",
        "idem-1",
        "authority-1",
        "operator-1",
        NOW.plusSeconds(600),
        1,
        1,
        "source-1",
        100,
        100);
  }

  private BudgetLimits limits() {
    return new BudgetLimits(1000, 1000, 1, 0, 1, 60_000, 120_000);
  }

  private TurnView turn(TurnState state) {
    return new TurnView(
        "sc-1430/v1",
        "session-1",
        "turn-1",
        "req-1",
        "sha256:" + "b".repeat(64),
        Provider.MISTRAL,
        "model-1",
        state,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        java.util.List.of(),
        null,
        NOW,
        NOW,
        1,
        true);
  }
}

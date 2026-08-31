package com.chromascape.web.gameplay.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Outcome;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapGameplayOrchestratorTest {
  private static final Instant NOW = Instant.parse("2026-08-30T12:05:00Z");
  private ObjectMapper objectMapper;
  private CanonicalGameplayJson canonicalJson;
  private GameplayContractValidator validator;
  private JsonNode fixtures;

  @TempDir Path temporaryDirectory;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    canonicalJson = new CanonicalGameplayJson(objectMapper);
    validator = new GameplayContractValidator(objectMapper);
    fixtures =
        objectMapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
  }

  @Test
  void fakeAccountBuilderCoversTerminalAndUncertainPathsWithAtomicEvidence() throws Exception {
    List<Case> cases =
        List.of(
            new Case(Outcome.CONFIRMED, null, true, true),
            new Case(Outcome.DENIED, "POLICY_DENIED", false, true),
            new Case(Outcome.FAILED, "DISPATCH_REJECTED", false, true),
            new Case(Outcome.STOPPED, null, true, true),
            new Case(Outcome.UNCERTAIN_OUTCOME, "LOST_ACK", true, false));

    for (int index = 0; index < cases.size(); index++) {
      Case testCase = cases.get(index);
      Bundle bundle = bundle("case" + index);
      FakeGateway gateway = new FakeGateway(testCase);
      BootstrapGameplayOrchestrator orchestrator = orchestrator(gateway, temporaryDirectory);

      var receipt =
          orchestrator.dispatch(
              bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

      assertThat(receipt.outcome()).isEqualTo(testCase.outcome());
      assertThat(receipt.retryAllowed())
          .isEqualTo(testCase.outcome() == Outcome.FAILED && !testCase.deliveryAcknowledged());
      Path evidence =
          temporaryDirectory.resolve(
              bundle.envelope().identities().path("recordingId").asText() + ".sc-1498.json");
      assertThat(evidence).exists();
      JsonNode document = objectMapper.readTree(evidence.toFile());
      assertThat(document.at("/provenance/origin").asText()).isEqualTo("CLAUDE");
      assertThat(document.path("learnedModelActivated").asBoolean()).isFalse();
      assertThat(document.path("continuingAuthority").asBoolean()).isFalse();
      assertThat(document.path("simulatorTrace").asBoolean()).isFalse();
      assertThat(document.path("receipts")).hasSize(3);
      assertThat(gateway.dispatches).hasValue(1);
    }
  }

  @Test
  void identicalRequestReconcilesAndConflictingIdempotencyFailsBeforeDispatch() {
    Bundle first = bundle("same");
    FakeGateway gateway = new FakeGateway(new Case(Outcome.CONFIRMED, null, true, true));
    BootstrapGameplayOrchestrator orchestrator = orchestrator(gateway, temporaryDirectory);

    var initial =
        orchestrator.dispatch(
            first.observation(), first.decision(), first.envelope(), first.authority());
    var identical =
        orchestrator.dispatch(
            first.observation(), first.decision(), first.envelope(), first.authority());
    Bundle conflict = bundle("conflict");
    ExecutionEnvelope conflictingEnvelope =
        copyEnvelopeWithIdempotency(conflict.envelope(), first.envelope().idempotencyKey());
    HumanAuthority conflictingAuthority = authorityFor(conflictingEnvelope);
    var denied =
        orchestrator.dispatch(
            conflict.observation(), conflict.decision(), conflictingEnvelope, conflictingAuthority);

    assertThat(identical).isEqualTo(initial);
    assertThat(denied.outcome()).isEqualTo(Outcome.DENIED);
    assertThat(denied.failureCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(gateway.dispatches).hasValue(1);
  }

  @Test
  void staleObservationFailsBeforeAccountBuilderReservation() {
    Bundle bundle = bundle("stale");
    var envelopeNode = objectMapper.valueToTree(bundle.envelope());
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelopeNode)
        .put("expectedObservationRevision", 40);
    ExecutionEnvelope stale = objectMapper.convertValue(envelopeNode, ExecutionEnvelope.class);
    FakeGateway gateway = new FakeGateway(new Case(Outcome.CONFIRMED, null, true, true));
    BootstrapGameplayOrchestrator orchestrator = orchestrator(gateway, temporaryDirectory);

    var receipt =
        orchestrator.dispatch(bundle.observation(), bundle.decision(), stale, authorityFor(stale));

    assertThat(receipt.outcome()).isEqualTo(Outcome.DENIED);
    assertThat(receipt.failureCode()).isEqualTo("REVISION_MISMATCH");
    assertThat(gateway.dispatches).hasValue(0);
  }

  private BootstrapGameplayOrchestrator orchestrator(FakeGateway gateway, Path root) {
    BootstrapEvidenceStore store = new BootstrapEvidenceStore(objectMapper, canonicalJson, root);
    return new BootstrapGameplayOrchestrator(
        validator, canonicalJson, gateway, store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Bundle bundle(String suffix) {
    JsonNode observation = fixtures.path("observation").deepCopy();
    JsonNode action = fixtures.path("semanticAction").deepCopy();
    JsonNode envelopeNode = fixtures.path("executionEnvelope").deepCopy();
    var observationIdentities =
        (com.fasterxml.jackson.databind.node.ObjectNode) observation.path("identities");
    var envelopeIdentities =
        (com.fasterxml.jackson.databind.node.ObjectNode) envelopeNode.path("identities");
    for (String field : List.of("runId", "taskId", "sessionId", "recordingId")) {
      String value = envelopeIdentities.path(field).asText() + "-" + suffix;
      observationIdentities.put(field, value);
      envelopeIdentities.put(field, value);
    }
    var envelopeObject = (com.fasterxml.jackson.databind.node.ObjectNode) envelopeNode;
    envelopeObject.put("requestId", "request-" + suffix);
    envelopeObject.put("idempotencyKey", "idempotency-" + suffix);
    envelopeObject.put("authorityId", "authority-" + suffix);
    envelopeObject.put("semanticActionDigest", canonicalJson.digest(action));
    ExecutionEnvelope envelope = objectMapper.convertValue(envelopeNode, ExecutionEnvelope.class);
    ProviderDecision decision =
        new ProviderDecision(
            "sc-1429/v1",
            Provider.CLAUDE,
            envelope.modelVersion(),
            envelope.decisionId(),
            0.9,
            action,
            List.of("observation:" + observation.path("observationId").asText()));
    return new Bundle(observation, decision, envelope, authorityFor(envelope));
  }

  private HumanAuthority authorityFor(ExecutionEnvelope envelope) {
    return new HumanAuthority(
        "sc-1429/v1",
        envelope.authorityId(),
        "HUMAN",
        "test-operator",
        Instant.parse("2026-08-30T12:00:00Z"),
        Instant.parse("2026-08-30T12:10:00Z"),
        canonicalJson.digest(envelope),
        fixtures.at("/expectedDigests/approvedScope").asText(),
        true,
        "hmac-sha256:" + "0".repeat(64));
  }

  private ExecutionEnvelope copyEnvelopeWithIdempotency(
      ExecutionEnvelope envelope, String idempotencyKey) {
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.valueToTree(envelope);
    node.put("idempotencyKey", idempotencyKey);
    return objectMapper.convertValue(node, ExecutionEnvelope.class);
  }

  private record Bundle(
      JsonNode observation,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      HumanAuthority authority) {}

  private record Case(
      Outcome outcome,
      String failureCode,
      boolean deliveryAcknowledged,
      boolean terminalReconciled) {}

  private static final class FakeGateway implements AccountBuilderGameplayGateway {
    private final Case result;
    private final AtomicInteger dispatches = new AtomicInteger();

    private FakeGateway(Case result) {
      this.result = result;
    }

    @Override
    public DispatchResult dispatch(ExecutionEnvelope envelope, HumanAuthority authority) {
      dispatches.incrementAndGet();
      return new DispatchResult(
          result.outcome(),
          result.failureCode(),
          result.deliveryAcknowledged(),
          result.terminalReconciled(),
          List.of("fake:accountbuilder:" + result.outcome().name().toLowerCase()));
    }

    @Override
    public DispatchResult stop(String requestId, String reason, boolean killSwitch) {
      return new DispatchResult(Outcome.STOPPED, null, true, true, List.of("fake:stop"));
    }
  }
}

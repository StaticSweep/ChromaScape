package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.ProviderOrchestrationCommand;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff.PersistedDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Production-path proof that the provider orchestration route binds a persisted terminal provider
 * decision through {@code ProviderDecisionOrchestrationSource} before any durable claim. The
 * provider decision, its provenance and the source type are never caller input on this route.
 *
 * <p>The envelope, observation, action and every provenance digest are derived from the shared
 * SC-1429 golden fixture, so the one valid case really does satisfy the full immutable contract
 * rather than a synthetic subset.
 */
class ProviderDecisionOrchestrationIntegrationTest {
  private static final String PROMPT_DIGEST = "sha256:" + "3".repeat(64);
  private static final String TRANSCRIPT = "sha256:" + "7".repeat(64);
  private static final String PREFLIGHT_CAPABILITY_DIGEST = "sha256:" + "0".repeat(64);
  private static final String SESSION_ID = "session-codex-1";
  private static final String TURN_ID = "turn-codex-1";
  private static final String ORCHESTRATION_ID = "orchestration-sc1429-001";

  private ObjectMapper mapper;
  private CanonicalGameplayJson canonicalJson;
  private AccountBuilderOrchestrationValidator orchestrationValidator;
  private AccountBuilderOrchestrationRequestFactory requestFactory;
  private InMemoryAccountBuilderOrchestrationStore store;
  private CountingGateway gateway;
  private AccountBuilderOrchestrationService service;
  private JsonNode fixtures;
  private ObjectNode observationNode;

  @BeforeEach
  void setUp() throws Exception {
    mapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    canonicalJson = new CanonicalGameplayJson(mapper);
    orchestrationValidator =
        new AccountBuilderOrchestrationValidator(canonicalJson, (authority, envelope) -> true);
    requestFactory =
        new AccountBuilderOrchestrationRequestFactory(orchestrationValidator, canonicalJson);
    store =
        new InMemoryAccountBuilderOrchestrationStore(
            java.time.Clock.systemUTC(), (authority, envelope) -> true);
    gateway = new CountingGateway();
    service =
        new AccountBuilderOrchestrationService(
            orchestrationValidator,
            canonicalJson,
            store,
            gateway,
            new AccountBuilderOrchestrationProperties());
    fixtures =
        mapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
    observationNode = (ObjectNode) fixtures.path("observation").deepCopy();
  }

  private ProviderDecisionOrchestrationSource source(PersistedDecision persisted) {
    return new ProviderDecisionOrchestrationSource(
        (sessionId, turnId) -> persisted == null ? Optional.empty() : Optional.of(persisted),
        canonicalJson,
        requestFactory);
  }

  private CapabilitySnapshot preflight() {
    Instant now = Instant.now();
    return new CapabilitySnapshot(
        null, PREFLIGHT_CAPABILITY_DIGEST, null, -1, -1, now, now, false, List.of());
  }

  @Test
  void aPersistedDecidedTurnReachesDurableOrchestrationWithOneClaimAndNoGatewayAccess() {
    ProviderOrchestrationCommand command = command();
    OrchestrationRequest request =
        source(persisted(TurnState.DECIDED)).bindProduction(command, preflight());

    // The final fingerprint validates after provenance binding.
    assertThat(request.requestFingerprint())
        .isEqualTo(orchestrationValidator.immutableFingerprint(request));
    assertThat(orchestrationValidator.validateAndFingerprint(request))
        .isEqualTo(request.requestFingerprint());
    assertThat(request.sourceType()).isEqualTo("PROVIDER:CODEX");

    var submission = service.orchestrateProvider(request);
    // Delivery is disabled by default: the durable claim is the farthest the request may go.
    assertThat(submission.created()).isTrue();
    assertThat(submission.view().state()).isEqualTo(State.DENIED);
    assertThat(submission.view().failureCode()).isEqualTo(FailureCode.RUNTIME_DISABLED);

    // Exactly one durable claim exists and a replay does not create a second one.
    assertThat(store.find(request.orchestrationId())).isPresent();
    var replay = service.orchestrateProvider(request);
    assertThat(replay.created()).isFalse();
    assertThat(store.readAggregate(request.orchestrationId()).bindings().decisionDigest())
        .isEqualTo(canonicalJson.digest(decision()));
    assertThat(gateway.touches()).isZero();

    // The generic entry point can never accept the same provider request directly.
    assertThatThrownBy(() -> service.orchestrate(request))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
  }

  @Test
  void missingSessionOrTurnFailsClosedBeforeClaim() {
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.DECIDED))
                    .bindProduction(command("", TURN_ID), preflight()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.DECIDED))
                    .bindProduction(command(SESSION_ID, " "), preflight()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThatThrownBy(
            () -> source(null).bindProduction(command("unknown", "unknown"), preflight()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void aNonterminalTurnFailsClosedBeforeClaim() {
    assertThatThrownBy(
            () -> source(persisted(TurnState.STREAMING)).bindProduction(command(), preflight()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void aDeletedOrTamperedTranscriptFailsBeforeClaim() {
    PersistedDecision tampered =
        new PersistedDecision(
            SESSION_ID,
            TURN_ID,
            envelope().requestId(),
            Provider.CODEX,
            envelope().modelVersion(),
            canonicalJson.digest(decision()),
            canonicalJson.digest(action()),
            canonicalJson.digest(observationNode),
            PROMPT_DIGEST,
            envelope().schemaDigest(),
            envelope().knowledgeDigest(),
            envelope().goalPlanDigest(),
            envelope().taskSkillDigests(),
            "sha256:" + "8".repeat(64),
            TRANSCRIPT,
            TurnState.DECIDED,
            com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode.NONE,
            decision());
    assertThatThrownBy(() -> source(tampered).bindProduction(command(), preflight()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void providerModelOrRequestMismatchFailsBeforeClaim() {
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.DECIDED))
                    .bindProduction(commandWithModel("other-model"), preflight()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void observationDriftFailsBeforeClaim() {
    ProviderOrchestrationCommand command = command();
    ObjectNode drifted = command.observation().deepCopy();
    drifted.put("revision", 3);
    ProviderOrchestrationCommand driftedCommand =
        new ProviderOrchestrationCommand(
            command.providerSessionId(),
            command.providerTurnId(),
            command.orchestrationId(),
            command.requestId(),
            command.idempotencyKey(),
            command.mode(),
            command.envelope(),
            command.authority(),
            drifted,
            command.deadline());
    assertThatThrownBy(
            () -> source(persisted(TurnState.DECIDED)).bindProduction(driftedCommand, preflight()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void knowledgeOrSkillDriftFailsBeforeClaim() {
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.DECIDED))
                    .bindProduction(
                        commandWithEnvelope(
                            "sha256:" + "9".repeat(64), envelope().taskSkillDigests()),
                        preflight()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
    assertThatThrownBy(
            () ->
                source(persisted(TurnState.DECIDED))
                    .bindProduction(
                        commandWithEnvelope(
                            envelope().knowledgeDigest(), List.of("sha256:" + "9".repeat(64))),
                        preflight()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void aCallerSuppliedProviderSourceTypeCanNeverMasqueradeThroughBinding() {
    // The binding route rejects a draft whose source type does not match the persisted provider,
    // so a caller naming PROVIDER:CLAUDE can never bind a CODEX turn.
    OrchestrationRequest draft =
        new OrchestrationRequest(
            ORCHESTRATION_ID,
            envelope().requestId(),
            envelope().idempotencyKey(),
            "sha256:" + "f".repeat(64),
            Mode.LIVE_TASK,
            "PROVIDER:CLAUDE",
            decision(),
            envelope(),
            null,
            authority(envelope()),
            observationNode,
            envelope().deadline());
    assertThatThrownBy(() -> source(persisted(TurnState.DECIDED)).bind(SESSION_ID, TURN_ID, draft))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
  }

  private PersistedDecision persisted(TurnState state) {
    ExecutionEnvelope envelope = envelope();
    return new PersistedDecision(
        SESSION_ID,
        TURN_ID,
        envelope.requestId(),
        Provider.CODEX,
        envelope.modelVersion(),
        canonicalJson.digest(decision()),
        canonicalJson.digest(action()),
        canonicalJson.digest(observationNode),
        PROMPT_DIGEST,
        envelope.schemaDigest(),
        envelope.knowledgeDigest(),
        envelope.goalPlanDigest(),
        envelope.taskSkillDigests(),
        TRANSCRIPT,
        TRANSCRIPT,
        state,
        com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode.NONE,
        decision());
  }

  private ProviderDecision decision() {
    return new ProviderDecision(
        "sc-1429/v1",
        Provider.CODEX,
        "codex-fixture-v1",
        "decision-sc1429-001",
        0.9,
        action(),
        List.of());
  }

  private JsonNode action() {
    return fixtures.path("semanticAction").deepCopy();
  }

  private ProviderOrchestrationCommand command() {
    return command(SESSION_ID, TURN_ID);
  }

  private ProviderOrchestrationCommand command(String sessionId, String turnId) {
    return commandWithEnvelope(sessionId, turnId, envelope());
  }

  private ProviderOrchestrationCommand commandWithModel(String modelVersion) {
    ExecutionEnvelope envelope = envelope();
    ObjectNode node = (ObjectNode) mapper.valueToTree(envelope);
    node.put("modelVersion", modelVersion);
    node.put("semanticActionDigest", canonicalJson.digest(action()));
    return commandWithEnvelope(
        SESSION_ID, TURN_ID, mapper.convertValue(node, ExecutionEnvelope.class));
  }

  private ProviderOrchestrationCommand commandWithEnvelope(
      String knowledgeDigest, List<String> taskSkillDigests) {
    ObjectNode node = (ObjectNode) mapper.valueToTree(envelope());
    node.put("knowledgeDigest", knowledgeDigest);
    ArrayNode skills = mapper.createArrayNode();
    taskSkillDigests.forEach(skills::add);
    node.set("taskSkillDigests", skills);
    return commandWithEnvelope(
        SESSION_ID, TURN_ID, mapper.convertValue(node, ExecutionEnvelope.class));
  }

  private ProviderOrchestrationCommand commandWithEnvelope(
      String sessionId, String turnId, ExecutionEnvelope envelope) {
    return new ProviderOrchestrationCommand(
        sessionId,
        turnId,
        ORCHESTRATION_ID,
        envelope.requestId(),
        envelope.idempotencyKey(),
        Mode.LIVE_TASK,
        envelope,
        authority(envelope),
        observationNode,
        envelope.deadline());
  }

  private ExecutionEnvelope envelope() {
    ObjectNode node = (ObjectNode) fixtures.path("executionEnvelope").deepCopy();
    node.put("provider", "CODEX");
    node.put("modelVersion", "codex-fixture-v1");
    node.put("semanticActionDigest", canonicalJson.digest(action()));
    node.put("deadline", Instant.now().plusSeconds(600).toString());
    return mapper.convertValue(node, ExecutionEnvelope.class);
  }

  private HumanAuthority authority(ExecutionEnvelope envelope) {
    Instant now = Instant.now();
    return new HumanAuthority(
        "sc-1429/v1",
        envelope.authorityId(),
        "HUMAN",
        "test-operator",
        now.minusSeconds(60),
        now.plusSeconds(600),
        canonicalJson.digest(envelope),
        "sha256:" + "9".repeat(64),
        true,
        "hmac-sha256:" + "0".repeat(64));
  }

  /** Counts every gateway touch; invalid provenance must fail before any of them. */
  private static final class CountingGateway implements AccountBuilderOrchestrationGateway {
    private final AtomicInteger touches = new AtomicInteger();

    int touches() {
      return touches.get();
    }

    @Override
    public CapabilitySnapshot capabilities(OrchestrationRequest request) {
      touches.incrementAndGet();
      return new CapabilitySnapshot(
          null, null, null, -1, -1, Instant.now(), Instant.now(), false, List.of());
    }

    @Override
    public GatewayReceipt reserve(OrchestrationRequest request, String envelopeDigest) {
      touches.incrementAndGet();
      throw new AssertionError("reserve is never reachable in this test");
    }

    @Override
    public GatewayReceipt dispatch(
        OrchestrationRequest request, String envelopeDigest, Lease lease) {
      touches.incrementAndGet();
      throw new AssertionError("dispatch is never reachable in this test");
    }

    @Override
    public GatewayReceipt status(GatewayCorrelation correlation, Lease lease) {
      touches.incrementAndGet();
      throw new AssertionError("status is never reachable in this test");
    }

    @Override
    public GatewayReceipt stop(
        GatewayCorrelation correlation, Lease lease, String reason, boolean killSwitch) {
      touches.incrementAndGet();
      throw new AssertionError("stop is never reachable in this test");
    }

    @Override
    public GatewayReceipt reconcile(GatewayCorrelation correlation, Lease lease) {
      touches.incrementAndGet();
      throw new AssertionError("reconcile is never reachable in this test");
    }

    @Override
    public GatewayReceipt reconcileReservation(GatewayCorrelation correlation) {
      touches.incrementAndGet();
      throw new AssertionError("reconcileReservation is never reachable in this test");
    }
  }
}

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Production-path proof that the provider orchestration route binds a persisted terminal provider
 * decision through {@code ProviderDecisionOrchestrationSource} before any durable claim. The
 * provider decision, its provenance and the source type are never caller input on this route, and
 * the single {@code AccountBuilderOrchestrationService.orchestrateProvider(command)} boundary owns
 * preparation, replay resolution and capability preflight.
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
  private static final String ALTERNATE_SESSION_ID = "session-codex-2";
  private static final String TURN_ID = "turn-codex-1";
  private static final String ALTERNATE_TURN_ID = "turn-codex-2";
  private static final String ORCHESTRATION_ID = "orchestration-sc1429-001";

  private ObjectMapper mapper;
  private CanonicalGameplayJson canonicalJson;
  private AccountBuilderOrchestrationValidator orchestrationValidator;
  private AccountBuilderOrchestrationRequestFactory requestFactory;
  private InMemoryAccountBuilderOrchestrationStore store;
  private CountingGateway gateway;
  private ProviderDecisionOrchestrationSource providerSource;
  private AccountBuilderOrchestrationService service;
  private JsonNode fixtures;
  private ObjectNode observationNode;
  private final Map<String, PersistedDecision> decisions = new HashMap<>();

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
    providerSource =
        new ProviderDecisionOrchestrationSource(
            (sessionId, turnId) ->
                Optional.ofNullable(decisions.get(decisionKey(sessionId, turnId))),
            canonicalJson,
            requestFactory,
            orchestrationValidator);
    service =
        new AccountBuilderOrchestrationService(
            orchestrationValidator,
            canonicalJson,
            store,
            gateway,
            new AccountBuilderOrchestrationProperties(),
            providerSource,
            new AccountBuilderCapabilityPreflight(gateway));
    fixtures =
        mapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
    observationNode = (ObjectNode) fixtures.path("observation").deepCopy();
  }

  private static String decisionKey(String sessionId, String turnId) {
    return sessionId + "|" + turnId;
  }

  private CapabilitySnapshot preflight() {
    Instant now = Instant.now();
    return new CapabilitySnapshot(
        null, PREFLIGHT_CAPABILITY_DIGEST, null, -1, -1, now, now, false, List.of());
  }

  private ProviderDecisionOrchestrationSource fixedSource(PersistedDecision persisted) {
    return new ProviderDecisionOrchestrationSource(
        (sessionId, turnId) -> persisted == null ? Optional.empty() : Optional.of(persisted),
        canonicalJson,
        requestFactory,
        orchestrationValidator);
  }

  @Test
  void aPersistedDecidedTurnReachesDurableOrchestrationWithOneClaimAndNoReserve() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    ProviderOrchestrationCommand command = command();

    var submission = service.orchestrateProvider(command);
    // Delivery is disabled by default: the durable claim is the farthest the request may go.
    assertThat(submission.created()).isTrue();
    assertThat(submission.view().state()).isEqualTo(State.DENIED);
    assertThat(submission.view().failureCode()).isEqualTo(FailureCode.RUNTIME_DISABLED);

    assertThat(store.find(ORCHESTRATION_ID)).isPresent();
    assertThat(store.readAggregate(ORCHESTRATION_ID).bindings().decisionDigest())
        .isEqualTo(canonicalJson.digest(decision()));
    // The durable binding carries the provider session and turn identity.
    assertThat(store.readAggregate(ORCHESTRATION_ID).bindings().providerSessionId())
        .isEqualTo(SESSION_ID);
    assertThat(store.readAggregate(ORCHESTRATION_ID).bindings().providerTurnId())
        .isEqualTo(TURN_ID);
    assertThat(store.readAggregate(ORCHESTRATION_ID).bindings().promptDigest())
        .isEqualTo(PROMPT_DIGEST);

    // Exactly one preflight happened for the new claim; the replay makes none.
    int touchesAfterFirst = gateway.touches();
    assertThat(touchesAfterFirst).isEqualTo(1);
    var replay = service.orchestrateProvider(command);
    assertThat(replay.created()).isFalse();
    assertThat(store.readAggregate(ORCHESTRATION_ID).bindings().providerSessionId())
        .isEqualTo(SESSION_ID);
    assertThat(gateway.touches()).isEqualTo(touchesAfterFirst);

    // The generic entry point can never accept an assembled provider request directly.
    OrchestrationRequest assembled = providerSource.bindProduction(command, preflight());
    assertThatThrownBy(() -> service.orchestrate(assembled))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
  }

  @Test
  void missingSessionOrTurnFailsClosedBeforeClaimAndPreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    assertThatThrownBy(() -> service.orchestrateProvider(command("", TURN_ID)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThatThrownBy(() -> service.orchestrateProvider(command(SESSION_ID, " ")))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThatThrownBy(() -> service.orchestrateProvider(command("unknown", "unknown")))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void aNonterminalTurnFailsClosedBeforeClaimAndPreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.STREAMING));
    assertThatThrownBy(() -> service.orchestrateProvider(command()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void aDeletedOrTamperedTranscriptFailsBeforeClaimAndPreflight() {
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
    decisions.put(decisionKey(SESSION_ID, TURN_ID), tampered);
    assertThatThrownBy(() -> service.orchestrateProvider(command()))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void providerModelOrRequestMismatchFailsBeforeClaimAndPreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    assertThatThrownBy(() -> service.orchestrateProvider(commandWithModel("other-model")))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void observationDriftFailsBeforeClaimAndPreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
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
    assertThatThrownBy(() -> service.orchestrateProvider(driftedCommand))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void knowledgeOrSkillDriftFailsBeforeClaimAndPreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    assertThatThrownBy(
            () ->
                service.orchestrateProvider(
                    commandWithEnvelope("sha256:" + "9".repeat(64), envelope().taskSkillDigests())))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
    assertThatThrownBy(
            () ->
                service.orchestrateProvider(
                    commandWithEnvelope(
                        envelope().knowledgeDigest(), List.of("sha256:" + "9".repeat(64)))))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
    assertThat(store.find(ORCHESTRATION_ID)).isEmpty();
    assertThat(gateway.touches()).isZero();
  }

  @Test
  void changingOnlyTheProviderSessionIdConflictsWithoutPreflight() {
    assertChangedProviderIdentityConflicts(ALTERNATE_SESSION_ID, TURN_ID);
  }

  @Test
  void changingOnlyTheProviderTurnIdConflictsWithoutPreflight() {
    assertChangedProviderIdentityConflicts(SESSION_ID, ALTERNATE_TURN_ID);
  }

  private void assertChangedProviderIdentityConflicts(
      String alternateSessionId, String alternateTurnId) {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    decisions.put(
        decisionKey(alternateSessionId, alternateTurnId),
        persisted(alternateSessionId, alternateTurnId, TurnState.DECIDED));
    service.orchestrateProvider(command());
    int touchesAfterFirst = gateway.touches();

    assertThatThrownBy(
            () -> service.orchestrateProvider(command(alternateSessionId, alternateTurnId)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.IDEMPOTENCY_CONFLICT);
    // The conflicting identity is resolved from the durable aggregate, never a new Micro6 read.
    assertThat(gateway.touches()).isEqualTo(touchesAfterFirst);
  }

  @Test
  void mismatchedRequestIdFailsBeforePreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    assertZeroPreflight(() -> service.orchestrateProvider(commandWithRequestId("request-other")));
  }

  @Test
  void mismatchedIdempotencyKeyFailsBeforePreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    assertZeroPreflight(
        () -> service.orchestrateProvider(commandWithIdempotencyKey("idempotency-other")));
  }

  @Test
  void mismatchedDeadlineFailsBeforePreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    assertZeroPreflight(
        () -> service.orchestrateProvider(commandWithDeadline(Instant.now().plusSeconds(3600))));
  }

  @Test
  void expiredDeadlineFailsBeforePreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    Instant expired = Instant.now().minusSeconds(60);
    ExecutionEnvelope expiredEnvelope = withDeadline(envelope(), expired);
    assertZeroPreflight(
        () ->
            service.orchestrateProvider(
                new ProviderOrchestrationCommand(
                    SESSION_ID,
                    TURN_ID,
                    ORCHESTRATION_ID,
                    expiredEnvelope.requestId(),
                    expiredEnvelope.idempotencyKey(),
                    Mode.LIVE_TASK,
                    expiredEnvelope,
                    authority(expiredEnvelope),
                    observationNode,
                    expired)));
  }

  @Test
  void malformedAuthorityFailsBeforePreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    ProviderOrchestrationCommand withNullAuthority =
        new ProviderOrchestrationCommand(
            SESSION_ID,
            TURN_ID,
            ORCHESTRATION_ID,
            envelope().requestId(),
            envelope().idempotencyKey(),
            Mode.LIVE_TASK,
            envelope(),
            null,
            observationNode,
            envelope().deadline());
    assertZeroPreflight(() -> service.orchestrateProvider(withNullAuthority));
  }

  @Test
  void malformedEnvelopeIdentityFailsBeforePreflight() {
    decisions.put(decisionKey(SESSION_ID, TURN_ID), persisted(TurnState.DECIDED));
    ObjectNode node = (ObjectNode) mapper.valueToTree(envelope());
    node.put("verifierId", "");
    ExecutionEnvelope malformed = mapper.convertValue(node, ExecutionEnvelope.class);
    assertZeroPreflight(
        () ->
            service.orchestrateProvider(
                new ProviderOrchestrationCommand(
                    SESSION_ID,
                    TURN_ID,
                    ORCHESTRATION_ID,
                    malformed.requestId(),
                    malformed.idempotencyKey(),
                    Mode.LIVE_TASK,
                    malformed,
                    authority(malformed),
                    observationNode,
                    malformed.deadline())));
  }

  private void assertZeroPreflight(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOf(AccountBuilderOrchestrationException.class);
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
    assertThatThrownBy(
            () -> fixedSource(persisted(TurnState.DECIDED)).bind(SESSION_ID, TURN_ID, draft))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
  }

  private PersistedDecision persisted(TurnState state) {
    return persisted(SESSION_ID, TURN_ID, state);
  }

  private PersistedDecision persisted(String sessionId, String turnId, TurnState state) {
    ExecutionEnvelope envelope = envelope();
    return new PersistedDecision(
        sessionId,
        turnId,
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

  private ProviderOrchestrationCommand commandWithRequestId(String requestId) {
    ExecutionEnvelope envelope = envelope();
    return new ProviderOrchestrationCommand(
        SESSION_ID,
        TURN_ID,
        ORCHESTRATION_ID,
        requestId,
        envelope.idempotencyKey(),
        Mode.LIVE_TASK,
        envelope,
        authority(envelope),
        observationNode,
        envelope.deadline());
  }

  private ProviderOrchestrationCommand commandWithIdempotencyKey(String idempotencyKey) {
    ExecutionEnvelope envelope = envelope();
    return new ProviderOrchestrationCommand(
        SESSION_ID,
        TURN_ID,
        ORCHESTRATION_ID,
        envelope.requestId(),
        idempotencyKey,
        Mode.LIVE_TASK,
        envelope,
        authority(envelope),
        observationNode,
        envelope.deadline());
  }

  private ProviderOrchestrationCommand commandWithDeadline(Instant deadline) {
    ExecutionEnvelope envelope = envelope();
    return new ProviderOrchestrationCommand(
        SESSION_ID,
        TURN_ID,
        ORCHESTRATION_ID,
        envelope.requestId(),
        envelope.idempotencyKey(),
        Mode.LIVE_TASK,
        envelope,
        authority(envelope),
        observationNode,
        deadline);
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

  private ExecutionEnvelope withDeadline(ExecutionEnvelope envelope, Instant deadline) {
    ObjectNode node = (ObjectNode) mapper.valueToTree(envelope);
    node.put("deadline", deadline.toString());
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
      Instant now = Instant.now();
      return new CapabilitySnapshot(
          null, PREFLIGHT_CAPABILITY_DIGEST, null, -1, -1, now, now, false, List.of());
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

package com.chromascape.web.gameplay.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Outcome;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderAuthorityVerifier;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationException;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationGateway;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationProperties;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationRequestFactory;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationService;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationValidator;
import com.chromascape.web.gameplay.orchestration.accountbuilder.InMemoryAccountBuilderOrchestrationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production-path proof that a bootstrap proposal delegated to SC-1431 carries the full immutable
 * request fingerprint. The pre-remediation bootstrap path computed a partial fingerprint, so every
 * production dispatch failed validation before the durable claim. These tests wire the real
 * orchestration service, validator and shared request factory exactly as the Spring configuration
 * does.
 */
class BootstrapGameplaySc1431IntegrationTest {
  private static final Instant NOW = Instant.parse("2026-08-30T12:05:00Z");

  private ObjectMapper objectMapper;
  private CanonicalGameplayJson canonicalJson;
  private GameplayContractValidator validator;
  private AccountBuilderOrchestrationValidator orchestrationValidator;
  private AccountBuilderOrchestrationRequestFactory requestFactory;
  private InMemoryAccountBuilderOrchestrationStore store;
  private RecordingGateway gateway;
  private AccountBuilderOrchestrationService service;
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
    AccountBuilderAuthorityVerifier authorityVerifier = (authority, envelope) -> true;
    orchestrationValidator =
        new AccountBuilderOrchestrationValidator(canonicalJson, authorityVerifier);
    requestFactory =
        new AccountBuilderOrchestrationRequestFactory(orchestrationValidator, canonicalJson);
    store = new InMemoryAccountBuilderOrchestrationStore(Clock.systemUTC(), authorityVerifier);
    gateway = new RecordingGateway();
    service =
        new AccountBuilderOrchestrationService(
            orchestrationValidator,
            canonicalJson,
            store,
            gateway,
            new AccountBuilderOrchestrationProperties());
    fixtures =
        objectMapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
  }

  @Test
  void productionWiredBootstrapRequestValidatesReachesClaimAndReplaysIdempotently() {
    Bundle bundle = bundle("prod1");
    BootstrapGameplayOrchestrator orchestrator = productionOrchestrator();

    var receipt =
        orchestrator.dispatch(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

    // Delivery is disabled by default, so the durable claim is the farthest the request may go;
    // the decisive assertion is that the full fingerprint validated and the claim exists.
    assertThat(receipt.failureCode()).isEqualTo("RUNTIME_DISABLED");
    assertThat(gateway.reserves.get()).isZero();
    assertThat(gateway.dispatchCalls.get()).isZero();
    String orchestrationId = "bootstrap-" + bundle.envelope().requestId();
    assertThat(store.find(orchestrationId)).isPresent();
    assertThat(store.findBudget(orchestrationId)).isPresent();

    OrchestrationRequest request =
        requestFactory.fromBootstrap(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());
    var first = service.orchestrate(request);
    var replay = service.orchestrate(request);
    assertThat(replay.created()).isFalse();
    assertThat(replay.view().orchestrationId()).isEqualTo(first.view().orchestrationId());
    assertThat(replay.view().requestFingerprint()).isEqualTo(request.requestFingerprint());
  }

  @Test
  void bootstrapFactoryAndValidatorComputeTheSameImmutableFingerprint() {
    Bundle bundle = bundle("fingerprint");
    OrchestrationRequest request =
        requestFactory.fromBootstrap(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

    assertThat(request.requestFingerprint())
        .isEqualTo(orchestrationValidator.immutableFingerprint(request));
    assertThat(orchestrationValidator.validateAndFingerprint(request))
        .isEqualTo(request.requestFingerprint());
  }

  @Test
  void partialLegacyFingerprintFailsValidationBeforeAnyClaim() {
    Bundle bundle = bundle("legacy");
    OrchestrationRequest request =
        requestFactory.fromBootstrap(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());
    OrchestrationRequest tampered =
        new OrchestrationRequest(
            request.orchestrationId(),
            request.requestId(),
            request.idempotencyKey(),
            // The pre-remediation bootstrap fingerprint covered only three fields.
            canonicalJson.digest(
                Map.of(
                    "envelopeDigest",
                    canonicalJson.digest(request.envelope()),
                    "authorityId",
                    request.authority().authorityId(),
                    "scopeDigest",
                    request.authority().scopeDigest())),
            request.mode(),
            request.sourceType(),
            request.decision(),
            request.envelope(),
            request.bindings(),
            request.authority(),
            request.observation(),
            request.deadline());

    assertThatThrownBy(() -> orchestrationValidator.validateAndFingerprint(tampered))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting(exception -> ((AccountBuilderOrchestrationException) exception).failureCode())
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThat(store.find(tampered.orchestrationId())).isEmpty();
    assertThat(gateway.reserves.get()).isZero();
  }

  @Test
  void changedImmutableFieldProducesIdempotencyConflictWithoutSecondClaim() {
    Bundle bundle = bundle("conflict");
    OrchestrationRequest request =
        requestFactory.fromBootstrap(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());
    service.orchestrate(request);

    // A still-internally-consistent request whose immutable observation payload changed: every
    // binding comparison passes, so only the durable fingerprint mismatch can reject it.
    JsonNode changedObservation = request.observation().deepCopy();
    ((ObjectNode) changedObservation).put("operatorNote", "tampered-after-claim");
    OrchestrationRequest changed =
        requestFactory.withFingerprint(
            new OrchestrationRequest(
                request.orchestrationId(),
                request.requestId(),
                request.idempotencyKey(),
                null,
                request.mode(),
                request.sourceType(),
                request.decision(),
                request.envelope(),
                request.bindings(),
                request.authority(),
                changedObservation,
                request.deadline()));

    assertThatThrownBy(() -> service.orchestrate(changed))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting(exception -> ((AccountBuilderOrchestrationException) exception).failureCode())
        .isEqualTo(FailureCode.IDEMPOTENCY_CONFLICT);
    assertThat(store.readAggregate(request.orchestrationId()).view().requestFingerprint())
        .isEqualTo(request.requestFingerprint());
  }

  @Test
  void noGatewayCallOccursWhenFingerprintValidationFails() {
    Bundle bundle = bundle("nogateway");
    OrchestrationRequest request =
        requestFactory.fromBootstrap(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());
    OrchestrationRequest tampered =
        new OrchestrationRequest(
            request.orchestrationId(),
            request.requestId(),
            request.idempotencyKey(),
            "sha256:" + "0".repeat(64),
            request.mode(),
            request.sourceType(),
            request.decision(),
            request.envelope(),
            request.bindings(),
            request.authority(),
            request.observation(),
            request.deadline());

    assertThatThrownBy(() -> service.orchestrate(tampered))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting(exception -> ((AccountBuilderOrchestrationException) exception).failureCode())
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThat(store.find(request.orchestrationId())).isEmpty();
    assertThat(gateway.capabilityCalls.get()).isZero();
    assertThat(gateway.reserves.get()).isZero();
    assertThat(gateway.dispatchCalls.get()).isZero();
  }

  private BootstrapGameplayOrchestrator productionOrchestrator() {
    return new BootstrapGameplayOrchestrator(
        validator,
        canonicalJson,
        new AccountBuilderGameplayGateway() {
          @Override
          public DispatchResult dispatch(ExecutionEnvelope envelope, HumanAuthority authority) {
            gateway.dispatchCalls.incrementAndGet();
            return new DispatchResult(Outcome.CONFIRMED, null, true, true, List.of());
          }

          @Override
          public DispatchResult stop(String requestId, String reason, boolean killSwitch) {
            gateway.dispatchCalls.incrementAndGet();
            return new DispatchResult(Outcome.STOPPED, null, true, true, List.of());
          }
        },
        new BootstrapEvidenceStore(objectMapper, canonicalJson, temporaryDirectory),
        service,
        requestFactory,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void terminalStateAndStopDecisionsSurviveAnOrchestratorRestart() {
    Bundle bundle = bundle("restart");
    BootstrapGameplayOrchestrator first = productionOrchestrator();
    first.dispatch(bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());
    String orchestrationId = "bootstrap-" + bundle.envelope().requestId();
    var terminalBefore = store.readAggregate(orchestrationId).view();

    // A brand-new orchestrator instance with no in-memory bootstrap state, exactly like a
    // restarted process: durable state alone decides stop and replay.
    BootstrapGameplayOrchestrator restarted = productionOrchestrator();

    assertThatThrownBy(
            () -> restarted.requestStop(bundle.envelope().requestId(), "operator requested", false))
        .isInstanceOf(BootstrapGameplayOrchestrator.GameplayProposalException.class)
        .extracting(
            throwable ->
                ((BootstrapGameplayOrchestrator.GameplayProposalException) throwable).failureCode())
        .isEqualTo("REQUEST_ALREADY_TERMINAL");

    var replayReceipt =
        restarted.dispatch(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());
    assertThat(replayReceipt.failureCode()).isEqualTo("RUNTIME_DISABLED");
    var terminalAfter = store.readAggregate(orchestrationId).view();
    assertThat(terminalAfter.version()).isEqualTo(terminalBefore.version());
    assertThat(terminalAfter.updatedAt()).isEqualTo(terminalBefore.updatedAt());
  }

  private Bundle bundle(String suffix) {
    JsonNode observation = fixtures.path("observation").deepCopy();
    JsonNode action = fixtures.path("semanticAction").deepCopy();
    JsonNode envelopeNode = fixtures.path("executionEnvelope").deepCopy();
    var observationIdentities = (ObjectNode) observation.path("identities");
    var envelopeIdentities = (ObjectNode) envelopeNode.path("identities");
    for (String field : List.of("runId", "taskId", "sessionId", "recordingId")) {
      String value = envelopeIdentities.path(field).asText() + "-" + suffix;
      observationIdentities.put(field, value);
      envelopeIdentities.put(field, value);
    }
    var envelopeObject = (ObjectNode) envelopeNode;
    envelopeObject.put("requestId", "request-" + suffix);
    envelopeObject.put("idempotencyKey", "idempotency-" + suffix);
    envelopeObject.put("authorityId", "authority-" + suffix);
    envelopeObject.put("semanticActionDigest", canonicalJson.digest(action));
    envelopeObject.put("deadline", Instant.now().plusSeconds(600).toString());
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
    Instant now = Instant.now();
    return new HumanAuthority(
        "sc-1429/v1",
        envelope.authorityId(),
        "HUMAN",
        "test-operator",
        now.minusSeconds(60),
        now.plusSeconds(600),
        canonicalJson.digest(envelope),
        fixtures.at("/expectedDigests/approvedScope").asText(),
        true,
        "hmac-sha256:" + "0".repeat(64));
  }

  private record Bundle(
      JsonNode observation,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      HumanAuthority authority) {}

  /** Records every SC-1431 gateway touch; fingerprint validation must happen before any of it. */
  private static final class RecordingGateway implements AccountBuilderOrchestrationGateway {
    private final AtomicInteger capabilityCalls = new AtomicInteger();
    private final AtomicInteger reserves = new AtomicInteger();
    private final AtomicInteger dispatchCalls = new AtomicInteger();

    @Override
    public CapabilitySnapshot capabilities(OrchestrationRequest request) {
      capabilityCalls.incrementAndGet();
      return new CapabilitySnapshot(
          "unavailable",
          "unavailable",
          "unavailable",
          -1,
          -1,
          Instant.now(),
          Instant.now(),
          false,
          List.of());
    }

    @Override
    public GatewayReceipt reserve(OrchestrationRequest request, String envelopeDigest) {
      reserves.incrementAndGet();
      return new GatewayReceipt(
          request.requestId(),
          envelopeDigest,
          "RESERVATION",
          State.DENIED,
          FailureCode.GATEWAY_UNAVAILABLE,
          false,
          true,
          null,
          List.of(),
          Instant.now());
    }

    @Override
    public GatewayReceipt dispatch(
        OrchestrationRequest request, String envelopeDigest, Lease lease) {
      dispatchCalls.incrementAndGet();
      return new GatewayReceipt(
          request.requestId(),
          envelopeDigest,
          "DISPATCH",
          State.DENIED,
          FailureCode.GATEWAY_UNAVAILABLE,
          false,
          true,
          null,
          List.of(),
          Instant.now());
    }

    @Override
    public GatewayReceipt status(GatewayCorrelation correlation, Lease lease) {
      throw new AssertionError("status is never reachable in this test");
    }

    @Override
    public GatewayReceipt stop(
        GatewayCorrelation correlation, Lease lease, String reason, boolean killSwitch) {
      throw new AssertionError("stop is never reachable in this test");
    }

    @Override
    public GatewayReceipt reconcile(GatewayCorrelation correlation, Lease lease) {
      throw new AssertionError("reconcile is never reachable in this test");
    }

    @Override
    public GatewayReceipt reconcileReservation(GatewayCorrelation correlation) {
      throw new AssertionError("reconcileReservation is never reachable in this test");
    }
  }
}

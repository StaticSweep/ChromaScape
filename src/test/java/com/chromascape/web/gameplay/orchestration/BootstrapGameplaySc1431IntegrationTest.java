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
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderCapabilityPreflight;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationException;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationGateway;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.TaskCapability;
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
            bundle.observation(),
            bundle.decision(),
            bundle.envelope(),
            bundle.authority(),
            closedGatewaySnapshot());
    var first = service.orchestrate(request);
    var replay = service.orchestrate(request);
    assertThat(replay.created()).isFalse();
    assertThat(replay.view().orchestrationId()).isEqualTo(first.view().orchestrationId());
    assertThat(replay.view().requestFingerprint()).isEqualTo(request.requestFingerprint());
  }

  @Test
  void enabledDeliveryBindsTheDistinctServerCapabilityDigestAndReachesReserve() {
    Bundle bundle = bundle("enabled");
    RecordingGateway liveGateway = new LiveCapabilityGateway();
    AccountBuilderOrchestrationService enabledService =
        new AccountBuilderOrchestrationService(
            orchestrationValidator, canonicalJson, store, liveGateway, enabledProperties());
    BootstrapGameplayOrchestrator orchestrator =
        productionOrchestrator(enabledService, liveGateway);

    var receipt =
        orchestrator.dispatch(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

    assertThat(receipt.failureCode()).isEqualTo("GATEWAY_UNAVAILABLE");
    String orchestrationId = "bootstrap-" + bundle.envelope().requestId();
    // The persisted request binds the server-issued capability digest, which is distinct from the
    // catalog digest in the same proposal snapshot.
    assertThat(store.find(orchestrationId)).isPresent();
    assertThat(store.readAggregate(orchestrationId).bindings().capabilityDigest())
        .isEqualTo(SERVER_CAPABILITY_DIGEST);
    assertThat(store.readAggregate(orchestrationId).bindings().capabilityDigest())
        .isNotEqualTo(store.readAggregate(orchestrationId).bindings().catalogDigest());
    assertThat(store.readAggregate(orchestrationId).bindings().catalogDigest())
        .isEqualTo(bundle.envelope().catalogDigest());
    // Preflight plus the immediate-before-reserve revalidation: exactly two read-only probes.
    assertThat(liveGateway.capabilityCalls.get()).isEqualTo(2);
    assertThat(liveGateway.reserves.get()).isEqualTo(1);
  }

  @Test
  void replayingAnExistingClaimDoesNotFetchANewSnapshotOrRedispatch() {
    Bundle bundle = bundle("replay");
    RecordingGateway liveGateway = new LiveCapabilityGateway();
    AccountBuilderOrchestrationService enabledService =
        new AccountBuilderOrchestrationService(
            orchestrationValidator, canonicalJson, store, liveGateway, enabledProperties());
    BootstrapGameplayOrchestrator orchestrator =
        productionOrchestrator(enabledService, liveGateway);
    orchestrator.dispatch(
        bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());
    int capabilitiesBefore = liveGateway.capabilityCalls.get();
    int reservesBefore = liveGateway.reserves.get();

    orchestrator.dispatch(
        bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

    assertThat(liveGateway.capabilityCalls.get()).isEqualTo(capabilitiesBefore);
    assertThat(liveGateway.reserves.get()).isEqualTo(reservesBefore);
  }

  @Test
  void bootstrapReplayWithAnyChangedImmutableInputConflicts() {
    Bundle bundle = bundle("fullidentity");
    BootstrapGameplayOrchestrator orchestrator = productionOrchestrator();
    orchestrator.dispatch(
        bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

    // A changed observation payload that still satisfies the proposal binding checks.
    JsonNode changedObservation = bundle.observation().deepCopy();
    ((ObjectNode) changedObservation)
        .put("capturedTick", changedObservation.path("capturedTick").asInt() + 1);
    assertThat(
            orchestrator
                .dispatch(
                    changedObservation, bundle.decision(), bundle.envelope(), bundle.authority())
                .failureCode())
        .isEqualTo("IDEMPOTENCY_CONFLICT");

    // A changed provider decision.
    ProviderDecision changedDecision =
        new ProviderDecision(
            bundle.decision().contractVersion(),
            bundle.decision().provider(),
            bundle.decision().modelVersion(),
            bundle.decision().decisionId(),
            0.5,
            bundle.decision().semanticAction(),
            bundle.decision().evidenceRefs());
    assertThat(
            orchestrator
                .dispatch(
                    bundle.observation(), changedDecision, bundle.envelope(), bundle.authority())
                .failureCode())
        .isEqualTo("IDEMPOTENCY_CONFLICT");

    // A changed authority.
    HumanAuthority originalAuthority = bundle.authority();
    HumanAuthority changedAuthority =
        new HumanAuthority(
            originalAuthority.contractVersion(),
            originalAuthority.authorityId(),
            originalAuthority.actorType(),
            "other-operator",
            originalAuthority.approvedAt(),
            originalAuthority.expiresAt(),
            originalAuthority.envelopeDigest(),
            originalAuthority.scopeDigest(),
            originalAuthority.oneUse(),
            originalAuthority.signature());
    assertThat(
            orchestrator
                .dispatch(
                    bundle.observation(), bundle.decision(), bundle.envelope(), changedAuthority)
                .failureCode())
        .isEqualTo("IDEMPOTENCY_CONFLICT");

    // A changed envelope: budgets and then deadline, each with a matching authority binding.
    Bundle budgetsBundle =
        mutateEnvelope(bundle, node -> ((ObjectNode) node.get("budgets")).put("maxActions", 9));
    assertThat(
            orchestrator
                .dispatch(
                    budgetsBundle.observation(),
                    budgetsBundle.decision(),
                    budgetsBundle.envelope(),
                    budgetsBundle.authority())
                .failureCode())
        .isEqualTo("IDEMPOTENCY_CONFLICT");

    Bundle deadlineBundle =
        mutateEnvelope(
            bundle, node -> node.put("deadline", Instant.now().plusSeconds(900).toString()));
    assertThat(
            orchestrator
                .dispatch(
                    deadlineBundle.observation(),
                    deadlineBundle.decision(),
                    deadlineBundle.envelope(),
                    deadlineBundle.authority())
                .failureCode())
        .isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void exactBootstrapReplaySurvivesAGatewayOutage() {
    Bundle bundle = bundle("replay-outage");
    BootstrapGameplayOrchestrator orchestrator = productionOrchestrator();
    orchestrator.dispatch(
        bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());
    int capabilitiesBefore = gateway.capabilityCalls.get();

    // The gateway is now unreachable, but an exact replay must resolve from the durable aggregate
    // without any capability access.
    gateway.failCapabilities = true;
    var replay =
        orchestrator.dispatch(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

    assertThat(replay.failureCode()).isEqualTo("RUNTIME_DISABLED");
    assertThat(gateway.capabilityCalls.get()).isEqualTo(capabilitiesBefore);
  }

  private Bundle mutateEnvelope(Bundle bundle, java.util.function.Consumer<ObjectNode> mutation) {
    ObjectNode envelopeNode = (ObjectNode) objectMapper.valueToTree(bundle.envelope());
    mutation.accept(envelopeNode);
    ExecutionEnvelope envelope = objectMapper.convertValue(envelopeNode, ExecutionEnvelope.class);
    HumanAuthority authority =
        new HumanAuthority(
            bundle.authority().contractVersion(),
            envelope.authorityId(),
            bundle.authority().actorType(),
            bundle.authority().approvedBy(),
            bundle.authority().approvedAt(),
            bundle.authority().expiresAt(),
            canonicalJson.digest(envelope),
            bundle.authority().scopeDigest(),
            bundle.authority().oneUse(),
            bundle.authority().signature());
    return new Bundle(bundle.observation(), bundle.decision(), envelope, authority);
  }

  @Test
  void missingCapabilityDigestFailsClosedBeforeClaim() {
    Bundle bundle = bundle("nocap");
    RecordingGateway absent =
        new RecordingGateway() {
          @Override
          public CapabilitySnapshot capabilities(OrchestrationRequest request) {
            capabilityCalls.incrementAndGet();
            return new CapabilitySnapshot(
                null, null, null, -1, -1, Instant.now(), Instant.now(), false, List.of());
          }
        };
    BootstrapGameplayOrchestrator orchestrator =
        productionOrchestrator(
            new AccountBuilderOrchestrationService(
                orchestrationValidator, canonicalJson, store, absent, enabledProperties()),
            absent);

    var receipt =
        orchestrator.dispatch(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

    assertThat(receipt.failureCode()).isEqualTo("GATEWAY_UNAVAILABLE");
    assertThat(store.find("bootstrap-" + bundle.envelope().requestId())).isEmpty();
    assertThat(absent.reserves.get()).isZero();
  }

  @Test
  void capabilityCatalogMismatchFailsBeforeClaimAndReserve() {
    Bundle bundle = bundle("catalogdrift");
    RecordingGateway drifted =
        new LiveCapabilityGateway() {
          @Override
          public CapabilitySnapshot capabilities(OrchestrationRequest request) {
            capabilityCalls.incrementAndGet();
            CapabilitySnapshot ready = super.capabilities(request);
            return new CapabilitySnapshot(
                "sha256:" + "4".repeat(64),
                ready.capabilityDigest(),
                ready.buildId(),
                ready.observationRevision(),
                ready.taskRevision(),
                ready.observedAt(),
                ready.expiresAt(),
                true,
                ready.tasks());
          }
        };
    BootstrapGameplayOrchestrator orchestrator =
        productionOrchestrator(
            new AccountBuilderOrchestrationService(
                orchestrationValidator, canonicalJson, store, drifted, enabledProperties()),
            drifted);

    var receipt =
        orchestrator.dispatch(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

    assertThat(receipt.failureCode()).isEqualTo("STALE_BINDING");
    assertThat(store.find("bootstrap-" + bundle.envelope().requestId())).isEmpty();
    assertThat(drifted.reserves.get()).isZero();
  }

  @Test
  void revisionDriftFailsBeforeClaimAndReserve() {
    Bundle bundle = bundle("revisiondrift");
    RecordingGateway drifted =
        new LiveCapabilityGateway() {
          @Override
          public CapabilitySnapshot capabilities(OrchestrationRequest request) {
            capabilityCalls.incrementAndGet();
            CapabilitySnapshot ready = super.capabilities(request);
            return new CapabilitySnapshot(
                ready.catalogDigest(),
                ready.capabilityDigest(),
                ready.buildId(),
                ready.observationRevision() + 1,
                ready.taskRevision(),
                ready.observedAt(),
                ready.expiresAt(),
                true,
                ready.tasks());
          }
        };
    BootstrapGameplayOrchestrator orchestrator =
        productionOrchestrator(
            new AccountBuilderOrchestrationService(
                orchestrationValidator, canonicalJson, store, drifted, enabledProperties()),
            drifted);

    var receipt =
        orchestrator.dispatch(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

    assertThat(receipt.failureCode()).isEqualTo("REVISION_MISMATCH");
    assertThat(store.find("bootstrap-" + bundle.envelope().requestId())).isEmpty();
    assertThat(drifted.reserves.get()).isZero();
  }

  @Test
  void observationDriftBetweenPreflightAndReserveFailsBeforeReserve() {
    Bundle bundle = bundle("clientdrift");
    RecordingGateway drifted =
        new LiveCapabilityGateway() {
          private boolean firstProbe = true;

          @Override
          public CapabilitySnapshot capabilities(OrchestrationRequest request) {
            capabilityCalls.incrementAndGet();
            CapabilitySnapshot ready = super.capabilities(request);
            if (firstProbe) {
              firstProbe = false;
              return ready;
            }
            // The second probe (immediately before reserve) now reports different capability
            // evidence: the request's preflight binding no longer matches the live snapshot.
            return new CapabilitySnapshot(
                ready.catalogDigest(),
                "sha256:" + "8".repeat(64),
                ready.buildId(),
                ready.observationRevision(),
                ready.taskRevision(),
                ready.observedAt(),
                ready.expiresAt(),
                true,
                ready.tasks());
          }
        };
    BootstrapGameplayOrchestrator orchestrator =
        productionOrchestrator(
            new AccountBuilderOrchestrationService(
                orchestrationValidator, canonicalJson, store, drifted, enabledProperties()),
            drifted);

    var receipt =
        orchestrator.dispatch(
            bundle.observation(), bundle.decision(), bundle.envelope(), bundle.authority());

    assertThat(receipt.failureCode()).isEqualTo("STALE_BINDING");
    assertThat(drifted.reserves.get()).isZero();
  }

  private AccountBuilderOrchestrationProperties enabledProperties() {
    AccountBuilderOrchestrationProperties properties = new AccountBuilderOrchestrationProperties();
    properties.setEnabled(true);
    properties.setLiveDeliveryEnabled(true);
    return properties;
  }

  @Test
  void bootstrapFactoryAndValidatorComputeTheSameImmutableFingerprint() {
    Bundle bundle = bundle("fingerprint");
    OrchestrationRequest request =
        requestFactory.fromBootstrap(
            bundle.observation(),
            bundle.decision(),
            bundle.envelope(),
            bundle.authority(),
            closedGatewaySnapshot());

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
            bundle.observation(),
            bundle.decision(),
            bundle.envelope(),
            bundle.authority(),
            closedGatewaySnapshot());
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
            bundle.observation(),
            bundle.decision(),
            bundle.envelope(),
            bundle.authority(),
            closedGatewaySnapshot());
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
            bundle.observation(),
            bundle.decision(),
            bundle.envelope(),
            bundle.authority(),
            closedGatewaySnapshot());
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

  private BootstrapGameplayOrchestrator productionOrchestrator(
      AccountBuilderOrchestrationService orchestrationService,
      AccountBuilderOrchestrationGateway gateway) {
    return new BootstrapGameplayOrchestrator(
        validator,
        canonicalJson,
        legacyGateway(),
        new BootstrapEvidenceStore(objectMapper, canonicalJson, temporaryDirectory),
        orchestrationService,
        requestFactory,
        new AccountBuilderCapabilityPreflight(gateway),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private BootstrapGameplayOrchestrator productionOrchestrator() {
    return productionOrchestrator(service, gateway);
  }

  private AccountBuilderGameplayGateway legacyGateway() {
    return new AccountBuilderGameplayGateway() {
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
    };
  }

  /** The digest-shaped snapshot the closed gateway reports when delivery is disabled. */
  private static CapabilitySnapshot closedGatewaySnapshot() {
    Instant now = Instant.now();
    return new CapabilitySnapshot(
        null, "sha256:" + "0".repeat(64), null, -1, -1, now, now, false, List.of());
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
  static class RecordingGateway implements AccountBuilderOrchestrationGateway {
    final AtomicInteger capabilityCalls = new AtomicInteger();
    final AtomicInteger reserves = new AtomicInteger();
    final AtomicInteger dispatchCalls = new AtomicInteger();
    volatile boolean failCapabilities;

    @Override
    public CapabilitySnapshot capabilities(OrchestrationRequest request) {
      capabilityCalls.incrementAndGet();
      if (failCapabilities) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.GATEWAY_UNAVAILABLE, "simulated capability outage");
      }
      return closedGatewaySnapshot();
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

  /** The distinct server-issued capability digest a live Micro6 attestation reports. */
  static final String SERVER_CAPABILITY_DIGEST = "sha256:" + "9".repeat(64);

  /**
   * A live, ready Micro6-shaped gateway: the capability snapshot carries a distinct server-issued
   * digest agreeing with the proposal catalog, but the reservation itself stays unavailable.
   */
  private static class LiveCapabilityGateway extends RecordingGateway {
    @Override
    public CapabilitySnapshot capabilities(OrchestrationRequest request) {
      capabilityCalls.incrementAndGet();
      Instant now = Instant.now();
      return new CapabilitySnapshot(
          request.envelope().catalogDigest(),
          SERVER_CAPABILITY_DIGEST,
          request.envelope().buildId(),
          request.envelope().expectedObservationRevision(),
          request.envelope().expectedTaskRevision(),
          now,
          now.plusSeconds(30),
          true,
          List.of(
              new TaskCapability(
                  request.envelope().taskKey(),
                  true,
                  request.envelope().schemaDigest(),
                  request.envelope().buildId(),
                  List.of())));
    }
  }
}

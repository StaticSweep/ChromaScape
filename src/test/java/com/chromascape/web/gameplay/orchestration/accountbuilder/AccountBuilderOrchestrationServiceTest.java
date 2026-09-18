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
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.TaskCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountBuilderOrchestrationServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-30T12:05:00Z");
  private ObjectMapper objectMapper;
  private CanonicalGameplayJson canonicalJson;
  private AccountBuilderOrchestrationValidator validator;
  private AccountBuilderOrchestrationProperties properties;
  private JsonNode fixtures;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    canonicalJson = new CanonicalGameplayJson(objectMapper);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    validator = new AccountBuilderOrchestrationValidator(canonicalJson, clock, (a, e) -> true);
    properties = new AccountBuilderOrchestrationProperties();
    properties.setEnabled(true);
    properties.setLiveDeliveryEnabled(true);
    properties.setOperatorKey("x".repeat(32));
    fixtures =
        objectMapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
  }

  @Test
  void heldAndShadowAreIdempotentAndNeverCallGateway() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    AccountBuilderOrchestrationService service = service(gateway);
    OrchestrationRequest request = request("held", Mode.HELD);

    var first = service.orchestrate(request);
    var identical = service.orchestrate(request);

    assertThat(first.view().state()).isEqualTo(State.HELD);
    assertThat(identical.created()).isFalse();
    assertThat(identical.view()).isEqualTo(first.view());
    assertThat(gateway.capabilityReads).hasValue(0);
    assertThat(gateway.reservations).hasValue(0);
    assertThat(gateway.dispatches).hasValue(0);
  }

  @Test
  void genericServiceCategoricallyRejectsProviderSourcesBeforeAnyClaimOrGatewayAccess() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest base = request("bypass", Mode.BOOTSTRAP_RECORDING);

    for (String source :
        List.of(
            "PROVIDER:CODEX",
            "provider:CODEX",
            "Provider:CODEX",
            "PROVIDER:codex",
            "PROVIDER:",
            "provider:CLAUDE",
            "PROVIDER:BOGUS",
            " PROVIDER:CODEX",
            "arbitrary")) {
      OrchestrationRequest forged = withSource(base, source);
      assertThatThrownBy(() -> service.orchestrate(forged))
          .isInstanceOf(AccountBuilderOrchestrationException.class)
          .extracting("failureCode")
          .isEqualTo(FailureCode.INVALID_CONTRACT);
    }

    // No forged source reached the durable claim, budget, authority or gateway boundary.
    assertThat(store.find("orchestration-bypass")).isEmpty();
    assertThat(gateway.capabilityReads).hasValue(0);
    assertThat(gateway.reservations).hasValue(0);
    assertThat(gateway.dispatches).hasValue(0);
  }

  @Test
  void providerEntryPointRejectsNonProviderSources() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest bootstrap = request("not-provider", Mode.BOOTSTRAP_RECORDING);

    assertThatThrownBy(() -> service.orchestrateProvider(bootstrap))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThat(store.find("orchestration-not-provider")).isEmpty();
    assertThat(gateway.capabilityReads).hasValue(0);
  }

  @Test
  void exactDeliveryConsumesAuthorityAndDispatchesOnce() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    AccountBuilderOrchestrationService service = service(gateway);
    OrchestrationRequest request = request("confirmed", Mode.BOOTSTRAP_RECORDING);

    var first = service.orchestrate(request);
    var identical = service.orchestrate(request);

    assertThat(first.view().state()).isEqualTo(State.CONFIRMED);
    assertThat(first.view().authorityConsumed()).isTrue();
    assertThat(first.view().receipts()).hasSize(2);
    assertThat(identical.created()).isFalse();
    assertThat(gateway.reservations).hasValue(1);
    assertThat(gateway.dispatches).hasValue(1);
  }

  @Test
  void authorityOwnershipIsCommittedBeforeReserveAndPreventsRedispatchAfterFailure() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    gateway.failReservation = true;
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest request = request("authority-before-reserve", Mode.BOOTSTRAP_RECORDING);

    var first = service.orchestrate(request);

    assertThat(first.view().state()).isEqualTo(State.NEEDS_RECONCILIATION);
    assertThat(first.view().authorityConsumed()).isTrue();
    assertThat(store.readAggregate(request.orchestrationId()).authority()).isNotNull();
    assertThat(gateway.reservations).hasValue(1);

    gateway.failReservation = false;
    var replay = service.orchestrate(request);

    assertThat(replay.created()).isFalse();
    assertThat(replay.view()).isEqualTo(first.view());
    assertThat(gateway.reservations).hasValue(1);
    assertThat(gateway.dispatches).hasValue(0);
  }

  @Test
  void connectionRefusalBeforeSubmissionBecomesTerminalGatewayUnavailableFailure() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    gateway.refuseConnection = true;
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest request = request("refusal", Mode.BOOTSTRAP_RECORDING);

    var first = service.orchestrate(request);

    // The connection never opened, so nothing could have been submitted: this is a proven
    // pre-submission failure, not a reconciliation case.
    assertThat(first.view().state()).isEqualTo(State.FAILED);
    assertThat(first.view().failureCode()).isEqualTo(FailureCode.GATEWAY_UNAVAILABLE);
    assertThat(first.view().retryAllowed()).isFalse();
    assertThat(first.view().authorityConsumed()).isTrue();
    assertThat(gateway.reservations).hasValue(1);
    assertThat(gateway.dispatches).hasValue(0);

    // Authority stays consumed and a replay returns the same terminal result without redispatch.
    var replay = service.orchestrate(request);
    assertThat(replay.created()).isFalse();
    assertThat(replay.view()).isEqualTo(first.view());
    assertThat(gateway.reservations).hasValue(1);
    assertThat(gateway.dispatches).hasValue(0);
  }

  @Test
  void timeoutAfterSubmissionRemainsNeedsReconciliationDeliveryUncertain() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    gateway.timeOutAfterSubmission = true;
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest request = request("timeout", Mode.BOOTSTRAP_RECORDING);

    var uncertain = service.orchestrate(request);

    assertThat(uncertain.view().state()).isEqualTo(State.NEEDS_RECONCILIATION);
    assertThat(uncertain.view().failureCode()).isEqualTo(FailureCode.DELIVERY_UNCERTAIN);
    assertThat(uncertain.view().authorityConsumed()).isTrue();
    assertThat(gateway.reservations).hasValue(1);
    assertThat(gateway.dispatches).hasValue(0);

    // Reconciliation of an authority-consumed orchestration without a lease is lookup only.
    gateway.statusState = State.DENIED;
    var reconciled = service.reconcile(request.orchestrationId());
    assertThat(reconciled.state()).isEqualTo(State.DENIED);
    assertThat(gateway.reservations).hasValue(1);
    assertThat(gateway.dispatches).hasValue(0);
    assertThat(gateway.reservationReconciliations).hasValue(1);
  }

  @Test
  void noLeaseReservationUncertaintyUsesLookupOnlyReconciliation() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    gateway.failReservation = true;
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest request = request("no-lease-reconciliation", Mode.BOOTSTRAP_RECORDING);

    var uncertain = service.orchestrate(request);
    gateway.statusState = State.DENIED;
    var reconciled = service.reconcile(request.orchestrationId());

    assertThat(uncertain.view().state()).isEqualTo(State.NEEDS_RECONCILIATION);
    assertThat(reconciled.state()).isEqualTo(State.DENIED);
    assertThat(gateway.reservations).hasValue(1);
    assertThat(gateway.dispatches).hasValue(0);
    assertThat(gateway.reservationReconciliations).hasValue(1);
  }

  @Test
  void duplicateReceiptIsIdempotentAndLeaseTupleCannotBeReplaced() {
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    gateway.statusState = State.ACTIVE;
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest request = request("receipt-replay", Mode.BOOTSTRAP_RECORDING);
    var initial = service.orchestrate(request);
    GatewayReceipt receipt = initial.view().receipts().get(initial.view().receipts().size() - 1);

    var replay =
        store.transition(
            request.orchestrationId(),
            State.NEEDS_RECONCILIATION,
            FailureCode.NEEDS_RECONCILIATION,
            initial.view().lease(),
            receipt,
            false,
            false);

    assertThat(replay).isEqualTo(initial.view());
    Lease changedLease =
        new Lease(
            initial.view().lease().reservationId(),
            initial.view().lease().generation() + 1,
            initial.view().lease().fence(),
            initial.view().lease().fenceTokenDigest(),
            initial.view().lease().expiresAt());
    assertThatThrownBy(
            () ->
                store.transition(
                    request.orchestrationId(),
                    State.NEEDS_RECONCILIATION,
                    FailureCode.NEEDS_RECONCILIATION,
                    changedLease,
                    null,
                    false,
                    false))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.FENCE_MISMATCH);
    assertThat(store.find(request.orchestrationId()).orElseThrow()).isEqualTo(initial.view());
  }

  @Test
  void conflictingIdentityFailsBeforeASecondGatewayCall() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    AccountBuilderOrchestrationService service = service(gateway);
    OrchestrationRequest first = request("conflict", Mode.HELD);
    service.orchestrate(first);
    OrchestrationRequest changed = withMode(first, Mode.SHADOW);

    assertThatThrownBy(() -> service.orchestrate(changed))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.IDEMPOTENCY_CONFLICT);
    assertThat(gateway.reservations).hasValue(0);
  }

  @Test
  void acknowledgedNonTerminalDeliveryFailsClosedAsNeedsReconciliation() {
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    gateway.statusState = State.ACTIVE;
    AccountBuilderOrchestrationService service = service(gateway);

    var result = service.orchestrate(request("uncertain", Mode.BOOTSTRAP_RECORDING));

    assertThat(result.view().state()).isEqualTo(State.NEEDS_RECONCILIATION);
    assertThat(result.view().retryAllowed()).isFalse();
    assertThat(gateway.dispatches).hasValue(1);
    assertThat(gateway.statusReads).hasValue(1);
  }

  @Test
  void reconciliationCanProveTerminalStateWithoutRedispatch() {
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    gateway.statusState = State.ACTIVE;
    AccountBuilderOrchestrationService service = service(gateway);
    var initial = service.orchestrate(request("reconcile", Mode.BOOTSTRAP_RECORDING));

    gateway.statusState = State.CONFIRMED;
    var reconciled = service.reconcile(initial.view().orchestrationId());

    assertThat(reconciled.state()).isEqualTo(State.CONFIRMED);
    assertThat(gateway.dispatches).hasValue(1);
  }

  @Test
  void staleCapabilityBindingIsDeniedBeforeReservation() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    gateway.staleCapabilities = true;
    AccountBuilderOrchestrationService service = service(gateway);

    var result = service.orchestrate(request("stale", Mode.BOOTSTRAP_RECORDING));

    assertThat(result.view().state()).isEqualTo(State.DENIED);
    assertThat(result.view().failureCode()).isEqualTo(FailureCode.STALE_BINDING);
    assertThat(gateway.reservations).hasValue(0);
    assertThat(gateway.dispatches).hasValue(0);
  }

  @Test
  void terminalReceiptCannotBeRewritten() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest request = request("terminal-immutable", Mode.BOOTSTRAP_RECORDING);
    OrchestrationView terminal = service.orchestrate(request).view();
    GatewayReceipt changedEvidence =
        new GatewayReceipt(
            request.requestId(),
            terminal.envelopeDigest(),
            "DISPATCH",
            State.CONFIRMED,
            FailureCode.NONE,
            true,
            true,
            terminal.lease(),
            List.of("different-evidence"),
            NOW);

    assertThatThrownBy(
            () ->
                store.transition(
                    request.orchestrationId(),
                    State.CONFIRMED,
                    FailureCode.NONE,
                    terminal.lease(),
                    changedEvidence,
                    false,
                    false))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.IDEMPOTENCY_CONFLICT);
    assertThat(store.find(request.orchestrationId()).orElseThrow()).isEqualTo(terminal);
  }

  @Test
  void reconciliationNeededCannotReturnToDelivery() {
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    gateway.statusState = State.ACTIVE;
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest request = request("quiescent", Mode.BOOTSTRAP_RECORDING);
    OrchestrationView uncertain = service.orchestrate(request).view();

    assertThatThrownBy(
            () ->
                store.transition(
                    request.orchestrationId(),
                    State.RESERVED,
                    FailureCode.NONE,
                    uncertain.lease(),
                    null,
                    false,
                    false))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.IDEMPOTENCY_CONFLICT);
    assertThat(store.find(request.orchestrationId()).orElseThrow()).isEqualTo(uncertain);
  }

  @Test
  void restartCanQuiesceAClaimBeforeAuthorityWithoutDelivery() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest request = request("restart-before-authority", Mode.BOOTSTRAP_RECORDING);
    store.claim(
        request,
        request.requestFingerprint(),
        canonicalJson.digest(request.envelope()),
        budget(request));

    List<OrchestrationView> recovered = service.recoverAfterRestart();

    assertThat(recovered)
        .singleElement()
        .extracting(OrchestrationView::state)
        .isEqualTo(State.NEEDS_RECONCILIATION);
    assertThat(recovered.get(0).authorityConsumed()).isFalse();
    assertThat(gateway.reservations).hasValue(0);
    assertThat(gateway.dispatches).hasValue(0);
  }

  @Test
  void normalStopSendsFalseAndRecordsTheRequestedMode() {
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    AccountBuilderOrchestrationService service = service(gateway);
    OrchestrationRequest request = request("stop-normal", Mode.BOOTSTRAP_RECORDING);
    service.orchestrate(request);

    OrchestrationView stopped =
        service.stop(request.orchestrationId(), "operator requested", false);

    assertThat(stopped.state()).isEqualTo(State.STOPPED);
    assertThat(gateway.lastStopKillSwitch).isFalse();
    assertThat(stopped.receipts().get(stopped.receipts().size() - 1).killSwitch()).isFalse();
  }

  @Test
  void killSwitchStopSendsTrueAndPersistsStopEvidenceWithoutSecrets() {
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    AccountBuilderOrchestrationService service = service(gateway);
    OrchestrationRequest request = request("stop-kill", Mode.BOOTSTRAP_RECORDING);
    service.orchestrate(request);

    OrchestrationView stopped = service.stop(request.orchestrationId(), "kill switch", true);

    assertThat(stopped.state()).isEqualTo(State.STOPPED);
    assertThat(gateway.lastStopKillSwitch).isTrue();
    GatewayReceipt stopReceipt = stopped.receipts().get(stopped.receipts().size() - 1);
    assertThat(stopReceipt.killSwitch()).isTrue();
    // The stop evidence never carries authority secrets or raw fence tokens.
    assertThat(stopReceipt.evidenceRefs()).doesNotContain(request.authority().signature());
    assertThat(stopReceipt.toString()).doesNotContain(request.authority().signature());
  }

  @Test
  void unauthorizedKillSwitchIsRejectedBeforeGatewayAccess() {
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest outOfScope =
        withKillSwitchScope(request("stop-unauthorized", Mode.BOOTSTRAP_RECORDING), "OBSERVE");
    // A durable record whose approved scope does not cover kill-switch use.
    store.claim(
        outOfScope,
        outOfScope.requestFingerprint(),
        canonicalJson.digest(outOfScope.envelope()),
        budget(outOfScope));

    assertThatThrownBy(() -> service.stop(outOfScope.orchestrationId(), "kill switch", true))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.OPERATOR_UNAUTHORIZED);
    assertThat(gateway.stops).hasValue(0);
  }

  private AccountBuilderOrchestrationService service(FakeGateway gateway) {
    return service(
        gateway,
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true));
  }

  private AccountBuilderOrchestrationService service(
      FakeGateway gateway, InMemoryAccountBuilderOrchestrationStore store) {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    return new AccountBuilderOrchestrationService(
        validator, canonicalJson, store, gateway, properties, clock);
  }

  private OrchestrationRequest request(String suffix, Mode mode) {
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
    SnapshotBindings bindings =
        new SnapshotBindings(
            canonicalJson.digest(decision),
            canonicalJson.digest(action),
            envelope.catalogDigest(),
            "sha256:" + "c".repeat(64),
            envelope.goalPlanDigest(),
            envelope.taskSkillDigests(),
            envelope.schemaDigest(),
            envelope.knowledgeDigest(),
            envelope.policyDigest(),
            envelope.verifierId(),
            envelope.buildId(),
            envelope.expectedObservationRevision(),
            envelope.expectedTaskRevision(),
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
    HumanAuthority authority =
        new HumanAuthority(
            "sc-1429/v1",
            envelope.authorityId(),
            "HUMAN",
            "test-operator",
            NOW.minusSeconds(60),
            NOW.plusSeconds(300),
            canonicalJson.digest(envelope),
            fixtures.at("/expectedDigests/approvedScope").asText(),
            true,
            "hmac-sha256:" + "0".repeat(64));
    OrchestrationRequest withoutFingerprint =
        new OrchestrationRequest(
            "orchestration-" + suffix,
            envelope.requestId(),
            envelope.idempotencyKey(),
            null,
            mode,
            "BOOTSTRAP",
            decision,
            envelope,
            bindings,
            authority,
            observation,
            envelope.deadline());
    return withFingerprint(withoutFingerprint, validator.immutableFingerprint(withoutFingerprint));
  }

  private OrchestrationRequest withKillSwitchScope(OrchestrationRequest request, String scope) {
    var node =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            objectMapper.valueToTree(request.envelope());
    node.put("killSwitchScope", scope);
    ExecutionEnvelope envelope = objectMapper.convertValue(node, ExecutionEnvelope.class);
    OrchestrationRequest changed =
        new OrchestrationRequest(
            request.orchestrationId(),
            request.requestId(),
            request.idempotencyKey(),
            null,
            request.mode(),
            request.sourceType(),
            request.decision(),
            envelope,
            request.bindings(),
            request.authority(),
            request.observation(),
            request.deadline());
    return withFingerprint(changed, validator.immutableFingerprint(changed));
  }

  private OrchestrationRequest withMode(OrchestrationRequest request, Mode mode) {
    OrchestrationRequest changed =
        new OrchestrationRequest(
            request.orchestrationId(),
            request.requestId(),
            request.idempotencyKey(),
            null,
            mode,
            request.sourceType(),
            request.decision(),
            request.envelope(),
            request.bindings(),
            request.authority(),
            request.observation(),
            request.deadline());
    return withFingerprint(changed, validator.immutableFingerprint(changed));
  }

  private OrchestrationRequest withSource(OrchestrationRequest request, String sourceType) {
    OrchestrationRequest changed =
        new OrchestrationRequest(
            request.orchestrationId(),
            request.requestId(),
            request.idempotencyKey(),
            null,
            request.mode(),
            sourceType,
            request.decision(),
            request.envelope(),
            request.bindings(),
            request.authority(),
            request.observation(),
            request.deadline());
    return withFingerprint(changed, validator.immutableFingerprint(changed));
  }

  private OrchestrationRequest withFingerprint(OrchestrationRequest request, String fingerprint) {
    return new OrchestrationRequest(
        request.orchestrationId(),
        request.requestId(),
        request.idempotencyKey(),
        fingerprint,
        request.mode(),
        request.sourceType(),
        request.decision(),
        request.envelope(),
        request.bindings(),
        request.authority(),
        request.observation(),
        request.deadline());
  }

  private final class FakeGateway implements AccountBuilderOrchestrationGateway {
    private final AtomicInteger capabilityReads = new AtomicInteger();
    private final AtomicInteger reservations = new AtomicInteger();
    private final AtomicInteger dispatches = new AtomicInteger();
    private final AtomicInteger statusReads = new AtomicInteger();
    private final AtomicInteger reservationReconciliations = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private final State dispatchState;
    private State statusState;
    private boolean staleCapabilities;
    private boolean failReservation;
    private boolean refuseConnection;
    private boolean timeOutAfterSubmission;
    private boolean lastStopKillSwitch;

    private FakeGateway(State dispatchState) {
      this.dispatchState = dispatchState;
      this.statusState = dispatchState;
    }

    @Override
    public CapabilitySnapshot capabilities(OrchestrationRequest request) {
      capabilityReads.incrementAndGet();
      OrchestrationRequest sample = request("capability", Mode.HELD);
      return new CapabilitySnapshot(
          staleCapabilities ? "sha256:" + "d".repeat(64) : sample.bindings().catalogDigest(),
          sample.bindings().capabilityDigest(),
          sample.bindings().buildId(),
          sample.bindings().expectedObservationRevision(),
          sample.bindings().expectedTaskRevision(),
          NOW,
          NOW.plusSeconds(60),
          true,
          List.of(
              new TaskCapability(
                  sample.envelope().taskKey(),
                  true,
                  sample.envelope().schemaDigest(),
                  sample.envelope().buildId(),
                  List.of())));
    }

    @Override
    public GatewayReceipt reserve(OrchestrationRequest request, String envelopeDigest) {
      reservations.incrementAndGet();
      if (refuseConnection) {
        // The transport proves the connection never opened: no request bytes were submitted.
        throw new AccountBuilderOrchestrationException(
            FailureCode.GATEWAY_UNAVAILABLE, "Micro6 v2 route is unreachable");
      }
      if (timeOutAfterSubmission) {
        // A timeout is only observable after submission was possible: delivery may have happened.
        throw new AccountBuilderOrchestrationException(
            FailureCode.DELIVERY_UNCERTAIN,
            "Micro6 v2 delivery outcome is unknown after submission was possible");
      }
      if (failReservation) {
        throw new IllegalStateException("simulated reserve failure");
      }
      Lease lease =
          new Lease(
              "reservation-" + request.requestId(),
              request.envelope().leaseGeneration(),
              request.envelope().fence(),
              "sha256:" + "f".repeat(64),
              NOW.plusSeconds(120));
      return receipt(request, envelopeDigest, "RESERVATION", State.RESERVED, lease, false, false);
    }

    @Override
    public GatewayReceipt dispatch(
        OrchestrationRequest request, String envelopeDigest, Lease lease) {
      dispatches.incrementAndGet();
      return receipt(
          request,
          envelopeDigest,
          "DISPATCH",
          dispatchState,
          lease,
          true,
          dispatchState.terminal());
    }

    @Override
    public GatewayReceipt status(GatewayCorrelation correlation, Lease lease) {
      statusReads.incrementAndGet();
      return receipt(correlation, "STATUS", statusState, lease, true, statusState.terminal());
    }

    @Override
    public GatewayReceipt stop(
        GatewayCorrelation correlation, Lease lease, String reason, boolean killSwitch) {
      stops.incrementAndGet();
      lastStopKillSwitch = killSwitch;
      return receipt(correlation, "STOP", State.STOPPED, lease, true, true, killSwitch);
    }

    @Override
    public GatewayReceipt reconcile(GatewayCorrelation correlation, Lease lease) {
      return receipt(correlation, "RECONCILE", statusState, lease, true, true);
    }

    @Override
    public GatewayReceipt reconcileReservation(GatewayCorrelation correlation) {
      reservationReconciliations.incrementAndGet();
      return receipt(
          correlation, "RESERVATION_RECONCILE", statusState, null, true, statusState.terminal());
    }

    private GatewayReceipt receipt(
        OrchestrationRequest request,
        String envelopeDigest,
        String phase,
        State state,
        Lease lease,
        boolean acknowledged,
        boolean reconciled) {
      return new GatewayReceipt(
          request.requestId(),
          envelopeDigest,
          phase,
          state,
          FailureCode.NONE,
          acknowledged,
          reconciled,
          lease,
          List.of("fake:" + phase.toLowerCase()),
          NOW);
    }

    private GatewayReceipt receipt(
        GatewayCorrelation correlation,
        String phase,
        State state,
        Lease lease,
        boolean acknowledged,
        boolean reconciled) {
      return receipt(correlation, phase, state, lease, acknowledged, reconciled, false);
    }

    private GatewayReceipt receipt(
        GatewayCorrelation correlation,
        String phase,
        State state,
        Lease lease,
        boolean acknowledged,
        boolean reconciled,
        boolean killSwitch) {
      return new GatewayReceipt(
          correlation.requestId(),
          correlation.envelopeDigest(),
          phase,
          state,
          FailureCode.NONE,
          acknowledged,
          reconciled,
          lease,
          List.of("fake:" + phase.toLowerCase()),
          NOW,
          null,
          killSwitch);
    }
  }

  private static AccountBuilderBudgetPolicy.OrchestrationBudget budget(
      OrchestrationRequest request) {
    return new AccountBuilderBudgetPolicy.OrchestrationBudget(
        5, 2, 60_000, 0, 0, NOW, request.deadline());
  }
}

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
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.ProviderOrchestrationCommand;
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
  /** The envelope digest the frozen v2 fixture binds; the authority must attest the same one. */
  private static final String V2_ENVELOPE_DIGEST = "sha256:" + "5".repeat(64);
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
  void bootstrapProvenanceCannotCarryProviderPromptDigest() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);
    OrchestrationRequest base = request("prompt-smuggle", Mode.BOOTSTRAP_RECORDING);
    SnapshotBindings bindings = base.bindings();
    SnapshotBindings smuggled =
        new SnapshotBindings(
            bindings.decisionDigest(),
            bindings.semanticActionDigest(),
            bindings.catalogDigest(),
            bindings.capabilityDigest(),
            bindings.goalPlanDigest(),
            bindings.taskSkillDigests(),
            bindings.toolSchemaDigest(),
            bindings.knowledgeDigest(),
            bindings.policyDigest(),
            bindings.verifierDigest(),
            bindings.buildId(),
            bindings.expectedObservationRevision(),
            bindings.expectedTaskRevision(),
            bindings.observationDigest(),
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
            bindings.providerSessionId(),
            bindings.providerTurnId());
    OrchestrationRequest unfingerprinted =
        new OrchestrationRequest(
            base.orchestrationId(),
            base.requestId(),
            base.idempotencyKey(),
            null,
            base.mode(),
            base.sourceType(),
            base.decision(),
            base.envelope(),
            smuggled,
            base.authority(),
            base.observation(),
            base.deadline());
    OrchestrationRequest forged =
        withFingerprint(unfingerprinted, validator.immutableFingerprint(unfingerprinted));

    // The documented contract requires the prompt digest to be absent for bootstrap sources;
    // a smuggled provider prompt digest fails before any durable claim or gateway access.
    assertThatThrownBy(() -> service.orchestrate(forged))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
    assertThat(store.find("orchestration-prompt-smuggle")).isEmpty();
    assertThat(gateway.capabilityReads).hasValue(0);
    assertThat(gateway.reservations).hasValue(0);
    assertThat(gateway.dispatches).hasValue(0);
  }

  @Test
  void providerEntryPointAcceptsOnlyProviderOrchestrationCommands() {
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    InMemoryAccountBuilderOrchestrationStore store =
        new InMemoryAccountBuilderOrchestrationStore(
            Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
    AccountBuilderOrchestrationService service = service(gateway, store);

    // The single provider entry point takes a ProviderOrchestrationCommand, never an assembled
    // provider OrchestrationRequest, so no caller can forge a provider-shaped request.
    assertThat(AccountBuilderOrchestrationService.class.getDeclaredMethods())
        .filteredOn(method -> method.getName().equals("orchestrateProvider"))
        .isNotEmpty()
        .allSatisfy(
            method ->
                assertThat(method.getParameterTypes())
                    .containsExactly(ProviderOrchestrationCommand.class));

    ProviderOrchestrationCommand forged =
        new ProviderOrchestrationCommand(
            "session-absent",
            "turn-absent",
            "orchestration-forged",
            "request-forged",
            "idempotency-forged",
            Mode.LIVE_TASK,
            null,
            null,
            null,
            NOW.plusSeconds(300));
    assertThatThrownBy(() -> service.orchestrateProvider(forged))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_CONTRACT);
    assertThat(store.find("orchestration-forged")).isEmpty();
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

  /**
   * The v2 path is exercised against the same fixed clock the service and validator use, so a
   * frozen bundle's deadline and budget horizon are evaluated at the instant it was approved.
   */
  private InMemoryAccountBuilderOrchestrationStore v2Store() {
    return new InMemoryAccountBuilderOrchestrationStore(
        Clock.fixed(NOW, ZoneOffset.UTC), (a, e) -> true);
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
    AccountBuilderOrchestrationRequestFactory requestFactory =
        new AccountBuilderOrchestrationRequestFactory(validator, canonicalJson);
    ProviderDecisionOrchestrationSource providerSource =
        new ProviderDecisionOrchestrationSource(
            (sessionId, turnId) -> java.util.Optional.empty(),
            canonicalJson,
            requestFactory,
            validator);
    return new AccountBuilderOrchestrationService(
        validator,
        canonicalJson,
        store,
        gateway,
        properties,
        clock,
        providerSource,
        new AccountBuilderCapabilityPreflight(gateway));
  }

  /**
   * A frozen v2 bundle is delivered through the same durable owner. The v1 contract and authority
   * record checks are skipped because the assembler already applied the same rules and Micro6
   * re-validates independently, but the claim, the capability probe and the fail-closed denials are
   * identical.
   */
  @Test
  void frozenV2DeliveryClaimsAndFailsClosedWhenTheGatewayCannotAttest() {
    OrchestrationRequest sample = request("capability", Mode.HELD);
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    gateway.staleCapabilities = true;
    InMemoryAccountBuilderOrchestrationStore store = v2Store();
    AccountBuilderOrchestrationService service = service(gateway, store);

    var command =
        new AccountBuilderOrchestrationModels.FrozenV2Command(
            "orch-v2-1",
            "req-v2-1",
            "idem-v2-1",
            Mode.BOOTSTRAP_RECORDING,
            NOW.plusSeconds(300),
            "session-v2-1",
            "turn-v2-1",
            frozenV2Bundle(sample));

    var submission = service.orchestrateFrozenV2(command);

    assertThat(submission.view().state()).isEqualTo(State.DENIED);
    assertThat(submission.view().failureCode()).isNotEqualTo(FailureCode.NONE);
    assertThat(gateway.reservations.get()).isZero();
    assertThat(store.find("orch-v2-1")).isPresent();
    assertThat(store.find("orch-v2-1").orElseThrow().authorityConsumed()).isFalse();
  }

  /**
   * The whole v2 chain through the durable owner: one claim, one capability probe, one reserve and
   * one dispatch, with the approved bundle's envelope digest as the delivery identity throughout.
   */
  @Test
  void frozenV2DeliveryReservesOnceAndDispatchesTheApprovedEnvelope() {
    OrchestrationRequest sample = request("capability", Mode.HELD);
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    InMemoryAccountBuilderOrchestrationStore store = v2Store();
    AccountBuilderOrchestrationService service = service(gateway, store);

    var submission =
        service.orchestrateFrozenV2(
            new AccountBuilderOrchestrationModels.FrozenV2Command(
                "orch-v2-live",
                "req-v2-live",
                "idem-v2-live",
                Mode.BOOTSTRAP_RECORDING,
                NOW.plusSeconds(300),
                "session-v2-1",
                "turn-v2-1",
                frozenV2Bundle(sample)));

    assertThat(submission.created()).isTrue();
    assertThat(submission.view().state()).isEqualTo(State.CONFIRMED);
    assertThat(submission.view().failureCode()).isEqualTo(FailureCode.NONE);
    assertThat(submission.view().envelopeDigest())
        .as("the delivery identity is the approved envelope, never a recomputed v1 digest")
        .isEqualTo(V2_ENVELOPE_DIGEST);
    assertThat(gateway.reservations.get()).isEqualTo(1);
    assertThat(gateway.dispatches.get()).isEqualTo(1);
    assertThat(store.find("orch-v2-live").orElseThrow().authorityConsumed()).isTrue();
    assertThat(store.find("orch-v2-live").orElseThrow().lease().reservationId())
        .as("the durable lease is the one the executor attested, never a local invention")
        .isEqualTo("reservation-req-v2-live");
  }

  /**
   * The durable request snapshot is redacted: the approved bytes, the authority signature and every
   * free-form member are either dropped or replaced by a digest, so a persisted delivery can never
   * leak the operator's approval material or the account's observation.
   */
  @Test
  void frozenV2PersistedRequestSnapshotIsRedacted() {
    OrchestrationRequest sample = request("capability", Mode.HELD);
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    InMemoryAccountBuilderOrchestrationStore store = v2Store();
    AccountBuilderOrchestrationService service = service(gateway, store);
    service.orchestrateFrozenV2(
        new AccountBuilderOrchestrationModels.FrozenV2Command(
            "orch-v2-redacted",
            "req-v2-redacted",
            "idem-v2-redacted",
            Mode.BOOTSTRAP_RECORDING,
            NOW.plusSeconds(300),
            "session-v2-1",
            "turn-v2-1",
            frozenV2Bundle(sample)));

    OrchestrationRequest persisted = service.findRequest("orch-v2-redacted");

    assertThat(persisted.frozenBundle()).as("the approved bytes are never persisted").isNull();
    assertThat(persisted.authority().signature()).as("the signature is never persisted").isNull();
    assertThat(persisted.authority().authorityId()).isEqualTo("authority-v2-1");
    assertThat(persisted.envelope().settings().path("redacted").asBoolean(false)).isTrue();
    assertThat(persisted.envelope().identities().path("redacted").asBoolean(false)).isTrue();
    assertThat(persisted.observation().path("redacted").asBoolean(false)).isTrue();
    assertThat(persisted.envelope().requestId()).isEqualTo("req-v2-1");
  }

  /**
   * A certification-mode executor refuses to bind its listener, so the capability probe can never
   * succeed. The delivery must deny without reserving, dispatching or consuming the one-use
   * authority: a refused executor is never a delivery attempt.
   */
  @Test
  void frozenV2CertificationRefusalDeniesWithoutReservingOrConsumingAuthority() {
    OrchestrationRequest sample = request("capability", Mode.HELD);
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    gateway.executorRefused = true;
    InMemoryAccountBuilderOrchestrationStore store = v2Store();
    AccountBuilderOrchestrationService service = service(gateway, store);

    var submission =
        service.orchestrateFrozenV2(
            new AccountBuilderOrchestrationModels.FrozenV2Command(
                "orch-v2-refused",
                "req-v2-refused",
                "idem-v2-refused",
                Mode.BOOTSTRAP_RECORDING,
                NOW.plusSeconds(300),
                "session-v2-1",
                "turn-v2-1",
                frozenV2Bundle(sample)));

    assertThat(submission.view().state()).isEqualTo(State.DENIED);
    assertThat(submission.view().failureCode()).isEqualTo(FailureCode.GATEWAY_UNAVAILABLE);
    assertThat(gateway.reservations.get()).isZero();
    assertThat(gateway.dispatches.get()).isZero();
    assertThat(store.find("orch-v2-refused").orElseThrow().authorityConsumed()).isFalse();
  }

  /**
   * A frozen bundle whose budget limits are outside the contract bounds is refused before the claim
   * is persisted and before any gateway access, so an invalid approval can never burn authority.
   */
  @Test
  void frozenV2InvalidBudgetIsRefusedBeforeAnyClaimOrGatewayAccess() {
    OrchestrationRequest sample = request("capability", Mode.HELD);
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    InMemoryAccountBuilderOrchestrationStore store = v2Store();
    AccountBuilderOrchestrationService service = service(gateway, store);

    assertThatThrownBy(
            () ->
                service.orchestrateFrozenV2(
                    new AccountBuilderOrchestrationModels.FrozenV2Command(
                        "orch-v2-invalid",
                        "req-v2-invalid",
                        "idem-v2-invalid",
                        Mode.BOOTSTRAP_RECORDING,
                        NOW.plusSeconds(300),
                        "session-v2-1",
                        "turn-v2-1",
                        frozenV2Bundle(sample, 0))))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("budget");

    assertThat(gateway.capabilityReads.get()).isZero();
    assertThat(gateway.reservations.get()).isZero();
    assertThat(gateway.dispatches.get()).isZero();
    assertThat(store.find("orch-v2-invalid")).isEmpty();
  }

  /**
   * A crash after the reserve was submitted leaves the outcome unknown. Recovery may only look the
   * reservation up by its durable correlation: it must never reserve or dispatch a second time, and
   * it must converge on the state the executor actually holds.
   */
  @Test
  void frozenV2CrashAfterSubmissionResumesLookupOnly() {
    OrchestrationRequest sample = request("capability", Mode.HELD);
    FakeGateway gateway = new FakeGateway(State.CONFIRMED);
    gateway.timeOutAfterSubmission = true;
    InMemoryAccountBuilderOrchestrationStore store = v2Store();
    AccountBuilderOrchestrationService service = service(gateway, store);

    var submission =
        service.orchestrateFrozenV2(
            new AccountBuilderOrchestrationModels.FrozenV2Command(
                "orch-v2-crash",
                "req-v2-crash",
                "idem-v2-crash",
                Mode.BOOTSTRAP_RECORDING,
                NOW.plusSeconds(300),
                "session-v2-1",
                "turn-v2-1",
                frozenV2Bundle(sample)));

    assertThat(submission.view().state()).isEqualTo(State.NEEDS_RECONCILIATION);
    assertThat(submission.view().failureCode()).isEqualTo(FailureCode.DELIVERY_UNCERTAIN);
    assertThat(store.find("orch-v2-crash").orElseThrow().authorityConsumed()).isTrue();

    gateway.timeOutAfterSubmission = false;
    // Micro6's status payload always attests the reservation lease, so a lookup of a request that
    // does exist can hand the durable owner the lease it never received.
    gateway.lookupCarriesLease = true;
    OrchestrationView recovered = service.reconcile("orch-v2-crash");

    assertThat(recovered.state()).isEqualTo(State.CONFIRMED);
    assertThat(gateway.reservations.get()).as("recovery never reserves again").isEqualTo(1);
    assertThat(gateway.dispatches.get()).as("recovery never dispatches").isZero();
    assertThat(gateway.reservationReconciliations.get()).isEqualTo(1);
  }

  /**
   * A kill-switch stop is authorized by the approved scope, and the v2 bundle is the only place that
   * scope can come from. Without a RUN scope the request is refused before any gateway access.
   */
  @Test
  void frozenV2KillSwitchStopWithoutTheApprovedScopeNeverReachesTheGateway() {
    OrchestrationRequest sample = request("capability", Mode.HELD);
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    InMemoryAccountBuilderOrchestrationStore store = v2Store();
    AccountBuilderOrchestrationService service = service(gateway, store);
    service.orchestrateFrozenV2(
        new AccountBuilderOrchestrationModels.FrozenV2Command(
            "orch-v2-kill-denied",
            "req-v2-kill-denied",
            "idem-v2-kill-denied",
            Mode.BOOTSTRAP_RECORDING,
            NOW.plusSeconds(300),
            "session-v2-1",
            "turn-v2-1",
            frozenV2Bundle(sample)));

    assertThatThrownBy(
            () -> service.stop("orch-v2-kill-denied", "kill-switch:operator", true))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("not authorized");
    assertThat(gateway.stops.get()).isZero();
  }

  /** The same stop is accepted when the approved bundle declares the RUN scope. */
  @Test
  void frozenV2KillSwitchStopIsAuthorizedByTheApprovedScope() {
    OrchestrationRequest sample = request("capability", Mode.HELD);
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    InMemoryAccountBuilderOrchestrationStore store = v2Store();
    AccountBuilderOrchestrationService service = service(gateway, store);
    service.orchestrateFrozenV2(
        new AccountBuilderOrchestrationModels.FrozenV2Command(
            "orch-v2-kill",
            "req-v2-kill",
            "idem-v2-kill",
            Mode.BOOTSTRAP_RECORDING,
            NOW.plusSeconds(300),
            "session-v2-1",
            "turn-v2-1",
            frozenV2Bundle(sample, 1, "RUN")));

    OrchestrationView stopped = service.stop("orch-v2-kill", "kill-switch:operator", true);

    assertThat(stopped.state()).isEqualTo(State.STOPPED);
    assertThat(gateway.stops.get()).isEqualTo(1);
    assertThat(gateway.lastStopKillSwitch).isTrue();
  }

  /**
   * An exact replay of a frozen v2 delivery is lookup-only: the durable claim already owns the
   * bundle digest, so no second reserve is attempted.
   */
  @Test
  void frozenV2ExactReplayIsIdempotentAndDoesNotReserveAgain() {
    OrchestrationRequest sample = request("capability", Mode.HELD);
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    gateway.staleCapabilities = true;
    InMemoryAccountBuilderOrchestrationStore store = v2Store();
    AccountBuilderOrchestrationService service = service(gateway, store);
    var command =
        new AccountBuilderOrchestrationModels.FrozenV2Command(
            "orch-v2-replay",
            "req-v2-replay",
            "idem-v2-replay",
            Mode.BOOTSTRAP_RECORDING,
            NOW.plusSeconds(300),
            "session-v2-1",
            "turn-v2-1",
            frozenV2Bundle(sample));

    var first = service.orchestrateFrozenV2(command);
    var replay = service.orchestrateFrozenV2(command);

    assertThat(first.created()).isTrue();
    assertThat(replay.created()).isFalse();
    assertThat(replay.view().requestFingerprint()).isEqualTo(first.view().requestFingerprint());
    assertThat(gateway.reservations.get()).isZero();
  }

  /**
   * A different bundle under the same orchestration identity is a conflict, never a second attempt:
   * the approved bytes are the identity.
   */
  @Test
  void frozenV2ChangedBundleConflictsWithTheExistingClaim() {
    OrchestrationRequest sample = request("capability", Mode.HELD);
    FakeGateway gateway = new FakeGateway(State.ACTIVE);
    gateway.staleCapabilities = true;
    InMemoryAccountBuilderOrchestrationStore store = v2Store();
    AccountBuilderOrchestrationService service = service(gateway, store);
    service.orchestrateFrozenV2(
        new AccountBuilderOrchestrationModels.FrozenV2Command(
            "orch-v2-conflict",
            "req-v2-conflict",
            "idem-v2-conflict",
            Mode.BOOTSTRAP_RECORDING,
            NOW.plusSeconds(300),
            "session-v2-1",
            "turn-v2-1",
            frozenV2Bundle(sample)));

    var changed =
        new AccountBuilderOrchestrationModels.FrozenV2Command(
            "orch-v2-conflict",
            "req-v2-conflict",
            "idem-v2-conflict",
            Mode.BOOTSTRAP_RECORDING,
            NOW.plusSeconds(300),
            "session-v2-1",
            "turn-v2-1",
            frozenV2BundleWithDigest(sample, "sha256:" + "e".repeat(64)));

    assertThatThrownBy(() -> service.orchestrateFrozenV2(changed))
        .isInstanceOf(AccountBuilderOrchestrationException.class);
    assertThat(gateway.reservations.get()).isZero();
  }

  /** Minimal frozen bundle whose envelope attests the same capability identity as the fixture. */
  private AccountBuilderOrchestrationModels.FrozenV2Bundle frozenV2BundleWithDigest(
      OrchestrationRequest sample, String bundleDigest) {
    var base = frozenV2Bundle(sample);
    return new AccountBuilderOrchestrationModels.FrozenV2Bundle(
        base.bytes(),
        bundleDigest,
        base.envelopeDigest(),
        base.scopeDigest(),
        base.catalogDigest(),
        base.observationDigest(),
        base.planningInputDigest(),
        base.eligibilityResultDigest(),
        base.goalPlanDigest(),
        base.semanticActionDigest(),
        base.authorityId(),
        base.requestId(),
        base.idempotencyKey(),
        base.envelopeDeadline());
  }

  private AccountBuilderOrchestrationModels.FrozenV2Bundle frozenV2Bundle(
      OrchestrationRequest sample) {
    return frozenV2Bundle(sample, 1, null);
  }

  private AccountBuilderOrchestrationModels.FrozenV2Bundle frozenV2Bundle(
      OrchestrationRequest sample, int maxActions) {
    return frozenV2Bundle(sample, maxActions, null);
  }

  private AccountBuilderOrchestrationModels.FrozenV2Bundle frozenV2Bundle(
      OrchestrationRequest sample, int maxActions, String killSwitchScope) {
    var envelope = objectMapper.createObjectNode();
    envelope.put("contractVersion", "sc-1429/v2");
    envelope.put("mode", "LIVE_TASK");
    envelope.put("provider", "CLAUDE");
    envelope.put("modelVersion", sample.envelope().modelVersion());
    envelope.put("decisionId", sample.envelope().decisionId());
    envelope.put("requestId", "req-v2-1");
    envelope.put("idempotencyKey", "idem-v2-1");
    envelope.put("promptId", "prompt-v2-1");
    envelope.put("taskKey", "FISHING_TRAINING");
    envelope.put("catalogDigest", sample.bindings().catalogDigest());
    envelope.put("schemaDigest", sample.envelope().schemaDigest());
    envelope.put("policyDigest", sample.envelope().policyDigest());
    envelope.put("knowledgeDigest", sample.envelope().knowledgeDigest());
    envelope.put("verifierId", sample.envelope().verifierId());
    envelope.put("buildId", sample.envelope().buildId());
    envelope.put("expectedObservationRevision", sample.envelope().expectedObservationRevision());
    envelope.put("expectedTaskRevision", sample.envelope().expectedTaskRevision());
    envelope.put("capabilitySnapshotDigest", sample.bindings().capabilityDigest());
    envelope.put("leaseGeneration", 1);
    envelope.put("fence", 1);
    envelope.put("authorityId", "authority-v2-1");
    if (killSwitchScope != null) {
      envelope.put("killSwitchScope", killSwitchScope);
    }
    envelope.put("deadline", NOW.plusSeconds(300).toString());
    envelope.putObject("identities").put("clientId", "client-1");
    var budgets = envelope.putObject("budgets");
    budgets.put("maxActions", maxActions);
    budgets.put("maxAttempts", 1);
    budgets.put("maxElapsedMs", 300000);

    var authority = objectMapper.createObjectNode();
    authority.put("contractVersion", "sc-1429/v2");
    authority.put("authorityId", "authority-v2-1");
    authority.put("actorType", "HUMAN");
    authority.put("approvedBy", "operator-1");
    authority.put("approvedAt", NOW.minusSeconds(60).toString());
    authority.put("expiresAt", NOW.plusSeconds(300).toString());
    authority.put("envelopeDigest", V2_ENVELOPE_DIGEST);
    authority.put("scopeDigest", "sha256:" + "2".repeat(64));
    authority.put("oneUse", true);
    authority.put("signature", "hmac-sha256:" + "3".repeat(64));

    var decision = objectMapper.createObjectNode();
    decision.put("contractVersion", "sc-1429/v2");
    decision.put("kind", "SELECTED");
    decision.put("policyConfidence", 0.9);
    decision.putArray("evidenceRefs").add("observation:1");

    var observation = objectMapper.createObjectNode();
    observation.put("contractVersion", "sc-1429/v2");
    observation.put("observationId", "observation-1");

    var bundle = objectMapper.createObjectNode();
    bundle.set("executionEnvelope", envelope);
    bundle.set("authority", authority);
    bundle.set("providerDecision", decision);
    bundle.set("semanticAction", objectMapper.createObjectNode().put("kind", "START_FISHING_TASK"));
    bundle.set("semanticObservation", observation);
    byte[] bytes;
    try {
      bytes = objectMapper.writeValueAsBytes(bundle);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
    return new AccountBuilderOrchestrationModels.FrozenV2Bundle(
        bytes,
        "sha256:" + "4".repeat(64),
        V2_ENVELOPE_DIGEST,
        "sha256:" + "6".repeat(64),
        sample.bindings().catalogDigest(),
        "sha256:" + "7".repeat(64),
        "sha256:" + "8".repeat(64),
        "sha256:" + "9".repeat(64),
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "authority-v2-1",
        "req-v2-1",
        "idem-v2-1",
        NOW.plusSeconds(300));
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
            null,
            null,
            null);
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
            envelope.deadline(),
            frozenBundle(envelope));
    return withFingerprint(withoutFingerprint, validator.immutableFingerprint(withoutFingerprint));
  }

  /**
   * Minimal frozen v2 bundle for delivery-mode fixtures. Its envelope digest is the canonical digest
   * of the request envelope, which is exactly the identity the service and gateway compare.
   */
  private AccountBuilderOrchestrationModels.FrozenV2Bundle frozenBundle(ExecutionEnvelope envelope) {
    String envelopeDigest = canonicalJson.digest(envelope);
    return new AccountBuilderOrchestrationModels.FrozenV2Bundle(
        ("{\"executionEnvelope\":{\"contractVersion\":\"sc-1429/v2\",\"mode\":\"LIVE_TASK\","
                + "\"requestId\":\""
                + envelope.requestId()
                + "\"}}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
        canonicalJson.digest(java.util.Map.of("bundle", envelope.requestId())),
        envelopeDigest,
        fixtures.at("/expectedDigests/approvedScope").asText(),
        envelope.catalogDigest(),
        "sha256:" + "b".repeat(64),
        "sha256:" + "e".repeat(64),
        "sha256:" + "f".repeat(64),
        envelope.goalPlanDigest(),
        envelope.semanticActionDigest(),
        envelope.authorityId(),
        envelope.requestId(),
        envelope.idempotencyKey(),
        envelope.deadline());
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
            request.deadline(),
            frozenBundle(envelope));
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
            request.deadline(),
            request.frozenBundle());
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
            request.deadline(),
            request.frozenBundle());
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
        request.deadline(),
        request.frozenBundle());
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
    private boolean executorRefused;
    private boolean timeOutAfterSubmission;
    private boolean lookupCarriesLease;
    private boolean lastStopKillSwitch;

    private FakeGateway(State dispatchState) {
      this.dispatchState = dispatchState;
      this.statusState = dispatchState;
    }

    @Override
    public CapabilitySnapshot capabilities(OrchestrationRequest request) {
      capabilityReads.incrementAndGet();
      if (executorRefused) {
        // A certification-mode executor refuses to bind its listener, so no probe can succeed.
        return null;
      }
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
          correlation,
          "RESERVATION_RECONCILE",
          statusState,
          lookupCarriesLease ? reservationLease(correlation) : null,
          true,
          statusState.terminal());
    }

    private Lease reservationLease(GatewayCorrelation correlation) {
      return new Lease(
          "reservation-" + correlation.requestId(),
          1,
          1,
          "sha256:" + "f".repeat(64),
          NOW.plusSeconds(120));
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

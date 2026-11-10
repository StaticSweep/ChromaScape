package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderBudgetPolicy.OrchestrationBudget;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FrozenV2Bundle;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FrozenV2Command;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.ProviderOrchestrationCommand;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Readiness;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Durable, source-neutral SC-1431 orchestration with no blind retry path. */
@Service
public final class AccountBuilderOrchestrationService {
  private static final String DIGEST_PATTERN = "sha256:[0-9a-f]{64}";

  /**
   * Conversion only. Every value it converts has already been validated by the assembler, and the
   * v2 envelope carries members the v1 carrier record does not model, so unknown members are
   * ignored rather than rejected.
   */
  private static final com.fasterxml.jackson.databind.ObjectMapper CONVERTER =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .findAndRegisterModules()
          .configure(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              false);

  private final AccountBuilderOrchestrationValidator validator;
  private final CanonicalGameplayJson canonicalJson;
  private final AccountBuilderOrchestrationStore store;
  private final AccountBuilderOrchestrationGateway gateway;
  private final AccountBuilderOrchestrationProperties properties;
  private final Clock clock;
  private final ProviderDecisionOrchestrationSource providerSource;
  private final AccountBuilderCapabilityPreflight capabilityPreflight;

  @Autowired
  public AccountBuilderOrchestrationService(
      AccountBuilderOrchestrationValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderOrchestrationStore store,
      AccountBuilderOrchestrationGateway gateway,
      AccountBuilderOrchestrationProperties properties,
      ProviderDecisionOrchestrationSource providerSource,
      AccountBuilderCapabilityPreflight capabilityPreflight) {
    this(
        validator,
        canonicalJson,
        store,
        gateway,
        properties,
        Clock.systemUTC(),
        providerSource,
        capabilityPreflight);
  }

  AccountBuilderOrchestrationService(
      AccountBuilderOrchestrationValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderOrchestrationStore store,
      AccountBuilderOrchestrationGateway gateway,
      AccountBuilderOrchestrationProperties properties,
      Clock clock,
      ProviderDecisionOrchestrationSource providerSource,
      AccountBuilderCapabilityPreflight capabilityPreflight) {
    this.validator = validator;
    this.canonicalJson = canonicalJson;
    this.store = store;
    this.gateway = gateway;
    this.properties = properties;
    this.clock = clock;
    this.providerSource = Objects.requireNonNull(providerSource, "providerSource");
    this.capabilityPreflight = Objects.requireNonNull(capabilityPreflight, "capabilityPreflight");
  }

  /**
   * Returns the durable view of an existing orchestration without validating any new request. The
   * bootstrap path consults this before its read-only capability preflight, so an idempotent replay
   * neither fetches a new snapshot nor replaces an existing capability binding.
   */
  public java.util.Optional<OrchestrationView> findOrchestration(String orchestrationId) {
    return store.find(orchestrationId);
  }

  /**
   * Returns the persisted capability digest for an existing orchestration, failing closed when the
   * stored aggregate is missing or malformed. Replay resolution uses it to rebuild the exact
   * immutable fingerprint without contacting the gateway.
   */
  public String storedCapabilityDigest(String orchestrationId) {
    AccountBuilderOrchestrationStore.NormalizedAggregate aggregate =
        store.readAggregate(orchestrationId);
    SnapshotBindings bindings = aggregate == null ? null : aggregate.bindings();
    String capabilityDigest = bindings == null ? null : bindings.capabilityDigest();
    if (capabilityDigest == null || !capabilityDigest.matches(DIGEST_PATTERN)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT, "Persisted capability binding is missing or malformed");
    }
    return capabilityDigest;
  }

  /**
   * Generic orchestration entry point. Provider-sourced requests are categorically rejected here,
   * before any durable claim, budget consumption, authority consumption or gateway access, so a
   * direct service caller can never enter the provider trust path by forging a source type.
   * Provider orchestrations must flow through {@link
   * #orchestrateProvider(ProviderOrchestrationCommand)}.
   */
  public Submission orchestrate(OrchestrationRequest request) {
    if (request != null && AccountBuilderOrchestrationSources.providerLike(request.sourceType())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "Provider source orchestrations must be created through the persisted provider decision"
              + " handoff");
    }
    return orchestrateInternal(request);
  }

  /**
   * Single provider-orchestration trust boundary. The caller supplies only a {@link
   * ProviderOrchestrationCommand}; the provider decision, source type and provenance are always
   * loaded from the durable persisted provider turn by {@link ProviderDecisionOrchestrationSource}.
   * Preparation, replay resolution and capability preflight all happen here, so no caller can
   * assemble a provider-shaped {@link OrchestrationRequest} and submit it. The final {@link
   * #orchestrateInternal(OrchestrationRequest)} stays private.
   *
   * <p>Authorized callers are the SC-1431 provider-orchestration controller route and the SC-1498
   * bootstrap operator service for {@code BOOTSTRAP_RECORDING} delivery. All other callers must use
   * {@link #orchestrate(OrchestrationRequest)}, which rejects provider sources.
   */
  public Submission orchestrateProvider(ProviderOrchestrationCommand command) {
    Objects.requireNonNull(command, "command");
    ProviderDecisionOrchestrationSource.PreparedProviderRequest prepared =
        providerSource.prepareProduction(command);
    Optional<OrchestrationView> existing = store.find(command.orchestrationId());
    if (existing.isPresent()) {
      // Exact replay: rebuild the candidate with the persisted capability digest and compare the
      // full immutable fingerprint. Any changed provider session, turn, decision, authority,
      // envelope, observation or binding conflicts. No capability access occurs.
      String storedCapabilityDigest = storedCapabilityDigest(command.orchestrationId());
      OrchestrationRequest replay =
          providerSource.completeProductionReplay(prepared, storedCapabilityDigest);
      if (!replay.requestFingerprint().equals(existing.get().requestFingerprint())) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.IDEMPOTENCY_CONFLICT,
            "A different request is already persisted under this orchestration id");
      }
      return new Submission(existing.get(), false);
    }
    // Only now, for a genuinely new orchestration, is the read-only capability preflight made.
    CapabilitySnapshot capabilityPreflightSnapshot = capabilityPreflight.probe(command.envelope());
    OrchestrationRequest request =
        providerSource.completeProduction(prepared, capabilityPreflightSnapshot);
    return orchestrateInternal(request);
  }

  /**
   * Validates a provider orchestration command against the durable persisted provider turn without
   * claiming, consuming authority, probing Micro6 or touching the gateway. SC-1498 calls this
   * before creating its durable recording projection so a stale or malformed command can never
   * leave an orphan {@code STARTED} recording.
   */
  public void validateProviderCommand(ProviderOrchestrationCommand command) {
    Objects.requireNonNull(command, "command");
    providerSource.prepareProduction(command);
  }

  private Submission orchestrateInternal(OrchestrationRequest request) {
    String fingerprint = validator.validateAndFingerprint(request);
    String envelopeDigest = canonicalJson.digest(request.envelope());
    return orchestrateInternal(request, fingerprint, envelopeDigest, false);
  }

  /**
   * Delivers one frozen SC-1429/v2 bundle.
   *
   * <p>The bundle was validated locally by the assembler with the same schema, eligibility replay,
   * full-binding and authority rules Micro6 applies, and Micro6 re-validates it independently on
   * reserve. This path therefore uses the bundle's own digests as the immutable identity — the
   * fingerprint is the bundle digest and the envelope digest is the one the authority binds — and
   * skips the frozen v1 contract and authority-record checks, which require the sc-1429/v1
   * contract version.
   */
  public Submission orchestrateFrozenV2(FrozenV2Command command) {
    Objects.requireNonNull(command, "command");
    FrozenV2Bundle frozen = command.frozenBundle();
    if (frozen == null || !frozen.usable()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT, "A complete frozen SC-1429/v2 bundle is required");
    }
    OrchestrationRequest request = frozenV2Carrier(command, frozen);
    return orchestrateInternal(request, frozen.bundleDigest(), frozen.envelopeDigest(), true);
  }

  /**
   * Carrier request for the durable store and the read-only capability probe.
   *
   * <p>Every value is taken from the frozen bundle, so the persisted request snapshot is a faithful
   * projection of the approved bytes rather than a synthesised v1 decision: the decision carries the
   * bundle's own semantic action, the bindings carry the bundle's own digests, and the observation
   * is the bundle's semantic observation.
   */
  private OrchestrationRequest frozenV2Carrier(FrozenV2Command command, FrozenV2Bundle frozen) {
    JsonNode bundle;
    try {
      bundle = CONVERTER.readTree(frozen.bytes());
    } catch (java.io.IOException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT, "Frozen bundle bytes are not valid JSON", exception);
    }
    JsonNode envelopeNode = bundle.path("executionEnvelope");
    JsonNode decisionNode = bundle.path("providerDecision");
    JsonNode observationNode = bundle.path("semanticObservation");
    ExecutionEnvelope envelope =
        CONVERTER.convertValue(envelopeNode, ExecutionEnvelope.class);
    HumanAuthority authority =
        CONVERTER.convertValue(bundle.path("authority"), HumanAuthority.class);
    ProviderDecision decision =
        new ProviderDecision(
            "sc-1429/v2",
            envelope.provider(),
            envelope.modelVersion(),
            envelope.decisionId(),
            decisionNode.path("policyConfidence").asDouble(),
            bundle.path("semanticAction").deepCopy(),
            CONVERTER.convertValue(
                decisionNode.path("evidenceRefs"),
                CONVERTER.getTypeFactory().constructCollectionType(java.util.List.class, String.class)));
    SnapshotBindings bindings =
        new SnapshotBindings(
            frozen.bundleDigest(),
            frozen.semanticActionDigest(),
            frozen.catalogDigest(),
            envelopeNode.path("capabilitySnapshotDigest").asText(null),
            frozen.goalPlanDigest(),
            CONVERTER.convertValue(
                envelopeNode.path("taskSkillDigests"),
                CONVERTER.getTypeFactory().constructCollectionType(java.util.List.class, String.class)),
            envelope.schemaDigest(),
            envelope.knowledgeDigest(),
            envelope.policyDigest(),
            envelope.verifierId(),
            envelope.buildId(),
            envelope.expectedObservationRevision(),
            envelope.expectedTaskRevision(),
            frozen.observationDigest(),
            envelopeNode.path("promptId").asText(null),
            command.providerSessionId(),
            command.providerTurnId());
    return new OrchestrationRequest(
        command.orchestrationId(),
        command.requestId(),
        command.idempotencyKey(),
        frozen.bundleDigest(),
        command.mode(),
        "PROVIDER:" + envelope.provider().name(),
        decision,
        envelope,
        bindings,
        authority,
        observationNode.deepCopy(),
        command.deadline(),
        frozen);
  }

  private Submission orchestrateInternal(
      OrchestrationRequest request,
      String fingerprint,
      String envelopeDigest,
      boolean frozenV2) {
    // The budget is parsed and validated before the claim, so an invalid frozen contract can
    // never persist any part of the orchestration aggregate.
    AccountBuilderBudgetPolicy.OrchestrationBudget budget;
    try {
      budget = OrchestrationBudget.fromEnvelope(request.envelope(), canonicalJson, clock);
    } catch (IllegalArgumentException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "Frozen SC-1429 budget limits are invalid at claim time",
          exception);
    }
    AccountBuilderOrchestrationStore.Claim claim =
        store.claim(request, fingerprint, envelopeDigest, budget);
    if (!claim.created()) {
      return new Submission(claim.view(), false);
    }

    store.transition(
        request.orchestrationId(), State.VALIDATED, FailureCode.NONE, null, null, false, false);
    if (request.mode() == Mode.HELD || request.mode() == Mode.SHADOW) {
      return new Submission(
          store.transition(
              request.orchestrationId(), State.HELD, FailureCode.NONE, null, null, false, false),
          true);
    }
    if (request.mode() == Mode.SIMULATION) {
      return terminal(request, State.DENIED, FailureCode.MODE_NOT_DELIVERABLE, null, false);
    }
    if (!properties.isEnabled() || !properties.isLiveDeliveryEnabled()) {
      return terminal(request, State.DENIED, FailureCode.RUNTIME_DISABLED, null, false);
    }
    // A delivery request must carry the frozen complete v2 bundle before any budget or authority
    // is touched. Refusing here is a proven pre-submission denial: no authority is consumed and no
    // gateway call is made, so a partial payload can never burn a one-use approval.
    if (request.frozenBundle() == null || !request.frozenBundle().usable()) {
      return terminal(request, State.DENIED, FailureCode.INVALID_CONTRACT, null, false);
    }
    if (!request.frozenBundle().envelopeDigest().equals(envelopeDigest)) {
      return terminal(request, State.DENIED, FailureCode.STALE_BINDING, null, false);
    }

    boolean authorityConsumed = false;
    try {
      // The reserve begins the one external attempt cycle this orchestration may ever run. The
      // durable counter advance happens before any gateway access, so an exhausted or expired
      // budget is always a proven denial, never a gateway mutation.
      store.consumeBudget(request.orchestrationId(), false, true);
      CapabilitySnapshot capabilities = gateway.capabilities(request);
      if (capabilities == null || !capabilities.liveDeliveryReady()) {
        return terminal(request, State.DENIED, FailureCode.GATEWAY_UNAVAILABLE, null, false);
      }
      validator.validateCapabilities(request, capabilities);
      if (!frozenV2) {
        // The v1 authority record check requires the frozen sc-1429/v1 contract version. A v2
        // authority was already checked by the assembler against the same rules and is checked
        // again independently by Micro6 on reserve.
        validator.validateAuthority(request, envelopeDigest, request.authority());
      }

      // Authority ownership is its own short transaction and is committed before reserve. If the
      // process dies after this call, the orchestration is recoverable but never redispatched.
      store.consumeAuthority(
          request.orchestrationId(), request.authority(), envelopeDigest, request);
      authorityConsumed = true;
      store.transition(
          request.orchestrationId(), State.AUTHORIZED, FailureCode.NONE, null, null, false, false);

      GatewayReceipt reservation;
      try {
        reservation = gateway.reserve(request, envelopeDigest);
        requireBoundReceipt(request, envelopeDigest, reservation, null);
      } catch (AccountBuilderOrchestrationException exception) {
        if (exception.failureCode() == FailureCode.GATEWAY_UNAVAILABLE) {
          // The adapter only raises GATEWAY_UNAVAILABLE when the transport proves the connection
          // never opened, so no request bytes could have been submitted. The outcome is therefore
          // a proven pre-submission failure, not a reconciliation case. Authority is already
          // consumed, so the orchestration is non-retryable: a new attempt requires a new
          // orchestration with fresh valid human authority.
          return terminal(request, State.FAILED, FailureCode.GATEWAY_UNAVAILABLE, null, false);
        }
        return uncertain(request, uncertaintyCode(exception));
      } catch (RuntimeException exception) {
        return uncertain(request, uncertaintyCode(exception));
      }

      if (reservation.state() != State.RESERVED) {
        if (provenReservationOutcome(reservation)) {
          FailureCode code =
              reservation.failureCode() == FailureCode.NONE
                  ? FailureCode.RESERVATION_REJECTED
                  : reservation.failureCode();
          return terminal(
              request, reservation.state(), code, withFailureCode(reservation, code), false);
        }
        return uncertain(request, FailureCode.DELIVERY_UNCERTAIN);
      }
      if (!validLease(request, reservation.lease())) {
        return uncertain(request, FailureCode.DELIVERY_UNCERTAIN);
      }
      OrchestrationView reserved =
          store.transition(
              request.orchestrationId(),
              State.RESERVED,
              FailureCode.NONE,
              reservation.lease(),
              reservation,
              false,
              false);
      return new Submission(deliver(request, envelopeDigest, reserved.lease()), true);
    } catch (AccountBuilderOrchestrationException exception) {
      return authorityConsumed
          ? uncertain(request, uncertaintyCode(exception))
          : terminal(request, State.DENIED, exception.failureCode(), null, false);
    } catch (RuntimeException exception) {
      return authorityConsumed
          ? uncertain(request, uncertaintyCode(exception))
          : terminal(request, State.DENIED, FailureCode.GATEWAY_UNAVAILABLE, null, false);
    }
  }

  public OrchestrationView find(String orchestrationId) {
    return store
        .find(orchestrationId)
        .orElseThrow(
            () ->
                new AccountBuilderOrchestrationException(
                    FailureCode.INVALID_CONTRACT, "Orchestration was not found"));
  }

  /**
   * Returns the durable redacted SC-1431 request snapshot for evidence recovery (SC-1498).
   *
   * <p>Used only to rebuild terminal evidence manifests from durable state after a crash. It never
   * dispatches, reserves or mutates delivery state; recovery is lookup/finalization only.
   */
  public OrchestrationRequest findRequest(String orchestrationId) {
    AccountBuilderOrchestrationStore.NormalizedAggregate aggregate =
        store.readAggregate(orchestrationId);
    OrchestrationRequest snapshot = aggregate == null ? null : aggregate.redactedRequestSnapshot();
    if (snapshot == null) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT, "Persisted orchestration request was not found");
    }
    return snapshot;
  }

  /**
   * Durable terminal result for deterministic manifest recovery (SC-1498).
   *
   * <p>Prefers the persisted SC-1431 terminal-result timestamp so crash recovery replays
   * byte-identical manifest bytes and digest. Never dispatches or mutates delivery state.
   */
  public java.util.Optional<AccountBuilderOrchestrationModels.TerminalResult> findTerminalResult(
      String orchestrationId) {
    return store.terminalResult(orchestrationId);
  }

  /** Durable budget row for the orchestration, if immutable limits were claimed. */
  public Optional<AccountBuilderBudgetPolicy.OrchestrationBudget> budget(String orchestrationId) {
    return store.findBudget(orchestrationId);
  }

  public OrchestrationView stop(String orchestrationId, String reason, boolean killSwitch) {
    OrchestrationView view = find(orchestrationId);
    if (reason == null
        || reason.isBlank()
        || reason.length() > 256
        || view.state().provenTerminal()
        || view.state() == State.HELD) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STOP_REJECTED, "A bounded stop requires active orchestration state");
    }
    // Kill-switch use is authorized by the approved scope. This check runs before any gateway
    // access: an unauthorized kill switch can never reach the transport.
    requireKillSwitchScope(orchestrationId, killSwitch);
    try {
      GatewayCorrelation correlation = store.correlation(orchestrationId);
      Lease lease = store.requireLease(orchestrationId, view.lease());
      GatewayReceipt receipt = gateway.stop(correlation, lease, reason, killSwitch);
      requireBoundReceipt(correlation, receipt, lease);
      State state =
          receipt.terminalReconciled() && receipt.state().provenTerminal()
              ? receipt.state()
              : State.NEEDS_RECONCILIATION;
      FailureCode code =
          state == State.NEEDS_RECONCILIATION
              ? FailureCode.NEEDS_RECONCILIATION
              : receipt.failureCode();
      return store.transition(orchestrationId, state, code, lease, receipt, false, false);
    } catch (RuntimeException exception) {
      return store.transition(
          orchestrationId,
          State.NEEDS_RECONCILIATION,
          FailureCode.NEEDS_RECONCILIATION,
          null,
          null,
          false,
          false);
    }
  }

  /**
   * Kill-switch intent is only valid inside the approved {@code RUN} scope that was verified at
   * claim time. The persisted request snapshot is the authorization source; nothing here trusts the
   * caller's assertion.
   */
  private void requireKillSwitchScope(String orchestrationId, boolean killSwitch) {
    if (!killSwitch) {
      return;
    }
    OrchestrationRequest stored = store.readAggregate(orchestrationId).redactedRequestSnapshot();
    if (stored == null
        || stored.envelope() == null
        || !"RUN".equals(stored.envelope().killSwitchScope())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.OPERATOR_UNAUTHORIZED,
          "Kill-switch stop is not authorized by the approved scope");
    }
  }

  public OrchestrationView reconcile(String orchestrationId) {
    OrchestrationView view = find(orchestrationId);
    if (view.state().terminal() && view.state() != State.NEEDS_RECONCILIATION) {
      return view;
    }
    try {
      AccountBuilderOrchestrationStore.NormalizedAggregate aggregate =
          store.readAggregate(orchestrationId);
      GatewayCorrelation correlation = aggregate.correlation();
      if (aggregate.lease() == null) {
        // A consumed authority with no lease is an ambiguous reservation boundary. The only
        // permitted remote operation is a lookup keyed by the durable non-secret correlation.
        if (!aggregate.authorityConsumed() || view.state() != State.NEEDS_RECONCILIATION) {
          return view;
        }
        return reconcileReservation(orchestrationId, correlation, view);
      }
      Lease lease = store.requireLease(orchestrationId, aggregate.lease());
      GatewayReceipt receipt = gateway.reconcile(correlation, lease);
      requireBoundReceipt(correlation, receipt, lease);
      if (!receipt.terminalReconciled() || !receipt.state().provenTerminal()) {
        return store.transition(
            orchestrationId,
            State.NEEDS_RECONCILIATION,
            FailureCode.NEEDS_RECONCILIATION,
            lease,
            receipt,
            false,
            false);
      }
      return store.transition(
          orchestrationId, receipt.state(), receipt.failureCode(), lease, receipt, false, false);
    } catch (RuntimeException exception) {
      return view;
    }
  }

  /**
   * Bounded terminal wait for the SC-1498 one-run bootstrap.
   *
   * <p>Polls read-only status through the gateway's bounded helper until a proven terminal receipt
   * or the supplied deadline, then projects the durable terminal state. It never reserves or
   * dispatches again; a still-nonterminal observation stays reconciliation-only.
   */
  public OrchestrationView reconcileUntilTerminal(String orchestrationId, Instant deadline) {
    OrchestrationView view = find(orchestrationId);
    if (view.state().provenTerminal()) {
      return view;
    }
    try {
      AccountBuilderOrchestrationStore.NormalizedAggregate aggregate =
          store.readAggregate(orchestrationId);
      if (aggregate == null) {
        return view;
      }
      GatewayCorrelation correlation = aggregate.correlation();
      if (aggregate.lease() == null) {
        // No lease means the reservation boundary is ambiguous; only a lookup is permitted.
        return reconcileReservation(orchestrationId, correlation, view);
      }
      Lease lease = store.requireLease(orchestrationId, aggregate.lease());
      GatewayReceipt receipt = gateway.awaitTerminal(correlation, lease, deadline);
      requireBoundReceipt(correlation, receipt, lease);
      if (!receipt.terminalReconciled() || !receipt.state().provenTerminal()) {
        return store.transition(
            orchestrationId,
            State.NEEDS_RECONCILIATION,
            FailureCode.NEEDS_RECONCILIATION,
            lease,
            receipt,
            false,
            false);
      }
      return store.transition(
          orchestrationId, receipt.state(), receipt.failureCode(), lease, receipt, false, false);
    } catch (RuntimeException exception) {
      return view;
    }
  }

  private OrchestrationView reconcileReservation(
      String orchestrationId, GatewayCorrelation correlation, OrchestrationView current) {
    try {
      GatewayReceipt receipt = gateway.reconcileReservation(correlation);
      requireBoundReceipt(correlation, receipt, null);
      if (receipt.state() == State.DENIED || receipt.state() == State.FAILED) {
        FailureCode code =
            receipt.failureCode() == FailureCode.NONE
                ? FailureCode.RESERVATION_REJECTED
                : receipt.failureCode();
        if (receipt.terminalReconciled()) {
          return store.transition(
              orchestrationId,
              receipt.state(),
              code,
              null,
              withFailureCode(receipt, code),
              false,
              false);
        }
      } else if (receipt.terminalReconciled() && receipt.state().provenTerminal()) {
        FailureCode code =
            receipt.state() == State.DENIED && receipt.failureCode() == FailureCode.NONE
                ? FailureCode.RESERVATION_REJECTED
                : receipt.failureCode();
        return store.transition(
            orchestrationId,
            receipt.state(),
            code,
            receipt.lease(),
            withFailureCode(receipt, code),
            false,
            false);
      } else if (receipt.state() == State.RESERVED && receipt.lease() != null) {
        // A lookup may discover an exact remote reservation. Keep the orchestration quiescent;
        // acquiring the lease is evidence, never permission to redispatch.
        return store.transition(
            orchestrationId,
            State.NEEDS_RECONCILIATION,
            FailureCode.DELIVERY_UNCERTAIN,
            receipt.lease(),
            receipt,
            false,
            false);
      } else {
        // A well-formed but non-terminal lookup response is useful evidence, but it cannot move
        // the orchestration back into a delivery state.
        return store.transition(
            orchestrationId,
            State.NEEDS_RECONCILIATION,
            FailureCode.DELIVERY_UNCERTAIN,
            receipt.lease(),
            receipt,
            false,
            false);
      }
    } catch (RuntimeException exception) {
      // Missing, malformed, cross-request and ambiguous lookup results remain quiescent. In
      // particular, this path never invokes reserve or dispatch as a recovery fallback.
      return current;
    }
    return current;
  }

  public List<OrchestrationView> recoverAfterRestart() {
    return store.recoverable().stream()
        .map(
            view ->
                view.state() == State.NEEDS_RECONCILIATION
                    ? view
                    : store.transition(
                        view.orchestrationId(),
                        State.NEEDS_RECONCILIATION,
                        FailureCode.NEEDS_RECONCILIATION,
                        null,
                        null,
                        false,
                        false))
        .toList();
  }

  public Readiness readiness() {
    CapabilitySnapshot capabilities;
    try {
      // Readiness is request-free and therefore cannot present client observation evidence; a
      // catalog-only probe is never delivery-ready.
      capabilities = gateway.capabilities(null);
    } catch (RuntimeException exception) {
      capabilities = null;
    }
    boolean gatewayAvailable =
        capabilities != null
            && capabilities.expiresAt() != null
            && capabilities.expiresAt().isAfter(clock.instant());
    return new Readiness(
        AccountBuilderOrchestrationModels.VERSION,
        properties.isEnabled(),
        properties.getOperatorKey() != null && properties.getOperatorKey().length() >= 32,
        gatewayAvailable,
        gatewayAvailable && capabilities.liveDeliveryReady() && properties.isLiveDeliveryEnabled(),
        gatewayAvailable ? capabilities.capabilityDigest() : null,
        gatewayAvailable ? FailureCode.NONE : FailureCode.GATEWAY_UNAVAILABLE);
  }

  private OrchestrationView deliver(
      OrchestrationRequest request, String envelopeDigest, Lease lease) {
    try {
      // The dispatch belongs to the attempt cycle already opened by the reserve, so it consumes a
      // semantic action rather than a second attempt. An exhausted or expired budget is a proven
      // denial before the dispatch boundary is persisted and before any gateway access.
      store.consumeBudget(request.orchestrationId(), true, false);
      Lease storedLease = store.requireLease(request.orchestrationId(), lease);
      // Persist the submission boundary before the network call. A restart can therefore
      // distinguish an unattempted reservation from a call whose outcome must be reconciled.
      OrchestrationView submitted =
          store.transition(
              request.orchestrationId(),
              State.DISPATCH_SUBMITTED,
              FailureCode.NONE,
              storedLease,
              null,
              false,
              false);
      Lease dispatchLease = store.requireLease(request.orchestrationId(), submitted.lease());
      GatewayReceipt dispatch = gateway.dispatch(request, envelopeDigest, dispatchLease);
      requireBoundReceipt(request, envelopeDigest, dispatch, dispatchLease);
      if (dispatch.state().provenTerminal() && dispatch.terminalReconciled()) {
        return store.transition(
            request.orchestrationId(),
            dispatch.state(),
            dispatch.failureCode(),
            dispatchLease,
            dispatch,
            false,
            false);
      }
      // A transport acknowledgement is not activation. Only an explicitly ACTIVE receipt may
      // cross the dispatch boundary; every other response remains quiescent and reconcile-only.
      if (!dispatch.deliveryAcknowledged() || dispatch.state() != State.ACTIVE) {
        return store.transition(
            request.orchestrationId(),
            State.NEEDS_RECONCILIATION,
            FailureCode.DELIVERY_UNCERTAIN,
            dispatchLease,
            dispatch,
            false,
            false);
      }
      OrchestrationView dispatched =
          store.transition(
              request.orchestrationId(),
              dispatch.state(),
              FailureCode.NONE,
              dispatchLease,
              dispatch,
              false,
              false);
      Lease statusLease = store.requireLease(request.orchestrationId(), dispatched.lease());
      GatewayReceipt status =
          gateway.status(store.correlation(request.orchestrationId()), statusLease);
      requireBoundReceipt(store.correlation(request.orchestrationId()), status, statusLease);
      if (!status.terminalReconciled() || !status.state().provenTerminal()) {
        return store.transition(
            request.orchestrationId(),
            State.NEEDS_RECONCILIATION,
            FailureCode.NEEDS_RECONCILIATION,
            statusLease,
            status,
            false,
            false);
      }
      return store.transition(
          request.orchestrationId(),
          status.state(),
          status.failureCode(),
          statusLease,
          status,
          false,
          false);
    } catch (AccountBuilderOrchestrationException exception) {
      if (exception.failureCode() == FailureCode.BUDGET_EXHAUSTED
          || exception.failureCode() == FailureCode.DEADLINE_EXPIRED) {
        // A budget denial at the dispatch boundary is a proven terminal failure before any
        // gateway access; it is never a reconciliation case and never retryable.
        return store.transition(
            request.orchestrationId(),
            State.FAILED,
            exception.failureCode(),
            lease,
            null,
            false,
            false);
      }
      return store.transition(
          request.orchestrationId(),
          State.NEEDS_RECONCILIATION,
          uncertaintyCode(exception),
          null,
          null,
          false,
          false);
    } catch (RuntimeException exception) {
      return store.transition(
          request.orchestrationId(),
          State.NEEDS_RECONCILIATION,
          uncertaintyCode(exception),
          null,
          null,
          false,
          false);
    }
  }

  private Submission terminal(
      OrchestrationRequest request,
      State state,
      FailureCode code,
      GatewayReceipt receipt,
      boolean retryAllowed) {
    return new Submission(
        store.transition(
            request.orchestrationId(),
            state,
            code,
            receipt == null ? null : receipt.lease(),
            receipt,
            false,
            retryAllowed),
        true);
  }

  private Submission uncertain(OrchestrationRequest request, FailureCode code) {
    return new Submission(
        store.transition(
            request.orchestrationId(), State.NEEDS_RECONCILIATION, code, null, null, false, false),
        true);
  }

  private FailureCode uncertaintyCode(RuntimeException exception) {
    if (exception instanceof AccountBuilderOrchestrationException orchestrationException) {
      return switch (orchestrationException.failureCode()) {
        case BOUNDARY_VIOLATION,
            FENCE_MISMATCH,
            LEASE_EXPIRED,
            DELIVERY_UNCERTAIN -> orchestrationException.failureCode();
        default -> FailureCode.DELIVERY_UNCERTAIN;
      };
    }
    return FailureCode.DELIVERY_UNCERTAIN;
  }

  private void requireBoundReceipt(
      OrchestrationRequest request,
      String envelopeDigest,
      GatewayReceipt receipt,
      Lease expectedLease) {
    if (receipt == null
        || !request.requestId().equals(receipt.requestId())
        || !envelopeDigest.equals(receipt.envelopeDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.BOUNDARY_VIOLATION,
          "Gateway receipt identity does not match the immutable request");
    }
    if (expectedLease != null && !expectedLease.equals(receipt.lease())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.FENCE_MISMATCH, "Gateway receipt lease does not match the stored lease");
    }
  }

  private void requireBoundReceipt(
      GatewayCorrelation correlation, GatewayReceipt receipt, Lease expectedLease) {
    if (receipt == null
        || !correlation.requestId().equals(receipt.requestId())
        || !correlation.envelopeDigest().equals(receipt.envelopeDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.BOUNDARY_VIOLATION,
          "Gateway receipt identity does not match the persisted correlation");
    }
    if (expectedLease != null && !expectedLease.equals(receipt.lease())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.FENCE_MISMATCH, "Gateway receipt lease does not match the stored lease");
    }
  }

  private GatewayReceipt withFailureCode(GatewayReceipt receipt, FailureCode failureCode) {
    if (receipt.failureCode() == failureCode) {
      return receipt;
    }
    return new GatewayReceipt(
        receipt.requestId(),
        receipt.envelopeDigest(),
        receipt.phase(),
        receipt.state(),
        failureCode,
        receipt.deliveryAcknowledged(),
        receipt.terminalReconciled(),
        receipt.lease(),
        receipt.evidenceRefs(),
        receipt.at(),
        receipt.receiptId(),
        receipt.killSwitch());
  }

  private boolean validLease(OrchestrationRequest request, Lease lease) {
    return lease != null
        && lease.reservationId() != null
        && lease.reservationId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        && lease.generation() >= 1
        && lease.fence() >= 1
        && lease.fenceTokenDigest() != null
        && lease.fenceTokenDigest().matches("sha256:[0-9a-f]{64}")
        && lease.generation() == request.envelope().leaseGeneration()
        && lease.fence() == request.envelope().fence()
        && lease.expiresAt() != null
        && lease.expiresAt().isAfter(clock.instant());
  }

  private boolean provenReservationOutcome(GatewayReceipt receipt) {
    return receipt != null
        && receipt.state() != null
        && receipt.state().provenTerminal()
        && receipt.terminalReconciled()
        && (receipt.lease() == null
            || (receipt.lease().expiresAt() != null
                && receipt.lease().expiresAt().isAfter(clock.instant())));
  }

  public record Submission(OrchestrationView view, boolean created) {}
}

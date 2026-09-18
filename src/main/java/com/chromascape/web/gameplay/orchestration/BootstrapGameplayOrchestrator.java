package com.chromascape.web.gameplay.orchestration;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Outcome;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.contract.GameplayContracts.Receipt;
import com.chromascape.web.gameplay.contract.GameplayContracts.ReceiptPhase;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationException;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationRequestFactory;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded SC-1498 coordinator. It never retries a state-changing or uncertain delivery. */
public final class BootstrapGameplayOrchestrator {
  private final GameplayContractValidator validator;
  private final CanonicalGameplayJson canonicalJson;
  private final AccountBuilderGameplayGateway gateway;
  private final BootstrapEvidenceStore evidenceStore;
  private final AccountBuilderOrchestrationService orchestrationService;
  private final AccountBuilderOrchestrationRequestFactory requestFactory;
  private final Clock clock;
  private final Map<String, RequestState> requests = new ConcurrentHashMap<>();
  private final Map<String, RequestIdentity> idempotencyOwners = new ConcurrentHashMap<>();

  public BootstrapGameplayOrchestrator(
      GameplayContractValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderGameplayGateway gateway,
      BootstrapEvidenceStore evidenceStore) {
    this(validator, canonicalJson, gateway, evidenceStore, null, null, Clock.systemUTC());
  }

  BootstrapGameplayOrchestrator(
      GameplayContractValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderGameplayGateway gateway,
      BootstrapEvidenceStore evidenceStore,
      Clock clock) {
    this(validator, canonicalJson, gateway, evidenceStore, null, null, clock);
  }

  /**
   * SC-1431 unified constructor: dispatch, stop, idempotency, authority ownership and lifecycle
   * delegate to the durable orchestration service through the shared request factory; the legacy
   * gateway is only the compatibility fallback for callers that were never wired to the durable
   * store. When the durable service is configured, the shared request factory is mandatory so the
   * bootstrap path can never emit a partial fingerprint.
   */
  BootstrapGameplayOrchestrator(
      GameplayContractValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderGameplayGateway gateway,
      BootstrapEvidenceStore evidenceStore,
      AccountBuilderOrchestrationService orchestrationService,
      AccountBuilderOrchestrationRequestFactory requestFactory,
      Clock clock) {
    if (orchestrationService != null && requestFactory == null) {
      throw new IllegalStateException(
          "Durable SC-1431 bootstrap delegation requires the shared orchestration request"
              + " factory");
    }
    this.validator = validator;
    this.canonicalJson = canonicalJson;
    this.gateway = gateway;
    this.evidenceStore = evidenceStore;
    this.orchestrationService = orchestrationService;
    this.requestFactory = requestFactory;
    this.clock = clock;
  }

  /** Validates the provider proposal and envelope before an operator is shown an approval scope. */
  public void validateProposal(
      JsonNode observation, ProviderDecision decision, ExecutionEnvelope envelope) {
    validator.validate("observation", observation);
    validator.validate("semanticAction", decision.semanticAction());
    validator.validate("executionEnvelope", envelope);
    String bindingFailure = decisionBindingFailure(observation, decision, envelope);
    if (bindingFailure != null) {
      throw new GameplayProposalException(bindingFailure, "Proposal does not match its envelope");
    }
  }

  /**
   * Requests a bounded stop while {@link #dispatch} remains the owner of terminal reconciliation
   * and evidence finalization.
   */
  public AccountBuilderGameplayGateway.DispatchResult requestStop(
      String requestId, String reason, boolean killSwitch) {
    if (requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new GameplayProposalException("REQUEST_ID_INVALID", "A stable request id is required");
    }
    if (reason == null || reason.isBlank() || reason.length() > 256) {
      throw new GameplayProposalException(
          "STOP_REASON_INVALID", "A bounded stop reason is required");
    }
    if (orchestrationService != null) {
      // Durable SC-1431 state alone decides whether the orchestration exists or is terminal: no
      // in-memory bootstrap entry is required, so stop works after a process restart.
      try {
        return toDispatchResult(
            orchestrationService.stop(sc1431OrchestrationId(requestId), reason, killSwitch));
      } catch (AccountBuilderOrchestrationException exception) {
        throw new GameplayProposalException(stopFailureCode(exception), exception.getMessage());
      }
    }
    RequestState state = requests.get(requestId);
    if (state == null) {
      throw new GameplayProposalException("REQUEST_NOT_ACTIVE", "Gameplay request is not active");
    }
    if (state.terminal() != null) {
      throw new GameplayProposalException(
          "REQUEST_ALREADY_TERMINAL", "Gameplay request is already terminal");
    }
    return gateway.stop(requestId, reason, killSwitch);
  }

  /** Preserves the stable bootstrap stop codes across the durable failure mapping. */
  private static String stopFailureCode(AccountBuilderOrchestrationException exception) {
    return switch (exception.failureCode()) {
      case INVALID_CONTRACT -> "REQUEST_NOT_ACTIVE";
      case STOP_REJECTED -> "REQUEST_ALREADY_TERMINAL";
      default -> exception.failureCode().name();
    };
  }

  public Receipt dispatch(
      JsonNode observation,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      HumanAuthority authority) {
    validator.validate("observation", observation);
    validator.validate("semanticAction", decision.semanticAction());
    validator.validate("executionEnvelope", envelope);
    validator.validate("authority", authority);
    String digest = canonicalJson.digest(envelope);

    if (orchestrationService != null) {
      // Durable SC-1431 lifecycle: the bootstrap request and idempotency maps, the independent
      // terminal receipts and the bootstrap evidence store are never the lifecycle authority.
      return dispatchThroughSc1431(observation, decision, envelope, authority, digest);
    }

    String recordingId = envelope.identities().path("recordingId").asText();

    RequestIdentity idempotencyOwner =
        idempotencyOwners.putIfAbsent(
            envelope.idempotencyKey(), new RequestIdentity(envelope.requestId(), digest));
    if (idempotencyOwner != null
        && (!idempotencyOwner.requestId().equals(envelope.requestId())
            || !idempotencyOwner.envelopeDigest().equals(digest))) {
      return receipt(
          envelope,
          digest,
          ReceiptPhase.VALIDATION,
          Outcome.DENIED,
          "IDEMPOTENCY_CONFLICT",
          false,
          List.of());
    }

    RequestState existing =
        requests.putIfAbsent(envelope.requestId(), new RequestState(digest, recordingId));
    if (existing != null) {
      if (!existing.envelopeDigest().equals(digest)) {
        return receipt(
            envelope,
            digest,
            ReceiptPhase.VALIDATION,
            Outcome.DENIED,
            "IDEMPOTENCY_CONFLICT",
            false,
            List.of());
      }
      Receipt terminal = existing.terminal();
      return terminal != null
          ? terminal
          : receipt(envelope, digest, ReceiptPhase.DISPATCH, Outcome.HELD, null, false, List.of());
    }

    evidenceStore.begin(observation, decision, envelope, authority);

    String bindingFailure = decisionBindingFailure(observation, decision, envelope);
    if (bindingFailure != null) {
      Receipt denied =
          receipt(
              envelope,
              digest,
              ReceiptPhase.VALIDATION,
              Outcome.DENIED,
              bindingFailure,
              false,
              List.of());
      requests.get(envelope.requestId()).terminal(denied);
      evidenceStore.append(recordingId, denied);
      return finalizeEvidence(envelope, digest, recordingId, denied);
    }
    if (!digest.equals(authority.envelopeDigest())
        || !envelope.authorityId().equals(authority.authorityId())) {
      Receipt denied =
          receipt(
              envelope,
              digest,
              ReceiptPhase.AUTHORIZATION,
              Outcome.DENIED,
              "AUTHORITY_SCOPE_MISMATCH",
              false,
              List.of());
      requests.get(envelope.requestId()).terminal(denied);
      evidenceStore.append(recordingId, denied);
      return finalizeEvidence(envelope, digest, recordingId, denied);
    }
    if (!authority.expiresAt().isAfter(clock.instant())
        || !envelope.deadline().isAfter(clock.instant())) {
      Receipt denied =
          receipt(
              envelope,
              digest,
              ReceiptPhase.AUTHORIZATION,
              Outcome.DENIED,
              "AUTHORITY_EXPIRED",
              false,
              List.of());
      requests.get(envelope.requestId()).terminal(denied);
      evidenceStore.append(recordingId, denied);
      return finalizeEvidence(envelope, digest, recordingId, denied);
    }

    Receipt authorized =
        receipt(
            envelope,
            digest,
            ReceiptPhase.AUTHORIZATION,
            Outcome.HELD,
            null,
            false,
            List.of(authority.authorityId()));
    evidenceStore.append(recordingId, authorized);
    try {
      AccountBuilderGameplayGateway.DispatchResult result = gateway.dispatch(envelope, authority);
      Outcome outcome = classify(result);
      boolean retryAllowed = !result.deliveryAcknowledged() && outcome == Outcome.FAILED;
      Receipt terminal =
          receipt(
              envelope,
              digest,
              terminalPhase(result),
              outcome,
              result.failureCode(),
              retryAllowed,
              result.evidenceRefs());
      evidenceStore.append(recordingId, terminal);
      requests.get(envelope.requestId()).terminal(terminal);
      return finalizeEvidence(envelope, digest, recordingId, terminal);
    } catch (RuntimeException exception) {
      Receipt uncertain =
          receipt(
              envelope,
              digest,
              ReceiptPhase.VERIFICATION,
              Outcome.UNCERTAIN_OUTCOME,
              "DELIVERY_RECONCILIATION_FAILED",
              false,
              List.of());
      evidenceStore.append(recordingId, uncertain);
      requests.get(envelope.requestId()).terminal(uncertain);
      return finalizeEvidence(envelope, digest, recordingId, uncertain);
    }
  }

  /**
   * Durable dispatch: the proposal binding and authority checks run before the durable claim, then
   * the SC-1431 service owns idempotency, claim, budget, authority, reservation, dispatch, status
   * verification and terminal evidence. The returned receipt is a read-only projection of the
   * durable view; no bootstrap state is mutated.
   */
  private Receipt dispatchThroughSc1431(
      JsonNode observation,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      HumanAuthority authority,
      String digest) {
    String bindingFailure = decisionBindingFailure(observation, decision, envelope);
    if (bindingFailure != null) {
      return receipt(
          envelope,
          digest,
          ReceiptPhase.VALIDATION,
          Outcome.DENIED,
          bindingFailure,
          false,
          List.of());
    }
    if (!digest.equals(authority.envelopeDigest())
        || !envelope.authorityId().equals(authority.authorityId())) {
      return receipt(
          envelope,
          digest,
          ReceiptPhase.AUTHORIZATION,
          Outcome.DENIED,
          "AUTHORITY_SCOPE_MISMATCH",
          false,
          List.of());
    }
    if (!authority.expiresAt().isAfter(clock.instant())
        || !envelope.deadline().isAfter(clock.instant())) {
      return receipt(
          envelope,
          digest,
          ReceiptPhase.AUTHORIZATION,
          Outcome.DENIED,
          "AUTHORITY_EXPIRED",
          false,
          List.of());
    }
    try {
      AccountBuilderGameplayGateway.DispatchResult result =
          orchestrateThroughSc1431(observation, decision, envelope, authority, digest);
      Outcome outcome = classify(result);
      boolean retryAllowed = !result.deliveryAcknowledged() && outcome == Outcome.FAILED;
      return receipt(
          envelope,
          digest,
          terminalPhase(result),
          outcome,
          result.failureCode(),
          retryAllowed,
          result.evidenceRefs());
    } catch (AccountBuilderOrchestrationException exception) {
      // Stable durable failure codes surface unchanged, including IDEMPOTENCY_CONFLICT.
      return receipt(
          envelope,
          digest,
          ReceiptPhase.VALIDATION,
          Outcome.DENIED,
          exception.failureCode().name(),
          false,
          List.of());
    } catch (RuntimeException exception) {
      return receipt(
          envelope,
          digest,
          ReceiptPhase.VERIFICATION,
          Outcome.UNCERTAIN_OUTCOME,
          "DELIVERY_RECONCILIATION_FAILED",
          false,
          List.of());
    }
  }

  /**
   * Builds exactly one durable {@code OrchestrationRequest} from the validated bootstrap proposal
   * and delegates authority ownership, reservation, dispatch, status verification and terminal
   * evidence to the SC-1431 orchestration service. The bootstrap path never opens a second mutable
   * task state: the returned view is the only lifecycle authority.
   */
  private AccountBuilderGameplayGateway.DispatchResult orchestrateThroughSc1431(
      JsonNode observation,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      HumanAuthority authority,
      String envelopeDigest) {
    // The immutable request and its full fingerprint come from the one shared factory, so the
    // bootstrap path and the validator can never disagree about request identity again.
    OrchestrationRequest request =
        requestFactory.fromBootstrap(observation, decision, envelope, authority);
    return toDispatchResult(orchestrationService.orchestrate(request).view());
  }

  /** Maps a durable SC-1431 view onto the bootstrap compatibility result without redispatch. */
  private AccountBuilderGameplayGateway.DispatchResult toDispatchResult(OrchestrationView view) {
    String failureCode =
        view.failureCode() == null || view.failureCode() == FailureCode.NONE
            ? null
            : view.failureCode().name();
    List<String> evidenceRefs =
        view.receipts().isEmpty()
            ? List.of()
            : view.receipts().get(view.receipts().size() - 1).evidenceRefs();
    return switch (view.state()) {
      case CONFIRMED -> new AccountBuilderGameplayGateway.DispatchResult(
          Outcome.CONFIRMED, failureCode, true, true, evidenceRefs);
      case REFUTED -> new AccountBuilderGameplayGateway.DispatchResult(
          Outcome.REFUTED, failureCode, true, true, evidenceRefs);
      case UNVERIFIABLE -> new AccountBuilderGameplayGateway.DispatchResult(
          Outcome.UNVERIFIABLE, failureCode, true, true, evidenceRefs);
      case STOPPED -> new AccountBuilderGameplayGateway.DispatchResult(
          Outcome.STOPPED, failureCode, true, true, evidenceRefs);
      case DENIED -> new AccountBuilderGameplayGateway.DispatchResult(
          Outcome.DENIED, failureCode, false, true, evidenceRefs);
      case FAILED -> new AccountBuilderGameplayGateway.DispatchResult(
          Outcome.FAILED, failureCode, !view.retryAllowed(), true, evidenceRefs);
      case NEEDS_RECONCILIATION -> new AccountBuilderGameplayGateway.DispatchResult(
          Outcome.UNCERTAIN_OUTCOME, failureCode, true, false, evidenceRefs);
      default -> new AccountBuilderGameplayGateway.DispatchResult(
          Outcome.UNCERTAIN_OUTCOME, failureCode, false, false, evidenceRefs);
    };
  }

  private static String sc1431OrchestrationId(String requestId) {
    return "bootstrap-" + requestId;
  }

  private String decisionBindingFailure(
      JsonNode observation, ProviderDecision decision, ExecutionEnvelope envelope) {
    JsonNode action = decision.semanticAction();
    if (!GameplayContracts.VERSION.equals(decision.contractVersion())
        || decision.provider() != envelope.provider()
        || !decision.modelVersion().equals(envelope.modelVersion())
        || !decision.decisionId().equals(envelope.decisionId())) {
      return "DECISION_IDENTITY_MISMATCH";
    }
    if (!"START_FISHING_TASK".equals(action.path("kind").asText())
        || !envelope.taskKey().equals(action.path("taskKey").asText())
        || !envelope.settings().equals(action.path("settings"))
        || !envelope.expectedTransition().equals(action.path("expectedTransition").asText())
        || !envelope.verifierId().equals(action.path("verifierId").asText())
        || !envelope.semanticActionDigest().equals(canonicalJson.digest(action))) {
      return "ACTION_ENVELOPE_MISMATCH";
    }
    if (envelope.expectedObservationRevision() != observation.path("revision").asLong(-1)
        || envelope.expectedTaskRevision()
            != observation.path("taskState").path("revision").asLong(-1)) {
      return "REVISION_MISMATCH";
    }
    if (!envelope.identities().equals(observation.path("identities"))
        || !envelope.catalogDigest().equals(observation.path("catalogDigest").asText())
        || !envelope.buildId().equals(observation.path("buildId").asText())) {
      return "IDENTITY_MISMATCH";
    }
    return null;
  }

  private Receipt finalizeEvidence(
      ExecutionEnvelope envelope, String digest, String recordingId, Receipt terminal) {
    try {
      Receipt finalization =
          receipt(
              envelope,
              digest,
              ReceiptPhase.FINALIZATION,
              terminal.outcome(),
              terminal.failureCode(),
              terminal.retryAllowed(),
              terminal.evidenceRefs());
      evidenceStore.append(recordingId, finalization);
      evidenceStore.finalizeAtomically(recordingId);
      requests.get(envelope.requestId()).terminal(finalization);
      return finalization;
    } catch (RuntimeException exception) {
      Receipt uncertain =
          receipt(
              envelope,
              digest,
              ReceiptPhase.FINALIZATION,
              Outcome.UNCERTAIN_OUTCOME,
              "EVIDENCE_FINALIZATION_FAILED",
              false,
              List.of());
      requests.get(envelope.requestId()).terminal(uncertain);
      return uncertain;
    }
  }

  private Outcome classify(AccountBuilderGameplayGateway.DispatchResult result) {
    if (result.outcome() == Outcome.UNCERTAIN_OUTCOME
        || (result.deliveryAcknowledged() && !result.terminalReconciled())) {
      return Outcome.UNCERTAIN_OUTCOME;
    }
    return result.outcome();
  }

  private ReceiptPhase terminalPhase(AccountBuilderGameplayGateway.DispatchResult result) {
    return result.terminalReconciled() ? ReceiptPhase.VERIFICATION : ReceiptPhase.DISPATCH;
  }

  private Receipt receipt(
      ExecutionEnvelope envelope,
      String digest,
      ReceiptPhase phase,
      Outcome outcome,
      String failureCode,
      boolean retryAllowed,
      List<String> evidenceRefs) {
    return new Receipt(
        GameplayContracts.VERSION,
        "receipt_" + UUID.randomUUID().toString().replace("-", ""),
        envelope.requestId(),
        digest,
        phase,
        outcome,
        failureCode,
        Instant.now(clock),
        evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs),
        retryAllowed);
  }

  private static final class RequestState {
    private final String envelopeDigest;
    private final String recordingId;
    private volatile Receipt terminal;

    private RequestState(String envelopeDigest, String recordingId) {
      this.envelopeDigest = envelopeDigest;
      this.recordingId = recordingId;
    }

    private String envelopeDigest() {
      return envelopeDigest;
    }

    private Receipt terminal() {
      return terminal;
    }

    private void terminal(Receipt receipt) {
      this.terminal = receipt;
    }
  }

  private record RequestIdentity(String requestId, String envelopeDigest) {}

  public static final class GameplayProposalException extends IllegalArgumentException {
    private final String failureCode;

    public GameplayProposalException(String failureCode, String message) {
      super(message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}

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
  private final Clock clock;
  private final Map<String, RequestState> requests = new ConcurrentHashMap<>();
  private final Map<String, RequestIdentity> idempotencyOwners = new ConcurrentHashMap<>();

  public BootstrapGameplayOrchestrator(
      GameplayContractValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderGameplayGateway gateway,
      BootstrapEvidenceStore evidenceStore) {
    this(validator, canonicalJson, gateway, evidenceStore, Clock.systemUTC());
  }

  BootstrapGameplayOrchestrator(
      GameplayContractValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderGameplayGateway gateway,
      BootstrapEvidenceStore evidenceStore,
      Clock clock) {
    this.validator = validator;
    this.canonicalJson = canonicalJson;
    this.gateway = gateway;
    this.evidenceStore = evidenceStore;
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

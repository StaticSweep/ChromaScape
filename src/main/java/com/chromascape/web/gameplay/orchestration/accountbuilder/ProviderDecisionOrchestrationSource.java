package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.ProviderOrchestrationCommand;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff.PersistedDecision;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Read-only SC-1430 handoff. It binds a persisted terminal {@code DECIDED} turn to exactly one
 * orchestration request and verifies every persisted provenance digest before the request can be
 * claimed. It has no gateway or authority capability and never mutates provider runtime state.
 *
 * <p>{@link #bindProduction} is the production entry point: the provider decision, its provenance
 * and the source type are always loaded from the durable persisted turn, never taken from caller
 * input.
 */
@Component
public final class ProviderDecisionOrchestrationSource {
  private static final String DIGEST_PATTERN = "sha256:[0-9a-f]{64}";

  private final ProviderDecisionHandoff handoff;
  private final CanonicalGameplayJson canonicalJson;
  private final AccountBuilderOrchestrationRequestFactory requestFactory;
  private final AccountBuilderOrchestrationValidator validator;

  public ProviderDecisionOrchestrationSource(
      ProviderDecisionHandoff handoff,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderOrchestrationRequestFactory requestFactory,
      AccountBuilderOrchestrationValidator validator) {
    this.handoff = Objects.requireNonNull(handoff);
    this.canonicalJson = Objects.requireNonNull(canonicalJson);
    this.requestFactory = Objects.requireNonNull(requestFactory);
    this.validator = Objects.requireNonNull(validator);
  }

  public OrchestrationRequest bind(
      String providerSessionId, String providerTurnId, OrchestrationRequest request) {
    PersistedDecision persisted =
        handoff
            .findValidatedDecision(providerSessionId, providerTurnId)
            .orElseThrow(
                () ->
                    new AccountBuilderOrchestrationException(
                        FailureCode.INVALID_CONTRACT,
                        "Validated persisted provider decision was not found"));
    if (!persisted.bindable()) {
      // ABSTAINED, FAILED, CANCELLED, NEEDS_RECONCILIATION and every other nonterminal,
      // non-decided, or provenance-incomplete turn can never be bound to an orchestration request.
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "Persisted provider turn is not a proven DECIDED turn with complete provenance");
    }
    if (persisted.transcriptRef() == null
        || persisted.transcriptSha256() == null
        || !persisted.transcriptRef().equals(persisted.transcriptSha256())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "Persisted provider transcript artifact is missing or tampered");
    }
    if (!persisted.sessionId().equals(providerSessionId)
        || !persisted.turnId().equals(providerTurnId)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted provider session or turn identity changed");
    }
    return bindProvenance(persisted, request);
  }

  /**
   * The immutable inputs of a provider orchestration before the capability digest exists. It is
   * sufficient to resolve an exact replay (finalized with the persisted capability digest) and to
   * finalize a new request (finalized with a fresh preflight). The draft carries no capability
   * binding and no fingerprint yet.
   */
  public record PreparedProviderRequest(OrchestrationRequest draft, PersistedDecision persisted) {
    public PreparedProviderRequest {
      Objects.requireNonNull(draft, "draft");
      Objects.requireNonNull(persisted, "persisted");
    }
  }

  /**
   * Stage one of the production provider-orchestration path. It validates the command shape, loads
   * the persisted terminal decision through the durable SC-1430 handoff, verifies every provenance
   * digest and returns a capability-free prepared request. It never contacts Micro6, so an invalid
   * provider decision can never trigger a gateway call.
   */
  public PreparedProviderRequest prepareProduction(ProviderOrchestrationCommand command) {
    Objects.requireNonNull(command, "command");
    String sessionId = command.providerSessionId();
    String turnId = command.providerTurnId();
    if (sessionId == null
        || sessionId.isBlank()
        || turnId == null
        || turnId.isBlank()
        || command.orchestrationId() == null
        || command.orchestrationId().isBlank()
        || command.requestId() == null
        || command.requestId().isBlank()
        || command.idempotencyKey() == null
        || command.idempotencyKey().isBlank()
        || command.mode() == null
        || command.deadline() == null
        || command.envelope() == null
        || command.envelope().identities() == null) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "Provider session, turn, request identity and envelope identities are required");
    }
    PersistedDecision persisted =
        handoff
            .findValidatedDecision(sessionId, turnId)
            .orElseThrow(
                () ->
                    new AccountBuilderOrchestrationException(
                        FailureCode.INVALID_CONTRACT,
                        "Validated persisted provider decision was not found"));
    if (!persisted.bindable()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "Persisted provider turn is not a proven DECIDED turn with complete provenance");
    }
    if (persisted.transcriptRef() == null
        || persisted.transcriptSha256() == null
        || !persisted.transcriptRef().equals(persisted.transcriptSha256())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "Persisted provider transcript artifact is missing or tampered");
    }
    if (!persisted.sessionId().equals(sessionId) || !persisted.turnId().equals(turnId)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted provider session or turn identity changed");
    }
    // The source type is derived from the persisted provider only. Caller-supplied source types
    // are never accepted on this route.
    String sourceType = "PROVIDER:" + persisted.provider().name();
    SnapshotBindings bindings =
        new SnapshotBindings(
            persisted.decisionDigest(),
            persisted.semanticActionDigest(),
            command.envelope().catalogDigest(),
            null,
            persisted.goalPlanDigest(),
            persisted.taskSkillDigests(),
            persisted.toolsetDigest(),
            persisted.knowledgeDigest(),
            command.envelope().policyDigest(),
            command.envelope().verifierId(),
            command.envelope().buildId(),
            command.envelope().expectedObservationRevision(),
            command.envelope().expectedTaskRevision(),
            persisted.observationDigest(),
            persisted.promptDigest(),
            persisted.sessionId(),
            persisted.turnId());
    OrchestrationRequest draft =
        new OrchestrationRequest(
            command.orchestrationId(),
            command.requestId(),
            command.idempotencyKey(),
            null,
            command.mode(),
            sourceType,
            persisted.decision(),
            command.envelope(),
            bindings,
            command.authority(),
            command.observation(),
            command.deadline(),
            command.frozenBundle());
    // Every persisted provenance digest is re-verified against the draft before any capability
    // access or durable claim. The full immutable invariant set (request identity, deadline,
    // authority shape, decision/envelope binding) is then validated without the capability digest
    // or final fingerprint, so a malformed command can never cause a Micro6 preflight.
    OrchestrationRequest bound = bindProvenance(persisted, draft);
    validator.validatePrepared(bound);
    return new PreparedProviderRequest(bound, persisted);
  }

  /**
   * Stage two for a new orchestration: binds the fresh server-issued capability digest and computes
   * the full immutable fingerprint. The caller must have performed the capability preflight first.
   */
  public OrchestrationRequest completeProduction(
      PreparedProviderRequest prepared, CapabilitySnapshot capabilityPreflight) {
    Objects.requireNonNull(prepared, "prepared");
    return requestFactory.bindCapability(
        prepared.draft(), capabilityDigest(prepared.draft().envelope(), capabilityPreflight));
  }

  /**
   * Stage two for an exact replay: finalizes the prepared request with the already persisted
   * capability digest and computes the normal full immutable fingerprint without any capability
   * access. The caller compares the fingerprint against the stored one.
   */
  public OrchestrationRequest completeProductionReplay(
      PreparedProviderRequest prepared, String storedCapabilityDigest) {
    Objects.requireNonNull(prepared, "prepared");
    if (storedCapabilityDigest == null || !storedCapabilityDigest.matches(DIGEST_PATTERN)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT, "Persisted capability digest is missing or malformed");
    }
    return requestFactory.bindCapability(prepared.draft(), storedCapabilityDigest);
  }

  /**
   * Convenience composition of both stages. Production callers use the separated stages so the
   * capability preflight happens only after the persisted decision and replay are resolved.
   */
  public OrchestrationRequest bindProduction(
      ProviderOrchestrationCommand command, CapabilitySnapshot capabilityPreflight) {
    return completeProduction(prepareProduction(command), capabilityPreflight);
  }

  /** Binds only a server-issued capability digest; the catalog digest is never substituted. */
  private String capabilityDigest(ExecutionEnvelope envelope, CapabilitySnapshot preflight) {
    if (preflight == null
        || preflight.capabilityDigest() == null
        || !preflight.capabilityDigest().matches(DIGEST_PATTERN)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.GATEWAY_UNAVAILABLE,
          "Capability preflight did not return a server-issued capability digest");
    }
    if (preflight.liveDeliveryReady()) {
      if (!Objects.equals(preflight.catalogDigest(), envelope.catalogDigest())
          || !Objects.equals(preflight.buildId(), envelope.buildId())) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.STALE_BINDING,
            "Capability preflight catalog or build identity does not match the request");
      }
      if (preflight.observationRevision() != envelope.expectedObservationRevision()
          || preflight.taskRevision() != envelope.expectedTaskRevision()) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.REVISION_MISMATCH,
            "Capability preflight observation or task revision drifted from the request");
      }
    }
    return preflight.capabilityDigest();
  }

  private OrchestrationRequest bindProvenance(
      PersistedDecision persisted, OrchestrationRequest request) {
    ExecutionEnvelope envelope = request.envelope();
    if (!persisted.requestId().equals(envelope.requestId())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted provider request id does not match the envelope");
    }
    if (!Objects.equals(envelope.provider(), persisted.provider())
        || !Objects.equals(envelope.modelVersion(), persisted.modelId())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted provider or model version does not match");
    }
    String expectedSourceType = "PROVIDER:" + persisted.provider().name();
    if (!expectedSourceType.equals(request.sourceType())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Orchestration source type does not match the provider");
    }

    SnapshotBindings bindings = request.bindings();
    if (!persisted.decisionDigest().equals(bindings.decisionDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted decision digest does not match the binding");
    }
    if (!persisted.semanticActionDigest().equals(bindings.semanticActionDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted semantic action digest does not match the binding");
    }
    // Provider session and turn identity is part of the immutable binding. Two distinct durable
    // provider turns that produced identical decision and provenance content must never collapse
    // into the same exact replay.
    if (!Objects.equals(persisted.sessionId(), bindings.providerSessionId())
        || !Objects.equals(persisted.turnId(), bindings.providerTurnId())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING,
          "Persisted provider session or turn identity does not match the binding");
    }
    // Every contract-mandated digest comparison is unconditional: a persisted record that is
    // missing any required provenance value fails closed and can never become deliverable.
    if (!persisted.observationDigest().equals(canonicalJson.digest(request.observation()))) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted observation digest does not match the request");
    }
    if (!persisted.toolsetDigest().equals(bindings.toolSchemaDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted tool schema digest does not match the binding");
    }
    if (!persisted.knowledgeDigest().equals(envelope.knowledgeDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted knowledge digest does not match the envelope");
    }
    if (!persisted.goalPlanDigest().equals(envelope.goalPlanDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted goal plan digest does not match the envelope");
    }
    if (persisted.taskSkillDigests().isEmpty()
        || !persisted.taskSkillDigests().equals(envelope.taskSkillDigests())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted task skill digests do not match the envelope");
    }
    if (bindings.observationDigest() == null
        || bindings.promptDigest() == null
        || !persisted.observationDigest().equals(bindings.observationDigest())
        || !persisted.promptDigest().equals(bindings.promptDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING,
          "Orchestration binding is missing the persisted observation or prompt provenance");
    }

    return new OrchestrationRequest(
        request.orchestrationId(),
        request.requestId(),
        request.idempotencyKey(),
        request.requestFingerprint(),
        request.mode(),
        expectedSourceType,
        persisted.decision(),
        envelope,
        bindings,
        request.authority(),
        request.observation(),
        request.deadline(),
        request.frozenBundle());
  }
}

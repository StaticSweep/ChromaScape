package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff.PersistedDecision;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Read-only SC-1430 handoff. It binds a persisted terminal {@code DECIDED} turn to exactly one
 * orchestration request and verifies every persisted provenance digest before the request can be
 * claimed. It has no gateway or authority capability and never mutates provider runtime state.
 */
@Component
public final class ProviderDecisionOrchestrationSource {
  private final ProviderDecisionHandoff handoff;
  private final CanonicalGameplayJson canonicalJson;

  public ProviderDecisionOrchestrationSource(
      ProviderDecisionHandoff handoff, CanonicalGameplayJson canonicalJson) {
    this.handoff = Objects.requireNonNull(handoff);
    this.canonicalJson = Objects.requireNonNull(canonicalJson);
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
      // ABSTAINED, FAILED, CANCELLED, NEEDS_RECONCILIATION and every other nonterminal or
      // non-decided turn can never be bound to an orchestration request.
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "Persisted provider turn is not a proven DECIDED turn with normalized evidence");
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
    // The persisted prompt/input digest must still describe the exact observation carried by the
    // request; any divergence means the planning input changed after the decision was made.
    if (persisted.observationDigest() != null
        && !persisted.observationDigest().equals(canonicalJson.digest(request.observation()))) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted observation digest does not match the request");
    }
    if (persisted.toolsetDigest() != null
        && !persisted.toolsetDigest().equals(bindings.toolSchemaDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted tool schema digest does not match the binding");
    }
    if (persisted.knowledgeDigest() != null
        && !persisted.knowledgeDigest().equals(envelope.knowledgeDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted knowledge digest does not match the envelope");
    }
    if (persisted.goalPlanDigest() != null
        && !persisted.goalPlanDigest().equals(envelope.goalPlanDigest())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted goal plan digest does not match the envelope");
    }
    if (!persisted.taskSkillDigests().isEmpty()
        && !persisted.taskSkillDigests().equals(envelope.taskSkillDigests())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted task skill digests do not match the envelope");
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
        request.deadline());
  }
}

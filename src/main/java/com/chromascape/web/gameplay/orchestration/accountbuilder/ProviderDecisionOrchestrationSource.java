package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff;
import org.springframework.stereotype.Component;

/** Read-only SC-1430 handoff. It can bind a decision but has no gateway or authority capability. */
@Component
public final class ProviderDecisionOrchestrationSource {
  private final ProviderDecisionHandoff handoff;

  public ProviderDecisionOrchestrationSource(ProviderDecisionHandoff handoff) {
    this.handoff = handoff;
  }

  public OrchestrationRequest bind(
      String providerSessionId, String providerTurnId, OrchestrationRequest request) {
    ProviderDecisionHandoff.PersistedDecision persisted =
        handoff
            .findValidatedDecision(providerSessionId, providerTurnId)
            .orElseThrow(
                () ->
                    new AccountBuilderOrchestrationException(
                        FailureCode.INVALID_CONTRACT,
                        "Validated persisted provider decision was not found"));
    if (!persisted.decisionDigest().equals(request.bindings().decisionDigest())
        || !persisted.semanticActionDigest().equals(request.bindings().semanticActionDigest())
        || !persisted.requestId().equals(request.envelope().requestId())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Persisted provider decision binding changed");
    }
    return new OrchestrationRequest(
        request.orchestrationId(),
        request.requestId(),
        request.idempotencyKey(),
        request.requestFingerprint(),
        request.mode(),
        "PROVIDER:" + persisted.decision().provider().name(),
        persisted.decision(),
        request.envelope(),
        request.bindings(),
        request.authority(),
        request.observation(),
        request.deadline());
  }
}

package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import java.util.Optional;

/** Read-only downstream boundary; it exposes decisions but no authority or delivery capability. */
public interface ProviderDecisionHandoff {
  Optional<PersistedDecision> findValidatedDecision(String sessionId, String turnId);

  record PersistedDecision(
      String sessionId,
      String turnId,
      String requestId,
      String decisionDigest,
      String semanticActionDigest,
      ProviderDecision decision,
      String transcriptRef) {}
}

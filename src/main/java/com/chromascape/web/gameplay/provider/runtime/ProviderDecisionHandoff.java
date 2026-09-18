package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import java.util.List;
import java.util.Optional;

/** Read-only downstream boundary; it exposes decisions but no authority or delivery capability. */
public interface ProviderDecisionHandoff {
  /**
   * Returns the full durable provenance of a persisted, terminal {@code DECIDED} turn. The lookup
   * is read-only; it never mutates runtime state and never exposes secrets or raw input.
   */
  Optional<PersistedDecision> findValidatedDecision(String sessionId, String turnId);

  /**
   * Complete immutable provenance of one persisted provider decision. Every digest is the canonical
   * digest of the exact bytes that were validated and persisted; the transcript reference doubles
   * as the transcript content hash of the redacted artifact.
   */
  record PersistedDecision(
      String sessionId,
      String turnId,
      String requestId,
      Provider provider,
      String modelId,
      String decisionDigest,
      String semanticActionDigest,
      String observationDigest,
      String promptDigest,
      String toolsetDigest,
      String knowledgeDigest,
      String goalPlanDigest,
      List<String> taskSkillDigests,
      String transcriptRef,
      String transcriptSha256,
      TurnState turnState,
      FailureCode normalizationResult,
      ProviderDecision decision) {

    public PersistedDecision {
      taskSkillDigests = taskSkillDigests == null ? List.of() : List.copyOf(taskSkillDigests);
    }

    /** Only a proven DECIDED turn with a normalized decision may be bound downstream. */
    public boolean bindable() {
      return turnState == TurnState.DECIDED
          && decision != null
          && decisionDigest != null
          && semanticActionDigest != null
          && transcriptRef != null;
    }
  }
}

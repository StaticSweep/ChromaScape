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
   * Returns the verified, credential-redacted provider transcript for a persisted turn.
   *
   * <p>The bytes are re-hashed against the recorded reference before they are returned, so the text
   * is provably the artifact that was persisted. It is the only durable record of what the provider
   * actually emitted: the normalised decision is a projection of it, and a v2 decision can only be
   * derived from persisted provider output. The lookup is read-only and never mutates runtime state.
   */
  default Optional<String> findProviderTranscript(String sessionId, String turnId) {
    // Defaulted so the handoff stays a functional interface for read-only test doubles. A
    // production implementation must override it; an absent transcript fails closed upstream.
    return Optional.empty();
  }

  /**
   * Returns the persisted SC-1429/v2 decision for a proven {@code DECIDED} turn.
   *
   * <p>The decision is the exact provider bytes that were validated and stored, never a projection
   * of them. It is the only legitimate source for a v2 draft: a v1 decision carries no GoalPlan and
   * cannot be relabelled. Defaulted to empty so read-only test doubles stay functional; the
   * production runtime overrides it.
   */
  default Optional<PersistedV2Decision> findValidatedV2Decision(String sessionId, String turnId) {
    return Optional.empty();
  }

  /**
   * Complete provenance of one persisted v2 decision. Every digest is the canonical digest of the
   * exact bytes that were validated and persisted.
   */
  record PersistedV2Decision(
      String sessionId,
      String turnId,
      String requestId,
      String provider,
      String modelId,
      String decisionDigest,
      String semanticActionDigest,
      String goalPlanDigest,
      String eligibleInputDigest,
      String transcriptRef,
      String transcriptSha256,
      com.fasterxml.jackson.databind.JsonNode decision) {}

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

    /**
     * Only a proven {@code DECIDED} turn with successful normalization and every contract-mandated
     * provenance digest may be bound downstream. Older records missing required provenance fail
     * closed and can never become deliverable.
     */
    public boolean bindable() {
      return turnState == TurnState.DECIDED
          && normalizationResult == FailureCode.NONE
          && decision != null
          && isId(sessionId)
          && isId(turnId)
          && isId(requestId)
          && provider != null
          && isId(modelId)
          && isDigest(decisionDigest)
          && isDigest(semanticActionDigest)
          && isDigest(observationDigest)
          && isDigest(promptDigest)
          && isDigest(toolsetDigest)
          && isDigest(knowledgeDigest)
          && isDigest(goalPlanDigest)
          && !taskSkillDigests.isEmpty()
          && taskSkillDigests.stream().allMatch(PersistedDecision::isDigest)
          && isDigest(transcriptRef)
          && isDigest(transcriptSha256)
          && transcriptRef.equals(transcriptSha256);
    }

    private static boolean isId(String value) {
      return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private static boolean isDigest(String value) {
      return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
  }
}

package com.chromascape.web.gameplay.contract.v2;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Provider-neutral SC-1429 v2 contract types. Providers propose these values but cannot execute
 * them. The frozen sc-1429/v1 types in {@code com.chromascape.web.gameplay.contract} remain
 * untouched and byte-identical.
 */
public final class GameplayV2Contracts {
  public static final String VERSION = "sc-1429/v2";

  private GameplayV2Contracts() {}

  public enum ExecutionMode {
    SIMULATION,
    SHADOW,
    LIVE_TASK
  }

  public enum Provider {
    CLAUDE,
    CODEX,
    MISTRAL
  }

  public enum TaskSkillMaturity {
    CANDIDATE,
    VALIDATED,
    TRUSTED
  }

  public enum EligibilityStatus {
    ELIGIBLE,
    NO_ELIGIBLE_TASK,
    STALE_OBSERVATION,
    STALE_KNOWLEDGE,
    UNSUPPORTED_CAPABILITY,
    PREREQUISITE_UNMET,
    POLICY_DENIED,
    BUDGET_EXHAUSTED,
    IDENTITY_MISMATCH
  }

  public enum DecisionKind {
    SELECTED,
    ABSTAINED
  }

  public enum AbstentionReason {
    NO_ELIGIBLE_TASK,
    STALE_OBSERVATION,
    STALE_KNOWLEDGE,
    UNSUPPORTED_CAPABILITY,
    PREREQUISITE_UNMET,
    POLICY_DENIED,
    BUDGET_EXHAUSTED,
    IDENTITY_MISMATCH
  }

  public enum ToolOperation {
    CATALOG,
    RESERVE,
    START,
    STATUS,
    STOP,
    OBSERVE
  }

  public enum ReceiptPhase {
    VALIDATION,
    AUTHORIZATION,
    RESERVATION,
    DISPATCH,
    ACTIVATION,
    PROGRESS,
    TASK_TERMINAL,
    VERIFICATION,
    FINALIZATION
  }

  public enum Outcome {
    HELD,
    DELIVERED,
    CONFIRMED,
    REFUTED,
    UNVERIFIABLE,
    UNCERTAIN_OUTCOME,
    ABSTAINED,
    DENIED,
    FAILED,
    STOPPED
  }

  public record EligibilityEntry(String taskKey, EligibilityStatus status) {}

  /**
   * Normalized provider decision. Claude and Codex may differ only in provider, model version, and
   * prompt provenance; every other field must normalize to the same provider-neutral value.
   * ABSTAINED decisions carry no goalPlan and preserve the closed abstention reason.
   */
  public record ProviderDecision(
      String contractVersion,
      DecisionKind kind,
      Provider provider,
      String modelVersion,
      String decisionId,
      String promptId,
      double policyConfidence,
      double adapterObservationConfidence,
      double knowledgeFreshnessConfidence,
      String eligibleInputDigest,
      JsonNode goalPlan,
      AbstentionReason abstentionReason,
      List<String> evidenceRefs) {}
}

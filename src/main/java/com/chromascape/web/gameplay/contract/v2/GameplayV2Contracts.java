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

  /** Closed planning and execution modes. */
  public enum ExecutionMode {
    SIMULATION,
    SHADOW,
    LIVE_TASK
  }

  /** Supported provider identities. */
  public enum Provider {
    CLAUDE,
    CODEX
  }

  /** Closed maturity levels for immutable TaskSkill references. */
  public enum TaskSkillMaturity {
    CANDIDATE,
    VALIDATED,
    TRUSTED
  }

  /** Deterministic task eligibility result and rejection reasons. */
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

  /** Closed provider decision variants. */
  public enum DecisionKind {
    SELECTED,
    ABSTAINED
  }

  /** Stable reasons for provider abstention. */
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

  /** Complete semantic tool surface exposed by this contract. */
  public enum ToolOperation {
    CATALOG,
    RESERVE,
    START,
    STATUS,
    STOP,
    OBSERVE
  }

  /** Ordered lifecycle phases represented by receipts. */
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

  /** Closed lifecycle outcomes. */
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

  /** Eligibility status for one catalog task. */
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

package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Current-version, decide-only domain contracts for SC-1430. */
public final class ProviderRuntimeModels {
  public static final String VERSION = "sc-1430/v1";

  private ProviderRuntimeModels() {}

  public enum SessionState {
    CREATED,
    ACTIVE,
    COMPLETED,
    CANCELLED,
    FAILED,
    NEEDS_RECONCILIATION
  }

  public enum TurnState {
    CREATED,
    RESERVED,
    SUBMITTED,
    STREAMING,
    NORMALIZING,
    DECIDED,
    ABSTAINED,
    INVALID_OUTPUT,
    TIMEOUT,
    BUDGET_EXHAUSTED,
    CANCELLED,
    PROVIDER_UNAVAILABLE,
    NEEDS_RECONCILIATION,
    INTERNAL_FAILURE;

    public boolean terminal() {
      return switch (this) {
        case DECIDED,
            ABSTAINED,
            INVALID_OUTPUT,
            TIMEOUT,
            BUDGET_EXHAUSTED,
            CANCELLED,
            PROVIDER_UNAVAILABLE,
            NEEDS_RECONCILIATION,
            INTERNAL_FAILURE -> true;
        default -> false;
      };
    }

    public boolean submitted() {
      return switch (this) {
        case SUBMITTED, STREAMING, NORMALIZING -> true;
        default -> false;
      };
    }
  }

  public enum FailureCode {
    NONE,
    RUNTIME_DISABLED,
    PROVIDER_UNAVAILABLE,
    INVALID_CONTRACT,
    INVALID_OUTPUT,
    MULTIPLE_DECISIONS,
    UNSUPPORTED_ACTION,
    INVALID_EVIDENCE_REFERENCE,
    IDEMPOTENCY_CONFLICT,
    BUDGET_EXHAUSTED,
    DEADLINE_EXPIRED,
    PROCESS_START_FAILED,
    PROCESS_START_TIMEOUT,
    FIRST_EVENT_TIMEOUT,
    TOOL_RESPONSE_TIMEOUT,
    PROCESS_TIMEOUT_BEFORE_SUBMISSION,
    UNKNOWN_PROVIDER_OUTCOME,
    NEEDS_RECONCILIATION,
    CANCELLED,
    TOOL_NOT_ALLOWED,
    TOOL_ARGUMENT_MISMATCH,
    TRANSCRIPT_PERSISTENCE_FAILED,
    TRANSCRIPT_ARTIFACT_MISSING,
    TRANSCRIPT_ARTIFACT_UNREADABLE,
    TRANSCRIPT_ARTIFACT_TOO_LARGE,
    TRANSCRIPT_DIGEST_MISMATCH,
    INTERNAL_FAILURE
  }

  public record BudgetLimits(
      long maxInputTokens,
      long maxOutputTokens,
      int maxProviderCalls,
      int maxToolCalls,
      int maxTurns,
      long maxTurnMillis,
      long maxSessionMillis) {
    public BudgetLimits {
      if (maxInputTokens <= 0
          || maxOutputTokens <= 0
          || maxProviderCalls <= 0
          || maxToolCalls < 0
          || maxTurns <= 0
          || maxTurnMillis <= 0
          || maxSessionMillis <= 0
          || maxTurnMillis > maxSessionMillis) {
        throw new IllegalArgumentException("Provider budgets must be positive and bounded");
      }
    }

    public Duration maxTurnDuration() {
      return Duration.ofMillis(maxTurnMillis);
    }
  }

  public record BudgetUsage(
      long inputTokens,
      long outputTokens,
      int providerCalls,
      int toolCalls,
      int turns,
      long elapsedMillis) {
    public static BudgetUsage zero() {
      return new BudgetUsage(0, 0, 0, 0, 0, 0);
    }
  }

  public record SessionRequest(
      String sessionId,
      Provider provider,
      String modelId,
      String taskId,
      String runId,
      String recordingId,
      String promptDigest,
      String toolsetDigest,
      String knowledgeDigest,
      String goalPlanDigest,
      List<String> taskSkillDigests,
      Instant deadline,
      BudgetLimits budgets,
      boolean decideOnly,
      String gameplayContractVersion) {

    public SessionRequest {
      gameplayContractVersion =
          gameplayContractVersion == null || gameplayContractVersion.isBlank()
              ? "sc-1429/v1"
              : gameplayContractVersion;
    }

    /** Legacy fourteen-argument form: a v1 session. */
    public SessionRequest(
        String sessionId,
        Provider provider,
        String modelId,
        String taskId,
        String runId,
        String recordingId,
        String promptDigest,
        String toolsetDigest,
        String knowledgeDigest,
        String goalPlanDigest,
        List<String> taskSkillDigests,
        Instant deadline,
        BudgetLimits budgets,
        boolean decideOnly) {
      this(
          sessionId,
          provider,
          modelId,
          taskId,
          runId,
          recordingId,
          promptDigest,
          toolsetDigest,
          knowledgeDigest,
          goalPlanDigest,
          taskSkillDigests,
          deadline,
          budgets,
          decideOnly,
          "sc-1429/v1");
    }

    /** True when this session must be asked for an SC-1429/v2 decision. */
    public boolean v2() {
      return "sc-1429/v2".equals(gameplayContractVersion);
    }
  }

  public record TurnRequest(
      String turnId,
      String requestId,
      String idempotencyKey,
      String requestFingerprint,
      JsonNode observation,
      String observationDigest,
      JsonNode eligibleActions,
      String eligibilityDigest,
      JsonNode identities,
      String sourceId,
      long estimatedInputTokens,
      long estimatedOutputTokens,
      Instant deadline,
      boolean decideOnly) {}

  public record ProviderUsage(
      Long inputTokens, Long outputTokens, int toolCalls, long elapsedMillis, boolean exact) {
    public ProviderUsage {
      if ((inputTokens != null && inputTokens < 0)
          || (outputTokens != null && outputTokens < 0)
          || toolCalls < 0
          || elapsedMillis < 0) {
        throw new IllegalArgumentException("Provider usage cannot be negative");
      }
    }
  }

  public record SessionView(
      String contractVersion,
      String sessionId,
      Provider provider,
      String modelId,
      SessionState state,
      String taskId,
      String runId,
      String recordingId,
      String promptDigest,
      String toolsetDigest,
      String knowledgeDigest,
      String goalPlanDigest,
      List<String> taskSkillDigests,
      Instant deadline,
      BudgetLimits budgets,
      BudgetUsage usage,
      long version,
      Instant createdAt,
      Instant updatedAt,
      boolean decideOnly) {}

  public record TurnView(
      String contractVersion,
      String sessionId,
      String turnId,
      String requestId,
      String requestFingerprint,
      Provider provider,
      String modelId,
      TurnState state,
      FailureCode failureCode,
      String providerSessionRef,
      String providerTurnRef,
      String observationDigest,
      String eligibilityDigest,
      String decisionDigest,
      String semanticActionDigest,
      ProviderDecision decision,
      ProviderUsage usage,
      List<String> eventRefs,
      String transcriptRef,
      Instant createdAt,
      Instant updatedAt,
      long version,
      boolean decideOnly) {}

  public record Readiness(
      String contractVersion,
      boolean enabled,
      boolean operatorKeyConfigured,
      Map<Provider, ProviderReadiness> providers) {}

  /**
   * Raw persisted decision row. The v2 decision is exposed as the exact provider bytes plus its
   * digests; it is never projected into the v1 record type.
   */
  public record PersistedDecisionRow(
      String turnId,
      String contractVersion,
      String decisionId,
      String decisionDigest,
      String semanticActionDigest,
      String outcome,
      com.fasterxml.jackson.databind.JsonNode decision) {}

  public record ProviderReadiness(
      boolean available, String executable, String version, FailureCode failureCode) {}
}

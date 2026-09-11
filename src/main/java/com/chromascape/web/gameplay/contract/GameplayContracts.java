package com.chromascape.web.gameplay.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * Provider-neutral SC-1429 contract types. Providers can propose these values but cannot execute
 * them.
 */
public final class GameplayContracts {
  public static final String VERSION = "sc-1429/v1";

  private GameplayContracts() {}

  /** Supported provider identities. */
  public enum Provider {
    CLAUDE,
    CODEX
  }

  /** Closed semantic actions accepted from a provider. */
  public enum ActionKind {
    START_FISHING_TASK,
    ABSTAIN
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

  /** Provider-attributed semantic proposal with no execution authority. */
  public record ProviderDecision(
      String contractVersion,
      Provider provider,
      String modelVersion,
      String decisionId,
      double policyConfidence,
      JsonNode semanticAction,
      List<String> evidenceRefs) {}

  /** Immutable delivery boundary binding a decision to runtime identities and limits. */
  public record ExecutionEnvelope(
      String contractVersion,
      Provider provider,
      String modelVersion,
      String decisionId,
      String requestId,
      String idempotencyKey,
      String goalPlanDigest,
      List<String> taskSkillDigests,
      String taskKey,
      JsonNode settings,
      JsonNode identities,
      String catalogDigest,
      String schemaDigest,
      String policyDigest,
      String knowledgeDigest,
      String semanticActionDigest,
      String expectedTransition,
      String verifierId,
      String buildId,
      long expectedObservationRevision,
      long expectedTaskRevision,
      String authorityId,
      long leaseGeneration,
      long fence,
      JsonNode budgets,
      Instant deadline,
      String killSwitchScope) {}

  /** Time-bounded human authority bound to one exact execution envelope. */
  public record HumanAuthority(
      String contractVersion,
      String authorityId,
      String actorType,
      String approvedBy,
      Instant approvedAt,
      Instant expiresAt,
      String envelopeDigest,
      String scopeDigest,
      boolean oneUse,
      String signature) {}

  /** Evidence-bearing lifecycle receipt. */
  public record Receipt(
      String contractVersion,
      String receiptId,
      String requestId,
      String envelopeDigest,
      ReceiptPhase phase,
      Outcome outcome,
      String failureCode,
      Instant at,
      List<String> evidenceRefs,
      boolean retryAllowed) {}
}

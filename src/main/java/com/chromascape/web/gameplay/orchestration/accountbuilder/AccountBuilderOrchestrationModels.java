package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** Source-neutral SC-1431 orchestration contracts. None of these types exposes raw input. */
public final class AccountBuilderOrchestrationModels {
  public static final String VERSION = "sc-1431/v1";

  private AccountBuilderOrchestrationModels() {}

  public enum Mode {
    HELD,
    SHADOW,
    BOOTSTRAP_RECORDING,
    LIVE_TASK,
    SIMULATION;

    public boolean deliveryMode() {
      return this == BOOTSTRAP_RECORDING || this == LIVE_TASK;
    }
  }

  public enum State {
    CREATED,
    VALIDATED,
    HELD,
    AUTHORIZED,
    RESERVED,
    DISPATCH_SUBMITTED,
    ACTIVE,
    VERIFYING,
    CONFIRMED,
    REFUTED,
    UNVERIFIABLE,
    DENIED,
    STOPPED,
    FAILED,
    NEEDS_RECONCILIATION;

    public boolean terminal() {
      return switch (this) {
        case HELD,
            CONFIRMED,
            REFUTED,
            UNVERIFIABLE,
            DENIED,
            STOPPED,
            FAILED,
            NEEDS_RECONCILIATION -> true;
        default -> false;
      };
    }

    /** Terminal outcomes that produce an immutable, durable terminal-result row. */
    public boolean provenTerminal() {
      return switch (this) {
        case CONFIRMED, REFUTED, UNVERIFIABLE, DENIED, STOPPED, FAILED -> true;
        default -> false;
      };
    }

    /**
     * Returns whether a durable lifecycle transition is structurally valid.
     *
     * <p>The stores still validate the evidence attached to a transition. This method only protects
     * the lifecycle graph, so a caller cannot skip the persisted reservation or dispatch boundary
     * and then present a later receipt as if the skipped work had happened.
     */
    public boolean canTransitionTo(State next) {
      if (next == null || this == next) {
        return next != null;
      }
      return switch (this) {
        case CREATED -> next == VALIDATED || next == DENIED || next == NEEDS_RECONCILIATION;
        case VALIDATED -> next == HELD
            || next == AUTHORIZED
            || next == DENIED
            || next == NEEDS_RECONCILIATION;
        case AUTHORIZED -> next == RESERVED
            || next == DENIED
            || next == FAILED
            || next == NEEDS_RECONCILIATION;
        case RESERVED -> next == DISPATCH_SUBMITTED
            || next == STOPPED
            || next == FAILED
            || next == NEEDS_RECONCILIATION;
        case DISPATCH_SUBMITTED -> next == ACTIVE
            || next == VERIFYING
            || next == CONFIRMED
            || next == REFUTED
            || next == UNVERIFIABLE
            || next == DENIED
            || next == STOPPED
            || next == FAILED
            || next == NEEDS_RECONCILIATION;
        case ACTIVE -> next == VERIFYING
            || next == CONFIRMED
            || next == REFUTED
            || next == UNVERIFIABLE
            || next == STOPPED
            || next == FAILED
            || next == NEEDS_RECONCILIATION;
        case VERIFYING -> next == CONFIRMED
            || next == REFUTED
            || next == UNVERIFIABLE
            || next == STOPPED
            || next == FAILED
            || next == NEEDS_RECONCILIATION;
        case NEEDS_RECONCILIATION -> next == CONFIRMED
            || next == REFUTED
            || next == UNVERIFIABLE
            || next == DENIED
            || next == STOPPED
            || next == FAILED;
        case HELD, CONFIRMED, REFUTED, UNVERIFIABLE, DENIED, STOPPED, FAILED -> false;
      };
    }
  }

  public enum FailureCode {
    NONE,
    OPERATOR_UNAUTHORIZED,
    RUNTIME_DISABLED,
    INVALID_CONTRACT,
    IDEMPOTENCY_CONFLICT,
    MODE_NOT_DELIVERABLE,
    TASK_INELIGIBLE,
    STALE_BINDING,
    REVISION_MISMATCH,
    DEADLINE_EXPIRED,
    BUDGET_EXHAUSTED,
    AUTHORITY_INVALID,
    AUTHORITY_EXPIRED,
    AUTHORITY_ALREADY_USED,
    GATEWAY_UNAVAILABLE,
    RESERVATION_REJECTED,
    LEASE_EXPIRED,
    FENCE_MISMATCH,
    DISPATCH_REJECTED,
    DELIVERY_UNCERTAIN,
    STOP_REJECTED,
    NEEDS_RECONCILIATION,
    BOUNDARY_VIOLATION,
    INVALID_GATEWAY_RECEIPT,
    INTERNAL_FAILURE
  }

  public record TaskCapability(
      String taskKey,
      boolean eligible,
      String schemaDigest,
      String minimumBuildId,
      List<String> denialReasons) {
    public TaskCapability {
      denialReasons = denialReasons == null ? List.of() : List.copyOf(denialReasons);
    }
  }

  public record CapabilitySnapshot(
      String catalogDigest,
      String capabilityDigest,
      String buildId,
      long observationRevision,
      long taskRevision,
      Instant observedAt,
      Instant expiresAt,
      boolean liveDeliveryReady,
      List<TaskCapability> tasks) {
    public CapabilitySnapshot {
      tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    public TaskCapability task(String taskKey) {
      return tasks.stream()
          .filter(value -> value.taskKey() != null && value.taskKey().equals(taskKey))
          .findFirst()
          .orElse(null);
    }
  }

  public record SnapshotBindings(
      String decisionDigest,
      String semanticActionDigest,
      String catalogDigest,
      String capabilityDigest,
      String goalPlanDigest,
      List<String> taskSkillDigests,
      String toolSchemaDigest,
      String knowledgeDigest,
      String policyDigest,
      String verifierDigest,
      String buildId,
      long expectedObservationRevision,
      long expectedTaskRevision,
      String observationDigest,
      String promptDigest) {
    public SnapshotBindings {
      taskSkillDigests = taskSkillDigests == null ? List.of() : List.copyOf(taskSkillDigests);
    }
  }

  public record OrchestrationRequest(
      String orchestrationId,
      String requestId,
      String idempotencyKey,
      String requestFingerprint,
      Mode mode,
      String sourceType,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      SnapshotBindings bindings,
      HumanAuthority authority,
      JsonNode observation,
      Instant deadline) {}

  /**
   * Non-secret identity used for every gateway operation after the initial validated request.
   *
   * <p>This contract deliberately contains digests and immutable routing metadata only. It is not a
   * recoverable {@link OrchestrationRequest}; in particular it never carries settings, identities,
   * budgets, observations, signatures, operator keys or fence tokens.
   */
  public record GatewayCorrelation(
      String contractVersion,
      String orchestrationId,
      String requestId,
      String idempotencyKey,
      String envelopeDigest,
      Mode mode,
      String sourceType,
      String taskKey,
      String authorityId,
      String identitiesDigest,
      String settingsDigest,
      String budgetsDigest,
      long expectedObservationRevision,
      long expectedTaskRevision,
      Instant deadline) {}

  public record Lease(
      String reservationId,
      long generation,
      long fence,
      String fenceTokenDigest,
      Instant expiresAt) {}

  /** Durable, one-use ownership of a human authority. No signature or secret is retained here. */
  public record AuthorityConsumption(
      String authorityId,
      String orchestrationId,
      String envelopeDigest,
      String approvedScopeDigest,
      String operatorId,
      Instant expiresAt,
      Instant consumedAt) {}

  public record TerminalResult(
      String orchestrationId,
      State outcome,
      FailureCode stableCode,
      boolean retryAllowed,
      boolean reconciled,
      String evidenceDigest,
      Instant terminalAt,
      long version) {}

  public record GatewayReceipt(
      String requestId,
      String envelopeDigest,
      String phase,
      State state,
      FailureCode failureCode,
      boolean deliveryAcknowledged,
      boolean terminalReconciled,
      Lease lease,
      List<String> evidenceRefs,
      Instant at,
      String receiptId,
      boolean killSwitch) {
    public GatewayReceipt(
        String requestId,
        String envelopeDigest,
        String phase,
        State state,
        FailureCode failureCode,
        boolean deliveryAcknowledged,
        boolean terminalReconciled,
        Lease lease,
        List<String> evidenceRefs,
        Instant at) {
      this(
          requestId,
          envelopeDigest,
          phase,
          state,
          failureCode,
          deliveryAcknowledged,
          terminalReconciled,
          lease,
          evidenceRefs,
          at,
          null,
          false);
    }

    public GatewayReceipt {
      evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
  }

  public record OrchestrationView(
      String contractVersion,
      String orchestrationId,
      String requestId,
      String idempotencyKey,
      String requestFingerprint,
      String envelopeDigest,
      Mode mode,
      String sourceType,
      State state,
      FailureCode failureCode,
      Lease lease,
      List<GatewayReceipt> receipts,
      boolean authorityConsumed,
      boolean retryAllowed,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    public OrchestrationView {
      receipts = receipts == null ? List.of() : List.copyOf(receipts);
    }
  }

  public record Readiness(
      String contractVersion,
      boolean enabled,
      boolean operatorKeyConfigured,
      boolean gatewayAvailable,
      boolean liveDeliveryReady,
      String capabilityDigest,
      FailureCode failureCode) {}
}

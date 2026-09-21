package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.AuthorityConsumption;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.TerminalResult;
import java.util.List;
import java.util.Optional;

public interface AccountBuilderOrchestrationStore {
  /**
   * Claims the orchestration and its immutable budget in one atomic operation.
   *
   * <p>The budget is parsed and validated by the caller before this method runs, so an invalid
   * budget can never persist any part of the aggregate. On success the root, the initial lifecycle
   * snapshot, the immutable request snapshot, the snapshot bindings, the gateway correlation, the
   * budget row and the initial event all exist or none of them do. An exact replay — same
   * fingerprint and same immutable budget — returns the existing aggregate. A changed request or
   * budget is an {@code IDEMPOTENCY_CONFLICT}. A durable claim without a budget is an internal
   * integrity failure and never silently replays.
   */
  Claim claim(
      OrchestrationRequest request,
      String fingerprint,
      String envelopeDigest,
      AccountBuilderBudgetPolicy.OrchestrationBudget budget);

  /** Returns the durable budget row, or empty when the claim never persisted one. */
  Optional<AccountBuilderBudgetPolicy.OrchestrationBudget> findBudget(String orchestrationId);

  /**
   * Atomically consumes one external attempt and, optionally, one semantic action. Returns the
   * advanced budget only when capacity remains at the moment of the conditional update; exhausted
   * or expired budgets throw {@link AccountBuilderOrchestrationException} with {@code
   * BUDGET_EXHAUSTED} or {@code DEADLINE_EXPIRED} before any gateway access.
   */
  AccountBuilderBudgetPolicy.OrchestrationBudget consumeBudget(
      String orchestrationId, boolean action, boolean attempt);

  /** Atomically claims a one-use authority before any gateway reservation is attempted. */
  AuthorityConsumption consumeAuthority(
      String orchestrationId,
      GameplayContracts.HumanAuthority authority,
      String envelopeDigest,
      OrchestrationRequest originalRequest);

  Optional<OrchestrationView> find(String orchestrationId);

  /** Reads the aggregate from normalized rows and verifies the denormalized view snapshot. */
  NormalizedAggregate readAggregate(String orchestrationId);

  /** Returns the exact currently persisted lease, rejecting stale or expired evidence. */
  Lease requireLease(String orchestrationId, Lease expected);

  /** Returns the persisted non-secret gateway correlation; never return a redacted request here. */
  GatewayCorrelation correlation(String orchestrationId);

  OrchestrationView transition(
      String orchestrationId,
      State state,
      FailureCode failureCode,
      Lease lease,
      GatewayReceipt receipt,
      boolean authorityConsumed,
      boolean retryAllowed);

  List<OrchestrationView> recoverable();

  Optional<TerminalResult> terminalResult(String orchestrationId);

  record Claim(OrchestrationView view, boolean created) {}

  record NormalizedAggregate(
      OrchestrationView view,
      GatewayCorrelation correlation,
      OrchestrationRequest redactedRequestSnapshot,
      SnapshotBindings bindings,
      AuthorityConsumption authority,
      Lease lease,
      List<GatewayReceipt> receipts,
      TerminalResult terminalResult) {
    public NormalizedAggregate {
      receipts = receipts == null ? List.of() : List.copyOf(receipts);
    }

    public boolean authorityConsumed() {
      return authority != null;
    }
  }
}

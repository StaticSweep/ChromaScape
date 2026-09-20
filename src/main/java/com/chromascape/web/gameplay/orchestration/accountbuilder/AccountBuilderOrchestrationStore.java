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
  Claim claim(OrchestrationRequest request, String fingerprint, String envelopeDigest);

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

package com.chromascape.web.gameplay.orchestration;

import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Outcome;
import java.util.List;

/** The sole state-changing boundary. Implementations may call only Micro6 AccountBuilderPlugin. */
public interface AccountBuilderGameplayGateway {
  DispatchResult dispatch(ExecutionEnvelope envelope, HumanAuthority authority);

  DispatchResult stop(String requestId, String reason, boolean killSwitch);

  record DispatchResult(
      Outcome outcome,
      String failureCode,
      boolean deliveryAcknowledged,
      boolean terminalReconciled,
      List<String> evidenceRefs) {}
}

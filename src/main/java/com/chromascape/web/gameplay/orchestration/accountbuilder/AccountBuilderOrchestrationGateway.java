package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;

/**
 * Sole SC-1431 delivery boundary. Implementations may expose semantic AccountBuilder calls only.
 */
public interface AccountBuilderOrchestrationGateway {
  CapabilitySnapshot capabilities();

  GatewayReceipt reserve(OrchestrationRequest request, String envelopeDigest);

  GatewayReceipt dispatch(OrchestrationRequest request, String envelopeDigest, Lease lease);

  GatewayReceipt status(GatewayCorrelation correlation, Lease lease);

  /**
   * Requests a bounded stop. The kill-switch flag carries the operator's requested stop mode all
   * the way to the transport; implementations must never substitute a default.
   */
  GatewayReceipt stop(
      GatewayCorrelation correlation, Lease lease, String reason, boolean killSwitch);

  GatewayReceipt reconcile(GatewayCorrelation correlation, Lease lease);

  /**
   * Lookup-only reservation reconciliation for an authority-consumed orchestration without lease.
   */
  GatewayReceipt reconcileReservation(GatewayCorrelation correlation);
}

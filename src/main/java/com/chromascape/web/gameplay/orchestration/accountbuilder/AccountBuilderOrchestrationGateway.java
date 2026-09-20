package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import java.time.Instant;

/**
 * Sole SC-1431 delivery boundary. Implementations may expose semantic AccountBuilder calls only.
 */
public interface AccountBuilderOrchestrationGateway {
  /**
   * Read-only capability acquisition bound to the validated request identity. Implementations must
   * consult both the catalog and the client observation evidence for the request's client, and must
   * never reserve, dispatch or mutate state here.
   */
  CapabilitySnapshot capabilities(OrchestrationRequest request);

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

  /**
   * Bounded, read-only terminal wait used by the SC-1498 one-run bootstrap. Implementations may
   * poll status until a proven terminal receipt or the earliest of the supplied deadline and their
   * own configured bound; the default is exactly one status lookup. It must never reserve or
   * dispatch again, and a still-nonterminal result must be reconciliation-only.
   */
  default GatewayReceipt awaitTerminal(
      GatewayCorrelation correlation, Lease lease, Instant deadline) {
    return status(correlation, lease);
  }
}

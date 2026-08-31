package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
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

  GatewayReceipt status(OrchestrationRequest request, String envelopeDigest, Lease lease);

  GatewayReceipt stop(
      OrchestrationRequest request, String envelopeDigest, Lease lease, String reason);

  GatewayReceipt reconcile(OrchestrationRequest request, String envelopeDigest, Lease lease);
}

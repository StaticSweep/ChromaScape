package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Default gateway until SC-1449 provides a conforming live AccountBuilder adapter. */
@Component
public final class ClosedAccountBuilderOrchestrationGateway
    implements AccountBuilderOrchestrationGateway {
  @Override
  public CapabilitySnapshot capabilities() {
    Instant now = Instant.now();
    return new CapabilitySnapshot(
        "unavailable", "unavailable", "unavailable", -1, -1, now, now, false, List.of());
  }

  @Override
  public GatewayReceipt reserve(OrchestrationRequest request, String envelopeDigest) {
    return unavailable(request, envelopeDigest, "RESERVATION");
  }

  @Override
  public GatewayReceipt dispatch(OrchestrationRequest request, String envelopeDigest, Lease lease) {
    return unavailable(request, envelopeDigest, "DISPATCH");
  }

  @Override
  public GatewayReceipt status(OrchestrationRequest request, String envelopeDigest, Lease lease) {
    return unavailable(request, envelopeDigest, "STATUS");
  }

  @Override
  public GatewayReceipt stop(
      OrchestrationRequest request, String envelopeDigest, Lease lease, String reason) {
    return unavailable(request, envelopeDigest, "STOP");
  }

  @Override
  public GatewayReceipt reconcile(
      OrchestrationRequest request, String envelopeDigest, Lease lease) {
    return unavailable(request, envelopeDigest, "RECONCILIATION");
  }

  private GatewayReceipt unavailable(
      OrchestrationRequest request, String envelopeDigest, String phase) {
    return new GatewayReceipt(
        request.requestId(),
        envelopeDigest,
        phase,
        State.DENIED,
        FailureCode.GATEWAY_UNAVAILABLE,
        false,
        true,
        null,
        List.of(),
        Instant.now());
  }
}

package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Default gateway until SC-1449 provides a conforming live AccountBuilder adapter. */
public final class ClosedAccountBuilderOrchestrationGateway
    implements AccountBuilderOrchestrationGateway {
  /**
   * Deterministic non-attestation capability digest. The closed gateway can never issue a server
   * capability attestation, so its probe reports exactly this fixed value instead of pretending a
   * catalog digest or any other request value is a capability attestation. It is a valid digest
   * literal so a delivery-disabled bootstrap request still fingerprints and persists its durable
   * {@code RUNTIME_DISABLED} denial, and it is stable so replays remain idempotent.
   */
  private static final String CLOSED_CAPABILITY_DIGEST = closedCapabilityDigest();

  private static String closedCapabilityDigest() {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          "closed-gateway-issues-no-capability-attestation"
                              .getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception exception) {
      throw new IllegalStateException("Closed gateway capability digest is unavailable", exception);
    }
  }

  @Override
  public CapabilitySnapshot capabilities(OrchestrationRequest request) {
    Instant now = Instant.now();
    return new CapabilitySnapshot(
        null, CLOSED_CAPABILITY_DIGEST, null, -1, -1, now, now, false, List.of());
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
  public GatewayReceipt status(GatewayCorrelation correlation, Lease lease) {
    return unavailable(correlation, "STATUS");
  }

  @Override
  public GatewayReceipt stop(
      GatewayCorrelation correlation, Lease lease, String reason, boolean killSwitch) {
    // The closed gateway never reaches a transport, but it still records the requested mode.
    return unavailable(correlation, "STOP", killSwitch);
  }

  @Override
  public GatewayReceipt reconcile(GatewayCorrelation correlation, Lease lease) {
    return unavailable(correlation, "RECONCILIATION");
  }

  @Override
  public GatewayReceipt reconcileReservation(GatewayCorrelation correlation) {
    return unavailable(correlation, "RESERVATION_RECONCILIATION");
  }

  private GatewayReceipt unavailable(GatewayCorrelation correlation, String phase) {
    return unavailable(correlation, phase, false);
  }

  private GatewayReceipt unavailable(
      GatewayCorrelation correlation, String phase, boolean killSwitch) {
    return new GatewayReceipt(
        correlation.requestId(),
        correlation.envelopeDigest(),
        phase,
        State.DENIED,
        FailureCode.GATEWAY_UNAVAILABLE,
        false,
        true,
        null,
        List.of(),
        Instant.now(),
        null,
        killSwitch);
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

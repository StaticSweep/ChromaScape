package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Read-only SC-1431 capability preflight.
 *
 * <p>Every production caller obtains the server-issued capability attestation here, before its
 * request is fingerprinted and persisted. The probe consults the catalog and the client observation
 * routes only: it never reserves, dispatches or mutates any state.
 */
@Component
public final class AccountBuilderCapabilityPreflight {
  private final AccountBuilderOrchestrationGateway gateway;

  public AccountBuilderCapabilityPreflight(AccountBuilderOrchestrationGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
  }

  /**
   * Probes the active gateway for the capability snapshot bound to the request client identity.
   * Implementations return a non-delivery-ready snapshot when the gateway cannot attest
   * capabilities; callers must fail closed instead of substituting a catalog digest.
   */
  public CapabilitySnapshot probe(ExecutionEnvelope envelope) {
    if (envelope == null || envelope.identities() == null) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "Capability preflight requires the request client identities");
    }
    // The gateway only reads the request identities for the client id, so this throwaway draft is
    // never validated, fingerprinted or persisted.
    return gateway.capabilities(probeRequest(envelope));
  }

  private OrchestrationRequest probeRequest(ExecutionEnvelope envelope) {
    return new OrchestrationRequest(
        "capability-preflight",
        envelope.requestId(),
        envelope.idempotencyKey(),
        null,
        null,
        "PREFLIGHT_PROBE",
        null,
        envelope,
        null,
        null,
        null,
        null);
  }
}

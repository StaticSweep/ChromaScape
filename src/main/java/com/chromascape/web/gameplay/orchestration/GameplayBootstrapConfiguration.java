package com.chromascape.web.gameplay.orchestration;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderCapabilityPreflight;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationRequestFactory;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legacy file-evidence and orchestrator compatibility beans (SC-1498 WP7).
 *
 * <p>Production bootstrap delivery uses {@code BootstrapGameplayOperatorService} with the durable
 * SC-1431 orchestration and the immutable manifest service. These beans are therefore excluded by
 * default so no second delivery path can be instantiated; tests and legacy tooling opt in with
 * {@code gameplay.bootstrap.legacy-orchestrator-enabled=true}.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "gameplay.bootstrap",
    name = "legacy-orchestrator-enabled",
    havingValue = "true")
public class GameplayBootstrapConfiguration {
  /**
   * Legacy file-evidence compatibility bean (SC-1498 WP7).
   *
   * @deprecated Production bootstrap delivery uses {@code BootstrapGameplayOperatorService} with
   *     the durable SC-1431 orchestration and the immutable manifest service. This bean remains
   *     only for the compatibility tests; it is not part of the production delivery route.
   */
  @Deprecated
  @Bean
  BootstrapEvidenceStore bootstrapEvidenceStore(
      ObjectMapper objectMapper,
      CanonicalGameplayJson canonicalJson,
      @Value("${gameplay.bootstrap.evidence-root:.chromascape/bootstrap-recordings}")
          String evidenceRoot) {
    return new BootstrapEvidenceStore(objectMapper, canonicalJson, Path.of(evidenceRoot));
  }

  /**
   * Legacy orchestrator compatibility bean (SC-1498 WP7).
   *
   * @deprecated Production bootstrap delivery uses {@code BootstrapGameplayOperatorService}
   *     delegating exclusively to {@code AccountBuilderOrchestrationService} in {@code
   *     BOOTSTRAP_RECORDING} mode via the Micro6 v2 gateway. This bean remains only for the
   *     compatibility tests; it is not part of the production delivery route.
   */
  @Deprecated
  @Bean
  BootstrapGameplayOrchestrator bootstrapGameplayOrchestrator(
      GameplayContractValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderGameplayGateway gateway,
      BootstrapEvidenceStore evidenceStore,
      AccountBuilderOrchestrationService orchestrationService,
      AccountBuilderOrchestrationRequestFactory requestFactory,
      AccountBuilderCapabilityPreflight capabilityPreflight) {
    // SC-1431 unification: dispatch, stop, idempotency, authority ownership and reconciliation
    // delegate to the durable orchestration service through the one shared request factory; the
    // legacy gateway remains only as evidence compatibility and is never an independent task-state
    // owner. The read-only capability preflight supplies the server-issued capability digest that
    // the immutable request binds before fingerprinting.
    return new BootstrapGameplayOrchestrator(
        validator,
        canonicalJson,
        gateway,
        evidenceStore,
        orchestrationService,
        requestFactory,
        capabilityPreflight,
        Clock.systemUTC());
  }
}

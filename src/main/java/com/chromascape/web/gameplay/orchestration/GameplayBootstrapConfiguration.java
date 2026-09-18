package com.chromascape.web.gameplay.orchestration;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationRequestFactory;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GameplayBootstrapConfiguration {
  @Bean
  BootstrapEvidenceStore bootstrapEvidenceStore(
      ObjectMapper objectMapper,
      CanonicalGameplayJson canonicalJson,
      @Value("${gameplay.bootstrap.evidence-root:.chromascape/bootstrap-recordings}")
          String evidenceRoot) {
    return new BootstrapEvidenceStore(objectMapper, canonicalJson, Path.of(evidenceRoot));
  }

  @Bean
  BootstrapGameplayOrchestrator bootstrapGameplayOrchestrator(
      GameplayContractValidator validator,
      CanonicalGameplayJson canonicalJson,
      AccountBuilderGameplayGateway gateway,
      BootstrapEvidenceStore evidenceStore,
      AccountBuilderOrchestrationService orchestrationService,
      AccountBuilderOrchestrationRequestFactory requestFactory) {
    // SC-1431 unification: dispatch, stop, idempotency, authority ownership and reconciliation
    // delegate to the durable orchestration service through the one shared request factory; the
    // legacy gateway remains only as evidence compatibility and is never an independent task-state
    // owner.
    return new BootstrapGameplayOrchestrator(
        validator,
        canonicalJson,
        gateway,
        evidenceStore,
        orchestrationService,
        requestFactory,
        Clock.systemUTC());
  }
}

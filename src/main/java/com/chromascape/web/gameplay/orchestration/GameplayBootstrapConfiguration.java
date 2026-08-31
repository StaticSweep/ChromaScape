package com.chromascape.web.gameplay.orchestration;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
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
      BootstrapEvidenceStore evidenceStore) {
    return new BootstrapGameplayOrchestrator(validator, canonicalJson, gateway, evidenceStore);
  }
}

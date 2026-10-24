package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Explicit external gate: real configured Codex process and PostgreSQL decision provenance. */
public final class Sc1991ConfiguredProviderHarness implements AutoCloseable {
  public final GameplayProviderRuntime runtime;
  public final ObjectMapper mapper;
  public final CanonicalGameplayJson canonical;
  private final ConfigurableApplicationContext context;

  public Sc1991ConfiguredProviderHarness(
      String url, Path temporary, String executable, String model) {
    context =
        new SpringApplicationBuilder(ProviderApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=test",
                "--spring.datasource.password=test",
                "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--spring.main.banner-mode=off");
    mapper = context.getBean(ObjectMapper.class);
    canonical = context.getBean(CanonicalGameplayJson.class);
    var properties = new ProviderRuntimeProperties();
    properties.setEnabled(true);
    properties.setOperatorKey("isolated-acceptance-operator-key-32-characters");
    properties.setWorkRoot(temporary.resolve("provider-work"));
    properties.setTranscriptRoot(temporary.resolve("provider-transcripts"));
    properties.getCodex().setCommand(List.of(executable));
    properties.getCodex().setAllowedModels(List.of(model));
    var validator = new ProviderRuntimeValidator(new GameplayContractValidator(mapper), canonical);
    var store = context.getBean(ProviderRuntimeStore.class);
    runtime =
        new GameplayProviderRuntime(
            properties,
            validator,
            store,
            new ProviderPromptBuilder(mapper),
            new ProviderOutputNormalizer(mapper, new GameplayContractValidator(mapper), canonical),
            new RedactedTranscriptStore(properties, canonical),
            new DurableProviderToolGateway(
                store, new DecisionToolFacade(canonical), canonical, properties),
            List.of(
                new CodexProviderAdapter(properties, new SafeProviderProcessRunner(properties))));
  }

  @org.springframework.boot.SpringBootConfiguration
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration
  @org.springframework.boot.autoconfigure.domain.EntityScan(
      basePackages = "com.chromascape.web.gameplay.provider.runtime")
  @org.springframework.context.annotation.Import(JpaProviderRuntimeStore.class)
  public static class ProviderApplication {
    @org.springframework.context.annotation.Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @org.springframework.context.annotation.Bean
    CanonicalGameplayJson canonicalGameplayJson(ObjectMapper mapper) {
      return new CanonicalGameplayJson(mapper);
    }
  }

  @Override
  public void close() {
    runtime.close();
    context.close();
  }
}

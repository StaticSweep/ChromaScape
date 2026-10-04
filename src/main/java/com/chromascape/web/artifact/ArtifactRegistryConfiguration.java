package com.chromascape.web.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ArtifactRegistryConfiguration {
  @Bean
  ImmutableArtifactStore immutableArtifactStore(
      ObjectMapper json,
      @Value("${ai-player.dataset.output-dir:.chromascape/datasets}") String root,
      @Value("${artifacts.max-artifact-bytes:104857600}") long maxBytes,
      @Value("${artifacts.quota-bytes:1073741824}") long quotaBytes) {
    return new ImmutableArtifactStore(
        Path.of(root).resolve("artifacts"), maxBytes, quotaBytes, json);
  }
}

package com.chromascape.web.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

class SourceTransportTokenBindingTest {
  @ParameterizedTest
  @CsvSource(
      value = {
        "legacy| | |true",
        " |metadata|content|true",
        "legacy|metadata| |true",
        "legacy| |content|true",
        " |metadata| |false",
        " | |content|false",
        " | | |false"
      },
      delimiter = '|')
  void validatesEffectiveOutboundTokensThroughSpring(
      String shared, String metadata, String content, boolean accepted) throws Exception {
    String publicKey =
        Base64.getEncoder()
            .encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
    new ApplicationContextRunner()
        .withUserConfiguration(Binding.class)
        .withPropertyValues(
            "chromascape.source.public-key-base64=" + publicKey,
            "chromascape.source.public-key-id=test-source",
            "chromascape.source.environment=test",
            "chromascape.source.access.metadata-token=inbound-metadata",
            "chromascape.source.access.content-token=inbound-content",
            "micro6.source.transport.base-url=http://127.0.0.1:8180",
            "micro6.source.transport.actor=test-actor",
            "micro6.source.transport.auth-token=" + value(shared),
            "micro6.source.transport.metadata-token=" + value(metadata),
            "micro6.source.transport.content-token=" + value(content))
        .run(
            context -> {
              if (accepted) {
                assertNull(context.getStartupFailure());
                assertNotNull(context.getBean(SourceConfigurationValidator.class));
              } else {
                assertNotNull(context.getStartupFailure());
                Throwable cause = context.getStartupFailure();
                while (cause.getCause() != null) cause = cause.getCause();
                assertTrue(cause.getMessage().contains("micro6.source.transport."));
              }
            });
  }

  private static String value(String value) {
    return value == null ? "   " : value;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SourceTrustProperties.class)
  @Import(SourceConfigurationValidator.class)
  static class Binding {
    @Bean
    static PropertySourcesPlaceholderConfigurer placeholders() {
      return new PropertySourcesPlaceholderConfigurer();
    }
  }
}

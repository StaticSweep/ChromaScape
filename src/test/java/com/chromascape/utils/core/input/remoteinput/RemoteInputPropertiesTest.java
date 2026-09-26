package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Proves the typed {@code chromascape.remoteinput} configuration contract: sensible bounded
 * defaults, relaxed environment-variable binding, and startup failure for every out-of-range value.
 */
class RemoteInputPropertiesTest {

  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);
  }

  private static ApplicationContextRunner runnerWithEnvironment(Map<String, Object> environment) {
    return runner()
        .withInitializer(
            context -> {
              MutablePropertySources sources = context.getEnvironment().getPropertySources();
              sources.replace(
                  StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                  new SystemEnvironmentPropertySource(
                      StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
            });
  }

  @Test
  void defaultsMatchTheSessionDefaultsAndStayDisabled() {
    runnerWithEnvironment(Map.of())
        .run(
            context -> {
              RemoteInputProperties properties = context.getBean(RemoteInputProperties.class);
              RemoteInputSessionOptions options = properties.toSessionOptions();
              assertFalse(options.attachmentEnabled());
              assertEquals(
                  RemoteInputSessionOptions.DEFAULT.injectTimeout(), options.injectTimeout());
              assertEquals(RemoteInputSessionOptions.DEFAULT.pairTimeout(), options.pairTimeout());
              assertEquals(
                  RemoteInputSessionOptions.DEFAULT.nativeCallTimeout(),
                  options.nativeCallTimeout());
              assertEquals(
                  RemoteInputSessionOptions.DEFAULT.releaseTimeout(), options.releaseTimeout());
              assertEquals(
                  RemoteInputSessionOptions.DEFAULT.validationInterval(),
                  options.validationInterval());
              assertEquals(RemoteInputSessionOptions.DEFAULT.maxClients(), options.maxClients());
            });
  }

  @Test
  void propertiesBindSpringDurationSyntax() {
    runner()
        .withPropertyValues(
            "chromascape.remoteinput.attachment-enabled=true",
            "chromascape.remoteinput.inject-timeout=45s",
            "chromascape.remoteinput.pair-timeout=5s",
            "chromascape.remoteinput.native-call-timeout=750ms",
            "chromascape.remoteinput.release-timeout=1500ms",
            "chromascape.remoteinput.validation-interval=100ms",
            "chromascape.remoteinput.max-clients=16")
        .run(
            context -> {
              RemoteInputSessionOptions options =
                  context.getBean(RemoteInputProperties.class).toSessionOptions();
              assertTrue(options.attachmentEnabled());
              assertEquals(Duration.ofSeconds(45), options.injectTimeout());
              assertEquals(Duration.ofSeconds(5), options.pairTimeout());
              assertEquals(Duration.ofMillis(750), options.nativeCallTimeout());
              assertEquals(Duration.ofMillis(1500), options.releaseTimeout());
              assertEquals(Duration.ofMillis(100), options.validationInterval());
              assertEquals(16, options.maxClients());
            });
  }

  @Test
  void environmentVariablesMapOntoEveryProperty() {
    runnerWithEnvironment(
            Map.of(
                "CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED", "true",
                "CHROMASCAPE_REMOTEINPUT_INJECT_TIMEOUT", "60s",
                "CHROMASCAPE_REMOTEINPUT_PAIR_TIMEOUT", "30s",
                "CHROMASCAPE_REMOTEINPUT_NATIVE_CALL_TIMEOUT", "10s",
                "CHROMASCAPE_REMOTEINPUT_RELEASE_TIMEOUT", "250ms",
                "CHROMASCAPE_REMOTEINPUT_VALIDATION_INTERVAL", "1s",
                "CHROMASCAPE_REMOTEINPUT_MAX_CLIENTS", "1"))
        .run(
            context -> {
              RemoteInputSessionOptions options =
                  context.getBean(RemoteInputProperties.class).toSessionOptions();
              assertTrue(options.attachmentEnabled());
              assertEquals(Duration.ofSeconds(60), options.injectTimeout());
              assertEquals(Duration.ofSeconds(30), options.pairTimeout());
              assertEquals(Duration.ofSeconds(10), options.nativeCallTimeout());
              assertEquals(Duration.ofMillis(250), options.releaseTimeout());
              assertEquals(Duration.ofSeconds(1), options.validationInterval());
              assertEquals(1, options.maxClients());
            });
  }

  @Test
  void preExistingArtifactSettingsUnderThePrefixAreTolerated() {
    runner()
        .withPropertyValues(
            "chromascape.remoteinput.application-root=/opt/chromascape/dist",
            "chromascape.remoteinput.library=/opt/chromascape/dist/native/libRemoteInput.so")
        .run(context -> assertNotNull(context.getBean(RemoteInputProperties.class)));
  }

  @Test
  void outOfRangeValuesFailStartup() {
    String[][] invalid = {
      {"chromascape.remoteinput.inject-timeout=0s", "inject-timeout"},
      {"chromascape.remoteinput.inject-timeout=-5s", "inject-timeout"},
      {"chromascape.remoteinput.inject-timeout=999ms", "inject-timeout"},
      {"chromascape.remoteinput.inject-timeout=61s", "inject-timeout"},
      {"chromascape.remoteinput.pair-timeout=500ms", "pair-timeout"},
      {"chromascape.remoteinput.pair-timeout=31s", "pair-timeout"},
      {"chromascape.remoteinput.native-call-timeout=249ms", "native-call-timeout"},
      {"chromascape.remoteinput.native-call-timeout=11s", "native-call-timeout"},
      {"chromascape.remoteinput.release-timeout=0ms", "release-timeout"},
      {"chromascape.remoteinput.release-timeout=10001ms", "release-timeout"},
      {"chromascape.remoteinput.validation-interval=24ms", "validation-interval"},
      {"chromascape.remoteinput.validation-interval=1001ms", "validation-interval"},
      {"chromascape.remoteinput.max-clients=0", "max-clients"},
      {"chromascape.remoteinput.max-clients=4097", "max-clients"},
    };
    for (String[] entry : invalid) {
      runner()
          .withPropertyValues(entry[0])
          .run(
              context -> {
                assertTrue(context.getStartupFailure() != null, entry[0] + " must fail startup");
                assertTrue(
                    rootMessage(context.getStartupFailure()).contains(entry[1]),
                    entry[0]
                        + " failure must name the property: "
                        + rootMessage(context.getStartupFailure()));
              });
    }
  }

  @Test
  void unparseableDurationFailsStartup() {
    runner()
        .withPropertyValues("chromascape.remoteinput.inject-timeout=thirty")
        .run(context -> assertTrue(context.getStartupFailure() != null));
  }

  @Test
  void pollingIntervalMayEqualButNeverExceedTheInjectionDeadline() {
    // The operator ranges (inject >= 1s, interval <= 1s) already make interval > inject
    // unreachable, so the tightest legal combination is equality, which must bind cleanly...
    runner()
        .withPropertyValues(
            "chromascape.remoteinput.inject-timeout=1s",
            "chromascape.remoteinput.validation-interval=1s")
        .run(
            context -> {
              RemoteInputSessionOptions options =
                  context.getBean(RemoteInputProperties.class).toSessionOptions();
              assertEquals(options.injectTimeout(), options.validationInterval());
            });

    // ...while any attempt to push the deadline below the interval is refused by the range guard
    // before the cross-field guard in RemoteInputSessionOptions would ever be needed.
    RemoteInputProperties properties = new RemoteInputProperties();
    properties.setValidationInterval(Duration.ofSeconds(1));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> properties.setInjectTimeout(Duration.ofMillis(999)));
    assertTrue(failure.getMessage().contains("inject-timeout"), failure.getMessage());
    properties.validate();
    assertEquals(Duration.ofSeconds(1), properties.toSessionOptions().validationInterval());
  }

  @Test
  void toSessionOptionsReturnsEquivalentValidatedOptionsEveryTime() {
    RemoteInputProperties properties = new RemoteInputProperties();
    properties.setAttachmentEnabled(true);
    properties.setInjectTimeout(Duration.ofSeconds(20));

    RemoteInputSessionOptions first = properties.toSessionOptions();
    RemoteInputSessionOptions second = properties.toSessionOptions();

    assertEquals(first, second);
    assertTrue(first.attachmentEnabled());
    assertEquals(Duration.ofSeconds(20), first.injectTimeout());
  }

  private static String rootMessage(Throwable failure) {
    Throwable current = failure;
    StringBuilder messages = new StringBuilder();
    while (current != null) {
      messages.append(current.getMessage()).append(' ');
      current = current.getCause();
    }
    return messages.toString();
  }

  @Configuration
  @EnableConfigurationProperties(RemoteInputProperties.class)
  static class PropertiesConfig {}
}

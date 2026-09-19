package com.chromascape.utils.core.input;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chromascape.utils.core.input.remoteinput.RemoteInputBindingProvider;
import com.chromascape.utils.core.input.remoteinput.RemoteInputInterface;
import com.chromascape.utils.core.input.remoteinput.RemoteInputProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Proves the attachment opt-in contract at the Spring property layer.
 *
 * <p>The default must remain disabled with no environment at all, an explicit {@code
 * chromascape.remoteinput.attachment-enabled=true} property enables attachment, and the {@code
 * CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED} environment variable maps onto that property through
 * Spring's relaxed binding — the documented operator override.
 */
class RemoteInputAttachmentPropertyMappingTest {

  private final RemoteInputBindingProvider provider = mock(RemoteInputBindingProvider.class);

  private ApplicationContextRunner runner() {
    when(provider.load()).thenReturn(mock(RemoteInputInterface.class));
    return new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig.class)
        .withBean(RemoteInputBindingProvider.class, () -> provider)
        .withBean(RemoteInputStartupValidator.class);
  }

  @Configuration
  @EnableConfigurationProperties(RemoteInputProperties.class)
  static class PropertiesConfig {}

  @Test
  void defaultWithoutAnyOverrideRemainsDisabled() {
    runner()
        .run(
            context -> {
              verifyNoInteractions(provider);
              assertNotNull(context.getBean(RemoteInputStartupValidator.class));
            });
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void explicitPropertyEnablesAttachmentAndLoadsTheBinding() {
    runner()
        .withPropertyValues("chromascape.remoteinput.attachment-enabled=true")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              verify(provider).load();
            });
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void environmentVariableOverrideEnablesAttachment() {
    runner()
        .withInitializer(
            context -> {
              MutablePropertySources sources = context.getEnvironment().getPropertySources();
              sources.replace(
                  StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                  new SystemEnvironmentPropertySource(
                      StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                      Map.of("CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED", "true")));
            })
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              verify(provider).load();
            });
  }
}

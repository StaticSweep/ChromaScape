package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class MouseVideoConfigurationTest {

  @Test
  void applicationYamlLoadsWithoutDuplicateKeysAndKeepsEagerMultipartDisabled() {
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    List<PropertySource<?>> sources =
        assertDoesNotThrowLoad(loader, new ClassPathResource("application.yml"));
    boolean enabled = false;
    for (PropertySource<?> source : sources) {
      Object value = source.getProperty("spring.servlet.multipart.enabled");
      if (value != null) {
        enabled = Boolean.parseBoolean(value.toString());
      }
    }
    assertThat(enabled).isFalse();
  }

  private static List<PropertySource<?>> assertDoesNotThrowLoad(
      YamlPropertySourceLoader loader, ClassPathResource resource) {
    var holder = new Object[1];
    assertThatCode(() -> holder[0] = loader.load("application", resource))
        .as("application.yml must not contain duplicate keys")
        .doesNotThrowAnyException();
    @SuppressWarnings("unchecked")
    List<PropertySource<?>> sources = (List<PropertySource<?>>) holder[0];
    return sources;
  }
}

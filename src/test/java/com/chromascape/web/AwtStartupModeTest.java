package com.chromascape.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

class AwtStartupModeTest {
  private final String originalHeadless = System.getProperty("java.awt.headless");

  @AfterEach
  void restoreHeadlessProperty() {
    if (originalHeadless == null) {
      System.clearProperty("java.awt.headless");
    } else {
      System.setProperty("java.awt.headless", originalHeadless);
    }
  }

  @Test
  void linuxWithoutDisplayStartsHeadless() {
    Properties properties = new Properties();

    AwtStartupMode.configure(properties, Map.of(), "Linux");

    assertThat(properties.getProperty("java.awt.headless")).isEqualTo("true");
  }

  @Test
  void linuxDisplayEnablesAwtBeforeStartup() {
    Properties properties = new Properties();

    AwtStartupMode.configure(properties, Map.of("WAYLAND_DISPLAY", "wayland-0"), "Linux");

    assertThat(properties.getProperty("java.awt.headless")).isEqualTo("false");
  }

  @Test
  void explicitOperatorChoiceAlwaysWins() {
    Properties properties = new Properties();
    properties.setProperty("java.awt.headless", "true");

    AwtStartupMode.configure(properties, Map.of("DISPLAY", ":0"), "Linux");

    assertThat(properties.getProperty("java.awt.headless")).isEqualTo("true");
  }

  @Test
  void nonLinuxHostKeepsJvmDefault() {
    Properties properties = new Properties();

    AwtStartupMode.configure(properties, Map.of(), "Windows 11");

    assertThat(properties).doesNotContainKey("java.awt.headless");
  }

  @Test
  void pageRequestsCannotChangeStartupMode() {
    System.setProperty("java.awt.headless", "true");
    ServePages pages = new ServePages();

    pages.serveIndexPage();
    pages.serveColourPickerPage();
    pages.serveDebuggerPage(new ConcurrentModel());

    assertThat(System.getProperty("java.awt.headless")).isEqualTo("true");
  }
}

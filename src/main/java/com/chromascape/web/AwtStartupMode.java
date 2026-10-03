package com.chromascape.web;

import java.util.Map;
import java.util.Properties;

/** Selects AWT headless mode once, before Spring or any AWT class is initialized. */
final class AwtStartupMode {
  private static final String HEADLESS_PROPERTY = "java.awt.headless";

  private AwtStartupMode() {}

  static void configureForCurrentHost() {
    configure(System.getProperties(), System.getenv(), System.getProperty("os.name", ""));
  }

  static void configure(Properties properties, Map<String, String> environment, String osName) {
    if (properties.getProperty(HEADLESS_PROPERTY) != null) {
      return;
    }
    if (!osName.toLowerCase().contains("linux")) {
      return;
    }
    boolean displayAvailable =
        isPresent(environment.get("DISPLAY")) || isPresent(environment.get("WAYLAND_DISPLAY"));
    properties.setProperty(HEADLESS_PROPERTY, Boolean.toString(!displayAvailable));
  }

  private static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}

package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InputLifecycleIsolationTest {
  @Test
  void linuxSessionHasNoWindowsOrKinputReference() throws IOException {
    String constants = classConstants(RemoteInputSession.class);
    assertFalse(constants.contains("com/sun/jna/platform/win32"));
    assertFalse(constants.contains("Kinput"));
  }

  @Test
  void windowsAdapterHasNoRemoteInputAbiReference() throws IOException {
    String constants = classConstants(KinputTargetInputSession.class);
    assertFalse(constants.contains("RemoteInputInterface"));
    assertFalse(constants.contains("EIOS_"));
  }

  private static String classConstants(Class<?> type) throws IOException {
    String resource = "/" + type.getName().replace('.', '/') + ".class";
    try (var input = type.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IOException("Compiled class resource is missing: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }
}

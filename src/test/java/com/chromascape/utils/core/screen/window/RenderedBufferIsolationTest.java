package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.chromascape.utils.core.screen.capture.TargetFrameRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RenderedBufferIsolationTest {

  @Test
  void platformNeutralFacadeHasNoWin32AbiReference() throws IOException {
    String constants = classConstants(ScreenManager.class);

    assertFalse(constants.contains("com/sun/jna/platform/win32"));
    assertFalse(constants.contains("User32"));
    assertFalse(constants.contains("GDI32"));
  }

  @Test
  void runtimeBridgeHasNoPlatformNativeReference() throws IOException {
    String constants = classConstants(TargetFrameRuntime.class);

    assertFalse(constants.contains("com/sun/jna"));
    assertFalse(constants.contains("RemoteInputInterface"));
    assertFalse(constants.contains("User32"));
  }

  @Test
  void windowsCaptureHasNoRemoteInputAbiReference() throws IOException {
    String constants = classConstants(WindowsGdiScreenCapture.class);

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

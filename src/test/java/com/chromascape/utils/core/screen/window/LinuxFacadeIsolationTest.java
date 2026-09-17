package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runtime isolation guarantee for the consumer facade on Linux: enumerating candidates and
 * resolving targets through the real {@link ProcessManagerFactory} + {@link TargetDiscoveryService}
 * must never initialize {@link WindowHandler}, whose static state loads the Win32 user32 library
 * via JNA.
 *
 * <p>On Linux any {@code Native.load("user32", ...)} attempt throws {@link UnsatisfiedLinkError}
 * (the library cannot exist on this platform, and {@code jna.library.path} is emptied below), so
 * completing the facade calls normally proves no Win32 native interface was initialized.
 */
class LinuxFacadeIsolationTest {

  @TempDir Path root;

  @Test
  @EnabledOnOs(OS.LINUX)
  void facadeEnumerationAndResolutionRunWithoutInitializingWin32() throws IOException {
    // Synthetic procfs with zero eligible clients: the scan must complete as a bounded empty result
    Files.writeString(root.resolve("uptime"), "1000.00 2000.00\n");

    String previous = System.getProperty("jna.library.path");
    System.setProperty("jna.library.path", "");
    try {
      ProcessManagerFactory factory = new ProcessManagerFactory(new ProcFs(root), "", "");
      WindowSelectionService selection = new WindowSelectionService();
      TargetDiscoveryService facade = new TargetDiscoveryService(factory, selection);

      // Candidate enumeration through the facade (returns WindowHandler.WindowCandidate values,
      // which must not initialize the enclosing WindowHandler class)
      assertTrue(facade.listUiCandidates().isEmpty());
      assertTrue(facade.listAllTargetWindows().isEmpty());

      // Zero-client target resolution through the facade
      TargetDiscoveryService.TargetResolution resolution = facade.resolveSelectedTarget();
      assertFalse(resolution.isResolved());
      assertEquals(TargetSelectionDiagnostic.NO_ELIGIBLE_TARGET, resolution.diagnostic());
      assertFalse(selection.hasSelection());

      // Any WindowHandler/User32 initialization above would have thrown UnsatisfiedLinkError
      // before reaching these assertions.
      assertTrue(factory.getProcessManager() instanceof LinuxProcessManager);
      assertTrue(factory.getProcessManager().isPlatformSupported());
    } finally {
      if (previous == null) {
        System.clearProperty("jna.library.path");
      } else {
        System.setProperty("jna.library.path", previous);
      }
    }
  }
}

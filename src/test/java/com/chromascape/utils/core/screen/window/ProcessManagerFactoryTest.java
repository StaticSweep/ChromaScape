package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class ProcessManagerFactoryTest {

  @Test
  void platformMappingCoversWindowsLinuxAndUnsupported() {
    assertEquals(
        ProcessManagerFactory.Platform.WINDOWS, ProcessManagerFactory.platformFor("Windows 11"));
    assertEquals(ProcessManagerFactory.Platform.LINUX, ProcessManagerFactory.platformFor("Linux"));
    assertEquals(
        ProcessManagerFactory.Platform.UNSUPPORTED, ProcessManagerFactory.platformFor("Mac OS X"));
    assertEquals(
        ProcessManagerFactory.Platform.UNSUPPORTED, ProcessManagerFactory.platformFor("SunOS"));
    assertEquals(
        ProcessManagerFactory.Platform.UNSUPPORTED, ProcessManagerFactory.platformFor(null));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void linuxDiscoveryRunsIsolatedFromWin32AndJnaInitialization() {
    String previous = System.getProperty("jna.library.path");
    System.setProperty("jna.library.path", "");
    try {
      // Any Win32/JNA user32 initialization would throw UnsatisfiedLinkError below
      ProcessManagerFactory factory =
          new ProcessManagerFactory(
              new ProcFs(Path.of("/nonexistent-sc1663-proc")), "/opt/micro6", "host-a");
      ProcessManager manager = factory.getProcessManager();

      assertInstanceOf(LinuxProcessManager.class, manager);
      assertTrue(manager.discoverEligibleLocal().isEmpty());
      assertTrue(manager.resolveLocal(1L).isEmpty());
    } finally {
      if (previous == null) {
        System.clearProperty("jna.library.path");
      } else {
        System.setProperty("jna.library.path", previous);
      }
    }
  }

  @Test
  void unsupportedPlatformsFailBoundedInsteadOfThrowing() {
    UnsupportedProcessManager unsupported = new UnsupportedProcessManager();

    assertTrue(unsupported.discoverEligibleLocal().isEmpty());
    assertTrue(unsupported.resolveLocal(1L).isEmpty());
    assertFalse(unsupported.isPlatformSupported());
    assertEquals(
        List.of(TargetSelectionDiagnostic.UNSUPPORTED_PLATFORM),
        unsupported.lastScanDiagnostics().diagnostics());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void windowsPlatformReturnsWindowsProcessManager() {
    ProcessManagerFactory factory = new ProcessManagerFactory();

    assertInstanceOf(WindowsProcessManager.class, factory.getProcessManager());
  }
}

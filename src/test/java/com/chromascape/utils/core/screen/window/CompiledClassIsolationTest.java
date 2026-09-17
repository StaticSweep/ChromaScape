package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Machine-checked isolation guarantees for SC-1663: the Linux discovery path must reference no
 * RemoteInput/attachment symbol and no Win32/JNA window class. The scan inspects the compiled
 * constant pools, which is the load-time-proof form of "discovery never attaches".
 *
 * <p>Scope note (deliberate): the facade {@code TargetDiscoveryService} returns {@link
 * WindowHandler.WindowCandidate}, the Windows-owned nested record, so the UI candidate shape stays
 * unchanged — its constant pool therefore contains a {@code WindowHandler} reference by design.
 * Instantiating that nested record does not initialize {@link WindowHandler}, whose static state
 * loads the Win32 user32 library; {@link LinuxFacadeIsolationTest} proves at runtime, with an empty
 * {@code jna.library.path}, that facade enumeration and resolution never trigger that
 * initialization. The facade is still scanned for {@code User32}/{@code user32} and {@code
 * WindowsProcessManager} references below.
 */
class CompiledClassIsolationTest {

  private static final Path CLASSES =
      Path.of(
          "build",
          "classes",
          "java",
          "main",
          "com",
          "chromascape",
          "utils",
          "core",
          "screen",
          "window");

  @Test
  void linuxDiscoveryAndFacadeClassesNeverReferenceRemoteInputOrAttachmentSymbols()
      throws IOException {
    assertForbiddenSymbolsAbsent(
        List.of("LinuxProcessManager", "ProcFs", "ProcessIdentity", "TargetDiscoveryService"),
        List.of("RemoteInput", "RemoteInputLibraryLoader", "EIOS_"));
  }

  @Test
  void linuxDiscoveryClassesNeverReferenceWin32WindowClasses() throws IOException {
    assertForbiddenSymbolsAbsent(
        List.of(
            "LinuxProcessManager",
            "ProcFs",
            "ProcessIdentity",
            "DiscoveredTarget",
            "ProcessManager",
            "TargetSelection",
            "SelectionValidation"),
        List.of("WindowHandler", "WindowsProcessManager", "user32", "User32"));
  }

  @Test
  void facadeNeverReferencesUser32OrTheWindowsImplementation() throws IOException {
    // The facade's WindowCandidate return type references WindowHandler by design (see the class
    // javadoc); any direct Win32 library or Windows-implementation reference is still forbidden.
    assertForbiddenSymbolsAbsent(
        List.of("TargetDiscoveryService"), List.of("WindowsProcessManager", "user32", "User32"));
  }

  private static void assertForbiddenSymbolsAbsent(List<String> classNames, List<String> forbidden)
      throws IOException {
    for (String className : classNames) {
      List<Path> classFiles;
      try (var stream = Files.list(CLASSES)) {
        classFiles =
            stream
                .filter(
                    path -> {
                      String name = path.getFileName().toString();
                      return name.equals(className + ".class") || name.startsWith(className + "$");
                    })
                .toList();
      }
      assertTrue(
          classFiles.stream()
              .anyMatch(path -> path.getFileName().toString().equals(className + ".class")),
          "missing compiled class " + className);
      for (Path classFile : classFiles) {
        String constantPool =
            new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        for (String symbol : forbidden) {
          assertFalse(
              constantPool.contains(symbol),
              classFile.getFileName() + " must not reference " + symbol);
        }
      }
    }
  }
}

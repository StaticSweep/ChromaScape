package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeArtifactResolverTest {

  private static final Path NO_ROOT = null;

  /** Injected property/environment accessor with Linux x86_64 host defaults. */
  private static final class FakeEnvironment implements Function<String, String> {
    private final Map<String, String> properties = new HashMap<>();
    private final Map<String, String> environment = new HashMap<>();

    FakeEnvironment() {
      properties.put("os.name", "Linux");
      properties.put("os.arch", "amd64");
    }

    FakeEnvironment property(String key, String value) {
      properties.put(key, value);
      return this;
    }

    FakeEnvironment environment(String key, String value) {
      environment.put(key, value);
      return this;
    }

    @Override
    public String apply(String name) {
      return properties.containsKey(name) ? properties.get(name) : environment.get(name);
    }
  }

  /** Writes a minimal well-formed ELF64 little-endian x86-64 shared-object header. */
  private static Path writeElfFixture(Path path) throws IOException {
    return writeElfFixture(path, 2, 1, 3, 62);
  }

  private static Path writeElfFixture(Path path, int elfClass, int data, int eType, int eMachine)
      throws IOException {
    byte[] header = new byte[64];
    header[0] = 0x7f;
    header[1] = 'E';
    header[2] = 'L';
    header[3] = 'F';
    header[4] = (byte) elfClass;
    header[5] = (byte) data;
    header[16] = (byte) (eType & 0xff);
    header[17] = (byte) ((eType >> 8) & 0xff);
    header[18] = (byte) (eMachine & 0xff);
    header[19] = (byte) ((eMachine >> 8) & 0xff);
    Files.createDirectories(path.getParent());
    Files.write(path, header);
    return path;
  }

  private static Path managedFixture(Path applicationRoot) throws IOException {
    return writeElfFixture(
        applicationRoot
            .resolve(NativeArtifactResolver.NATIVE_SUBDIRECTORY)
            .resolve(NativeArtifactResolver.LIBRARY_FILENAME));
  }

  @Test
  void propertyTakesPrecedenceOverEnvironment(@TempDir Path tmp) throws IOException {
    Path fromProperty = managedFixture(tmp.resolve("propertyRoot"));
    Path fromEnvironment = managedFixture(tmp.resolve("environmentRoot"));
    FakeEnvironment env =
        new FakeEnvironment()
            .property(NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY, fromProperty.toString())
            .environment(
                NativeArtifactResolver.LIBRARY_ENVIRONMENT_VARIABLE, fromEnvironment.toString());

    Path resolved = new NativeArtifactResolver(env, env).resolve(NO_ROOT);

    assertEquals(fromProperty.toRealPath(), resolved);
  }

  @Test
  void environmentIsUsedWhenPropertyIsAbsent(@TempDir Path tmp) throws IOException {
    Path fromEnvironment = managedFixture(tmp.resolve("environmentRoot"));
    FakeEnvironment env =
        new FakeEnvironment()
            .environment(
                NativeArtifactResolver.LIBRARY_ENVIRONMENT_VARIABLE, fromEnvironment.toString());

    Path resolved = new NativeArtifactResolver(env, env).resolve(NO_ROOT);

    assertEquals(fromEnvironment.toRealPath(), resolved);
  }

  @Test
  void applicationRootFallbackIsUsedWhenNoOverrideConfigured(@TempDir Path tmp) throws IOException {
    Path applicationRoot = tmp.resolve("distribution");
    Path expected = managedFixture(applicationRoot);

    Path resolved =
        new NativeArtifactResolver(new FakeEnvironment(), new FakeEnvironment())
            .resolve(applicationRoot);

    assertEquals(expected.toRealPath(), resolved);
  }

  @Test
  void invalidExplicitOverrideDoesNotFallBack(@TempDir Path tmp) throws IOException {
    Path validEnvironmentTarget = managedFixture(tmp.resolve("environmentRoot"));
    FakeEnvironment env =
        new FakeEnvironment()
            .property(
                NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY,
                tmp.resolve("missing/libRemoteInput.so").toString())
            .environment(
                NativeArtifactResolver.LIBRARY_ENVIRONMENT_VARIABLE,
                validEnvironmentTarget.toString());

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new NativeArtifactResolver(env, env).resolve(NO_ROOT));

    assertEquals(RemoteInputDiagnostic.MISSING_LIBRARY, failure.diagnostic());
  }

  @Test
  void relativeOverrideIsRejected(@TempDir Path tmp) {
    FakeEnvironment env =
        new FakeEnvironment()
            .property(NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY, "relative/libRemoteInput.so");

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new NativeArtifactResolver(env, env).resolve(NO_ROOT));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
  }

  @Test
  void missingFileIsReported(@TempDir Path tmp) {
    FakeEnvironment env =
        new FakeEnvironment()
            .property(
                NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY,
                tmp.resolve("nope/libRemoteInput.so").toString());

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new NativeArtifactResolver(env, env).resolve(NO_ROOT));

    assertEquals(RemoteInputDiagnostic.MISSING_LIBRARY, failure.diagnostic());
  }

  @Test
  void directoryInsteadOfFileIsRejected(@TempDir Path tmp) throws IOException {
    Path directory = tmp.resolve("libRemoteInput.so");
    Files.createDirectories(directory);
    FakeEnvironment env =
        new FakeEnvironment()
            .property(NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY, directory.toString());

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new NativeArtifactResolver(env, env).resolve(NO_ROOT));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
  }

  @Test
  void unreadableFileIsRejected(@TempDir Path tmp) throws IOException {
    Assumptions.assumeFalse(
        "root".equals(System.getProperty("user.name")), "root ignores permissions");
    Path unreadable = writeElfFixture(tmp.resolve("unreadable/libRemoteInput.so"));
    Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
    try {
      FakeEnvironment env =
          new FakeEnvironment()
              .property(NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY, unreadable.toString());

      RemoteInputArtifactException failure =
          assertThrows(
              RemoteInputArtifactException.class,
              () -> new NativeArtifactResolver(env, env).resolve(NO_ROOT));

      assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
    } finally {
      Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void pathsContainingSpacesResolve(@TempDir Path tmp) throws IOException {
    Path applicationRoot = tmp.resolve("dir with spaces");
    Path expected = managedFixture(applicationRoot);

    Path resolved =
        new NativeArtifactResolver(new FakeEnvironment(), new FakeEnvironment())
            .resolve(applicationRoot);

    assertEquals(expected.toRealPath(), resolved);
  }

  @Test
  void resolutionIsIndependentOfWorkingDirectory(@TempDir Path tmp) throws IOException {
    Path applicationRoot = tmp.resolve("elsewhere");
    Path expected = managedFixture(applicationRoot);
    String originalUserDir = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", tmp.resolve("unrelated").toString());
      Path resolved =
          new NativeArtifactResolver(new FakeEnvironment(), new FakeEnvironment())
              .resolve(applicationRoot);
      assertEquals(expected.toRealPath(), resolved);
    } finally {
      System.setProperty("user.dir", originalUserDir);
    }
  }

  @Test
  void unsupportedPlatformIsReported(@TempDir Path tmp) throws IOException {
    managedFixture(tmp.resolve("distribution"));
    FakeEnvironment env = new FakeEnvironment().property("os.name", "Windows 11");

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new NativeArtifactResolver(env, env).resolve(tmp.resolve("distribution")));

    assertEquals(RemoteInputDiagnostic.UNSUPPORTED_PLATFORM_OR_ARCH, failure.diagnostic());
  }

  @Test
  void corruptNonElfContentIsRejected(@TempDir Path tmp) throws IOException {
    Path corrupt = tmp.resolve("libRemoteInput.so");
    Files.write(corrupt, "this is not an ELF object".getBytes());
    FakeEnvironment env =
        new FakeEnvironment()
            .property(NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY, corrupt.toString());

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new NativeArtifactResolver(env, env).resolve(NO_ROOT));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
  }

  @Test
  void wrongElfClassIsRejected(@TempDir Path tmp) throws IOException {
    Path wrongClass = writeElfFixture(tmp.resolve("libRemoteInput.so"), 1, 1, 3, 62);
    FakeEnvironment env =
        new FakeEnvironment()
            .property(NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY, wrongClass.toString());

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new NativeArtifactResolver(env, env).resolve(NO_ROOT));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
  }

  @Test
  void wrongElfMachineIsReportedAsUnsupported(@TempDir Path tmp) throws IOException {
    Path wrongMachine = writeElfFixture(tmp.resolve("libRemoteInput.so"), 2, 1, 3, 183);
    FakeEnvironment env =
        new FakeEnvironment()
            .property(NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY, wrongMachine.toString());

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new NativeArtifactResolver(env, env).resolve(NO_ROOT));

    assertEquals(RemoteInputDiagnostic.UNSUPPORTED_PLATFORM_OR_ARCH, failure.diagnostic());
  }

  @Test
  void nonSharedObjectElfTypeIsRejected(@TempDir Path tmp) throws IOException {
    Path executable = writeElfFixture(tmp.resolve("libRemoteInput.so"), 2, 1, 2, 62);
    FakeEnvironment env =
        new FakeEnvironment()
            .property(NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY, executable.toString());

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new NativeArtifactResolver(env, env).resolve(NO_ROOT));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
  }

  @Test
  void managedPathUsesExpectedStagingLayout(@TempDir Path tmp) {
    Path managed = NativeArtifactResolver.managedArtifactPath(tmp);

    assertTrue(managed.endsWith("native/linux-x86_64/libRemoteInput.so"), managed::toString);
    assertEquals(managed, NativeArtifactResolver.managedArtifactPath(tmp));
  }
}

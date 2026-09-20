package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteInputLibraryLoaderTest {

  /**
   * A binding whose every method fails the test if invoked. Loading and symbol verification must
   * never call into the native interface, because doing so would risk injection, pairing or input.
   */
  private static RemoteInputInterface noCallBinding() {
    return (RemoteInputInterface)
        Proxy.newProxyInstance(
            RemoteInputInterface.class.getClassLoader(),
            new Class<?>[] {RemoteInputInterface.class},
            (proxy, method, args) -> {
              throw new AssertionError("native binding must not be invoked: " + method.getName());
            });
  }

  private static final class FakeNativeAccess implements RemoteInputLibraryLoader.NativeAccess {
    private Path openedPath;
    private Path boundPath;
    private int openCount;
    private int bindCount;
    private Throwable openFailure;
    private Throwable bindFailure;
    private final Set<String> available = new HashSet<>(RemoteInputLibraryLoader.REQUIRED_SYMBOLS);
    private RemoteInputInterface binding = noCallBinding();

    @Override
    public RemoteInputLibraryLoader.NativeLibraryHandle open(Path canonicalPath) {
      openedPath = canonicalPath;
      openCount++;
      if (openFailure != null) {
        throwAsUnchecked(openFailure);
      }
      return available::contains;
    }

    @Override
    public RemoteInputInterface bind(Path canonicalPath) {
      boundPath = canonicalPath;
      bindCount++;
      if (bindFailure != null) {
        throwAsUnchecked(bindFailure);
      }
      return binding;
    }

    private static void throwAsUnchecked(Throwable throwable) {
      if (throwable instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (throwable instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException(throwable);
    }
  }

  @Test
  void loadsAbsolutePathAndVerifiesSymbols(@TempDir Path tmp) {
    FakeNativeAccess nativeAccess = new FakeNativeAccess();

    RemoteInputLibraryLoader.LoadedRemoteInput loaded =
        new RemoteInputLibraryLoader(nativeAccess).load(tmp.resolve("libRemoteInput.so"));

    assertEquals(tmp.resolve("libRemoteInput.so").toAbsolutePath(), nativeAccess.openedPath);
    assertEquals(tmp.resolve("libRemoteInput.so").toAbsolutePath(), nativeAccess.boundPath);
    assertNotNull(loaded);
    assertEquals(1, nativeAccess.openCount);
    assertEquals(1, nativeAccess.bindCount);
  }

  @Test
  void loadFailureIsReportedWithOriginalCause(@TempDir Path tmp) {
    FakeNativeAccess nativeAccess = new FakeNativeAccess();
    UnsatisfiedLinkError cause =
        new UnsatisfiedLinkError("libGL.so.1: cannot open shared object file");
    nativeAccess.openFailure = cause;

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () ->
                new RemoteInputLibraryLoader(nativeAccess).load(tmp.resolve("libRemoteInput.so")));

    assertEquals(RemoteInputDiagnostic.LOAD_FAILED, failure.diagnostic());
    assertSame(cause, failure.getCause());
    assertEquals(0, nativeAccess.bindCount, "a failed open must not reach the bind step");
  }

  @Test
  void bindFailureIsReportedAsLoadFailed(@TempDir Path tmp) {
    FakeNativeAccess nativeAccess = new FakeNativeAccess();
    UnsatisfiedLinkError cause = new UnsatisfiedLinkError("symbol resolution failed");
    nativeAccess.bindFailure = cause;

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () ->
                new RemoteInputLibraryLoader(nativeAccess).load(tmp.resolve("libRemoteInput.so")));

    assertEquals(RemoteInputDiagnostic.LOAD_FAILED, failure.diagnostic());
    assertSame(cause, failure.getCause());
  }

  @Test
  void missingSymbolProducesExpectedDiagnostic(@TempDir Path tmp) {
    FakeNativeAccess nativeAccess = new FakeNativeAccess();
    nativeAccess.available.remove("EIOS_GetClients");

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () ->
                new RemoteInputLibraryLoader(nativeAccess).load(tmp.resolve("libRemoteInput.so")));

    assertEquals(RemoteInputDiagnostic.MISSING_SYMBOL, failure.diagnostic());
    assertTrue(failure.getMessage().contains("EIOS_GetClients"), failure::getMessage);
    assertEquals(0, nativeAccess.bindCount, "a missing symbol must not reach the bind step");
  }

  @Test
  void everyRequiredSymbolIsChecked(@TempDir Path tmp) {
    for (String symbol : RemoteInputLibraryLoader.REQUIRED_SYMBOLS) {
      FakeNativeAccess nativeAccess = new FakeNativeAccess();
      nativeAccess.available.remove(symbol);

      RemoteInputArtifactException failure =
          assertThrows(
              RemoteInputArtifactException.class,
              () ->
                  new RemoteInputLibraryLoader(nativeAccess).load(tmp.resolve("libRemoteInput.so")),
              "expected a failure when " + symbol + " is absent");

      assertEquals(RemoteInputDiagnostic.MISSING_SYMBOL, failure.diagnostic());
      assertTrue(failure.getMessage().contains(symbol), failure::getMessage);
    }
  }

  @Test
  void bindingIsNeverInvoked(@TempDir Path tmp) {
    FakeNativeAccess nativeAccess = new FakeNativeAccess();

    RemoteInputLibraryLoader.LoadedRemoteInput loaded =
        new RemoteInputLibraryLoader(nativeAccess).load(tmp.resolve("libRemoteInput.so"));

    // The proxy throws AssertionError if any native method were called. Touch the handle only.
    assertNotNull(loaded.binding());
    assertSame(nativeAccess.binding, loaded.binding());
  }

  @Test
  void constructingTheLoaderPerformsNoNativeAccess() {
    FakeNativeAccess nativeAccess = new FakeNativeAccess();

    RemoteInputLibraryLoader loader = new RemoteInputLibraryLoader(nativeAccess);

    assertNotNull(loader);
    assertEquals(0, nativeAccess.openCount, "no native open must occur before load()");
    assertEquals(0, nativeAccess.bindCount, "no native bind must occur before load()");
    assertFalse(RemoteInputLibraryLoader.REQUIRED_SYMBOLS.isEmpty());
    assertTrue(RemoteInputLibraryLoader.REQUIRED_SYMBOLS.contains("EIOS_IsMouseButtonHeld"));
    assertFalse(
        RemoteInputLibraryLoader.REQUIRED_SYMBOLS.contains("EIOS_IsMouseHeld"),
        "EIOS_IsMouseHeld does not exist in the pinned native sources");
  }

  @Test
  void nativeBooleanAbiUsesOneByteValues() throws NoSuchMethodException {
    assertEquals(
        byte.class,
        RemoteInputInterface.class
            .getMethod("EIOS_HasFocus", com.sun.jna.Pointer.class)
            .getReturnType());
    assertEquals(
        byte.class,
        RemoteInputInterface.class
            .getMethod("EIOS_IsKeyboardInputEnabled", com.sun.jna.Pointer.class)
            .getReturnType());
    assertEquals(
        void.class,
        RemoteInputInterface.class
            .getMethod("EIOS_SetKeyboardInputEnabled", com.sun.jna.Pointer.class, byte.class)
            .getReturnType());
    assertEquals(
        byte.class,
        RemoteInputInterface.class
            .getMethod("EIOS_IsMouseButtonHeld", com.sun.jna.Pointer.class, int.class)
            .getReturnType());
    assertEquals(
        byte.class,
        RemoteInputInterface.class
            .getMethod("EIOS_IsKeyHeld", com.sun.jna.Pointer.class, int.class)
            .getReturnType());
    assertEquals(
        long.class,
        RemoteInputInterface.class.getMethod("EIOS_GetClients", byte.class).getReturnType());

    assertEquals(0, RemoteInputInterface.toNativeBoolean(false));
    assertEquals(1, RemoteInputInterface.toNativeBoolean(true));
    assertFalse(RemoteInputInterface.fromNativeBoolean((byte) 0));
    assertTrue(RemoteInputInterface.fromNativeBoolean((byte) 1));
    assertTrue(RemoteInputInterface.fromNativeBoolean((byte) -1));
  }

  @Test
  void absoluteNonNormalisedPathIsRejected(@TempDir Path tmp) {
    FakeNativeAccess nativeAccess = new FakeNativeAccess();
    Path nonNormalised = tmp.resolve("nested").resolve("..").resolve("libRemoteInput.so");

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new RemoteInputLibraryLoader(nativeAccess).load(nonNormalised));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
    assertEquals(0, nativeAccess.openCount, "a non-normalised path must not reach native loading");
  }

  @Test
  void relativePathIsRejectedRatherThanResolvedAgainstTheWorkingDirectory() {
    FakeNativeAccess nativeAccess = new FakeNativeAccess();
    Path relative = Path.of("libRemoteInput.so");

    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> new RemoteInputLibraryLoader(nativeAccess).load(relative));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
    assertEquals(0, nativeAccess.openCount, "a relative path must not reach native loading");
    assertEquals(0, nativeAccess.bindCount, "a relative path must not bind an interface");
  }

  /**
   * A library that would exist if the relative path were silently absolutised against the working
   * directory must still be refused: loading must never depend on where the JVM started.
   */
  @Test
  void relativePathCannotBecomeWorkingDirectoryDependent(@TempDir Path tmp) throws IOException {
    Path plausible = tmp.resolve("libRemoteInput.so");
    Files.write(plausible, new byte[] {0x7f, 'E', 'L', 'F'});
    assertTrue(Files.isRegularFile(plausible));

    String originalUserDir = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", tmp.toString());
      FakeNativeAccess nativeAccess = new FakeNativeAccess();

      RemoteInputArtifactException failure =
          assertThrows(
              RemoteInputArtifactException.class,
              () -> new RemoteInputLibraryLoader(nativeAccess).load(Path.of("libRemoteInput.so")));

      assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
      assertEquals(
          0, nativeAccess.openCount, "no library may be opened from the working directory");
    } finally {
      System.setProperty("user.dir", originalUserDir);
    }
  }
}

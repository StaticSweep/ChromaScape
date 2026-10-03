package com.chromascape.utils.core.input.remoteinput;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Loads a already-resolved RemoteInput artifact and verifies its required exports.
 *
 * <p>This class exists because the upstream {@code RemoteInput(int pid)} constructor both loads the
 * library <em>and</em> immediately injects/pairs with a target. That constructor must not be used
 * for a load smoke test. This loader therefore performs only:
 *
 * <ol>
 *   <li>opening the library at an absolute canonical path;
 *   <li>confirming every required exported symbol is present;
 *   <li>binding the audited {@link RemoteInputInterface}.
 * </ol>
 *
 * <p>It never enumerates processes, chooses a pid, injects, pairs, requests control of a target, or
 * sends input. Binding through JNA resolves nothing eagerly, so no target session is created.
 *
 * <p>Native access is injected through {@link NativeAccess} so ordinary unit tests can exercise
 * success and failure paths without a real shared library.
 */
public final class RemoteInputLibraryLoader {

  /** Human-readable description of the only platform this loader is valid for. */
  public static final String EXPECTED_TARGET = "linux-x86_64";

  /**
   * Exported symbols this story binds, and therefore requires. Derived from the pinned {@code
   * EIOS.hxx} plus {@code EIOS_Inject_PID} from {@code Plugin/NativePlugin.hxx}. Mirrored by {@code
   * scripts/native/build-remoteinput-linux.sh}; keep both in step.
   */
  public static final List<String> REQUIRED_SYMBOLS =
      List.of(
          "EIOS_Inject_PID",
          "EIOS_RequestTarget",
          "EIOS_ReleaseTarget",
          "EIOS_GetTargetDimensions",
          "EIOS_GetImageBuffer",
          "EIOS_GetDebugImageBuffer",
          "EIOS_HasFocus",
          "EIOS_GainFocus",
          "EIOS_LoseFocus",
          "EIOS_IsKeyboardInputEnabled",
          "EIOS_SetKeyboardInputEnabled",
          "EIOS_GetMousePosition",
          "EIOS_MoveMouse",
          "EIOS_HoldMouse",
          "EIOS_ReleaseMouse",
          "EIOS_ScrollMouse",
          "EIOS_IsMouseButtonHeld",
          "EIOS_SendString",
          "EIOS_HoldKey",
          "EIOS_ReleaseKey",
          "EIOS_IsKeyHeld",
          "EIOS_GetClients",
          "EIOS_GetClientPID");

  /** A loaded library plus the audited interface bound to it. Never a target-bound session. */
  public record LoadedRemoteInput(Path canonicalPath, RemoteInputInterface binding) {}

  /** Minimal handle over an open native library, so symbol checks are injectable. */
  public interface NativeLibraryHandle {

    /**
     * Reports whether the open library exports a symbol.
     *
     * @return true when {@code name} is an exported symbol of the open library.
     */
    boolean hasSymbol(String name);
  }

  /** Injectable native-loading boundary. */
  public interface NativeAccess {

    /** Opens the library at {@code canonicalPath}, failing if it cannot be loaded. */
    NativeLibraryHandle open(Path canonicalPath);

    /** Binds the audited interface to the library at {@code canonicalPath}. */
    RemoteInputInterface bind(Path canonicalPath);
  }

  /** Default {@link NativeAccess} backed by JNA. */
  public static final class JnaNativeAccess implements NativeAccess {

    @Override
    public NativeLibraryHandle open(Path canonicalPath) {
      NativeLibrary library = NativeLibrary.getInstance(canonicalPath.toString());
      return name -> {
        try {
          library.getFunction(name);
          return true;
        } catch (UnsatisfiedLinkError notFound) {
          return false;
        }
      };
    }

    @Override
    public RemoteInputInterface bind(Path canonicalPath) {
      return Native.load(canonicalPath.toString(), RemoteInputInterface.class);
    }
  }

  private final NativeAccess nativeAccess;

  /** Creates a loader backed by JNA. */
  public RemoteInputLibraryLoader() {
    this(new JnaNativeAccess());
  }

  /**
   * Creates a loader over an injected native-access boundary.
   *
   * @param nativeAccess the native loading implementation to use
   */
  public RemoteInputLibraryLoader(NativeAccess nativeAccess) {
    this.nativeAccess = Objects.requireNonNull(nativeAccess, "nativeAccess");
  }

  /**
   * Loads and symbol-verifies the resolved artifact.
   *
   * <p>The path must already be absolute and canonical, as returned by {@link
   * NativeArtifactResolver#resolve(Path)}. A relative path is rejected rather than resolved against
   * the process working directory: silently absolutising it would let a caller load a different
   * library depending on where the JVM happened to start.
   *
   * @param canonicalArtifact absolute, previously validated artifact path
   * @return the loaded library and its bound interface
   * @throws RemoteInputArtifactException with {@code INVALID_ARTIFACT} for a relative or
   *     non-normalised path, {@code MISSING_SYMBOL} or {@code LOAD_FAILED} otherwise
   */
  public LoadedRemoteInput load(Path canonicalArtifact) {
    Objects.requireNonNull(canonicalArtifact, "canonicalArtifact");
    if (!canonicalArtifact.isAbsolute()
        || !canonicalArtifact.equals(canonicalArtifact.normalize())) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          canonicalArtifact,
          EXPECTED_TARGET,
          "artifact path must be absolute and normalised; resolve it through"
              + " NativeArtifactResolver first");
    }
    Path path = canonicalArtifact;

    NativeLibraryHandle handle;
    try {
      handle = nativeAccess.open(path);
    } catch (Throwable openFailure) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.LOAD_FAILED,
          path,
          EXPECTED_TARGET,
          "native library failed to load",
          openFailure);
    }

    try {
      for (String symbol : REQUIRED_SYMBOLS) {
        if (!handle.hasSymbol(symbol)) {
          throw new RemoteInputArtifactException(
              RemoteInputDiagnostic.MISSING_SYMBOL,
              path,
              EXPECTED_TARGET,
              "required native symbol is unavailable: " + symbol,
              new UnsatisfiedLinkError(symbol));
        }
      }
      RemoteInputInterface binding = nativeAccess.bind(path);
      return new LoadedRemoteInput(path, binding);
    } catch (RemoteInputArtifactException failure) {
      throw failure;
    } catch (Throwable bindFailure) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.LOAD_FAILED,
          path,
          EXPECTED_TARGET,
          "native library failed to bind the audited interface",
          bindFailure);
    }
  }
}

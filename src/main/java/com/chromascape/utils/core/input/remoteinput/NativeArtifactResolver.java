package com.chromascape.utils.core.input.remoteinput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Deterministically selects and validates the Linux RemoteInput native artifact.
 *
 * <p>Precedence (highest first):
 *
 * <ol>
 *   <li>system property {@value #LIBRARY_SYSTEM_PROPERTY};
 *   <li>environment variable {@value #LIBRARY_ENVIRONMENT_VARIABLE};
 *   <li>{@code <applicationRoot>/}{@value #NATIVE_SUBDIRECTORY}{@code /}{@value #LIBRARY_FILENAME}.
 * </ol>
 *
 * <p>An explicit override must be an absolute path to a readable regular file. If it is invalid the
 * resolver fails immediately and does <b>not</b> fall back to another library.
 *
 * <p>This class never enumerates processes, selects a target, injects, pairs, or sends input, and
 * it never searches the working directory, {@code PATH}, downloads, or system library directories.
 */
public final class NativeArtifactResolver {

  public static final String LIBRARY_SYSTEM_PROPERTY = "chromascape.remoteinput.library";
  public static final String LIBRARY_ENVIRONMENT_VARIABLE = "CHROMASCAPE_REMOTEINPUT_LIBRARY";
  public static final String NATIVE_SUBDIRECTORY = "native/linux-x86_64";
  public static final String LIBRARY_FILENAME = "libRemoteInput.so";
  public static final String EXPECTED_DESCRIPTION = "linux-x86_64 ELF 64-bit LSB shared object";

  private static final String SUPPORTED_OS = "linux";
  private static final String[] SUPPORTED_ARCHITECTURES = {"x86_64", "amd64"};

  // ELF constants (System V gABI).
  private static final int ELF_HEADER_BYTES = 20;
  private static final int EI_CLASS_OFFSET = 4;
  private static final int EI_DATA_OFFSET = 5;
  private static final int E_TYPE_OFFSET = 16;
  private static final int E_MACHINE_OFFSET = 18;
  private static final int ELFCLASS64 = 2;
  private static final int ELFDATA2LSB = 1;
  private static final int ET_DYN = 3;
  private static final int EM_X86_64 = 62;

  private final Function<String, String> systemProperty;
  private final Function<String, String> environment;

  /** Creates a resolver backed by the real system properties and environment variables. */
  public NativeArtifactResolver() {
    this(System::getProperty, System::getenv);
  }

  /**
   * Creates a resolver over injected property and environment accessors.
   *
   * @param systemProperty reads a system property (injected for tests)
   * @param environment reads an environment variable (injected for tests)
   */
  public NativeArtifactResolver(
      Function<String, String> systemProperty, Function<String, String> environment) {
    this.systemProperty = Objects.requireNonNull(systemProperty, "systemProperty");
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  /**
   * Resolves and validates the artifact to load.
   *
   * @param applicationRoot absolute root beneath which the staged {@code native/linux-x86_64}
   *     directory lives; may be {@code null} only when an explicit override is configured
   * @return the canonical absolute path of the validated artifact
   * @throws RemoteInputArtifactException when resolution or validation fails
   */
  public Path resolve(Path applicationRoot) {
    requireSupportedPlatform(null);

    String propertyOverride = normalise(systemProperty.apply(LIBRARY_SYSTEM_PROPERTY));
    if (propertyOverride != null) {
      return resolveExplicit(propertyOverride, LIBRARY_SYSTEM_PROPERTY);
    }

    String environmentOverride = normalise(environment.apply(LIBRARY_ENVIRONMENT_VARIABLE));
    if (environmentOverride != null) {
      return resolveExplicit(environmentOverride, LIBRARY_ENVIRONMENT_VARIABLE);
    }

    if (applicationRoot == null) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.MISSING_LIBRARY,
          (Path) null,
          EXPECTED_DESCRIPTION,
          "no explicit override is configured and no application root was supplied");
    }
    if (!applicationRoot.isAbsolute()) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          applicationRoot,
          EXPECTED_DESCRIPTION,
          "application root must be an absolute path");
    }
    return validateFile(managedArtifactPath(applicationRoot), "staged artifact");
  }

  /** The managed artifact path for a given application root. Does not touch the filesystem. */
  public static Path managedArtifactPath(Path applicationRoot) {
    return applicationRoot.resolve(NATIVE_SUBDIRECTORY).resolve(LIBRARY_FILENAME);
  }

  private Path resolveExplicit(String raw, String source) {
    Path path;
    try {
      path = Path.of(raw);
    } catch (InvalidPathException invalid) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          raw,
          EXPECTED_DESCRIPTION,
          source + " is not a usable path",
          invalid);
    }
    if (!path.isAbsolute()) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          path,
          EXPECTED_DESCRIPTION,
          source + " must be an absolute path");
    }
    return validateFile(path, source);
  }

  private Path validateFile(Path path, String context) {
    if (!Files.exists(path)) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.MISSING_LIBRARY,
          path,
          EXPECTED_DESCRIPTION,
          context + " does not exist");
    }
    if (!Files.isRegularFile(path)) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          path,
          EXPECTED_DESCRIPTION,
          context + " is not a regular file");
    }
    if (!Files.isReadable(path)) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          path,
          EXPECTED_DESCRIPTION,
          context + " is not readable");
    }

    Path canonical;
    try {
      canonical = path.toRealPath();
    } catch (IOException cannotCanonicalise) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          path,
          EXPECTED_DESCRIPTION,
          context + " cannot be canonicalised",
          cannotCanonicalise);
    }
    verifyElfSharedObject(canonical);
    return canonical;
  }

  private void verifyElfSharedObject(Path canonical) {
    byte[] header;
    try (InputStream in = Files.newInputStream(canonical)) {
      header = in.readNBytes(ELF_HEADER_BYTES);
    } catch (IOException unreadable) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          canonical,
          EXPECTED_DESCRIPTION,
          "artifact could not be read",
          unreadable);
    }

    if (header.length < ELF_HEADER_BYTES
        || (header[0] & 0xff) != 0x7f
        || header[1] != 'E'
        || header[2] != 'L'
        || header[3] != 'F') {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          canonical,
          EXPECTED_DESCRIPTION,
          "artifact is not an ELF object");
    }
    if ((header[EI_CLASS_OFFSET] & 0xff) != ELFCLASS64) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          canonical,
          EXPECTED_DESCRIPTION,
          "artifact is not a 64-bit ELF object");
    }
    if ((header[EI_DATA_OFFSET] & 0xff) != ELFDATA2LSB) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          canonical,
          EXPECTED_DESCRIPTION,
          "artifact is not little-endian");
    }
    int type = u16(header, E_TYPE_OFFSET);
    if (type != ET_DYN) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          canonical,
          EXPECTED_DESCRIPTION,
          "artifact is not a shared object (ELF type " + type + ")");
    }
    int machine = u16(header, E_MACHINE_OFFSET);
    if (machine != EM_X86_64) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.UNSUPPORTED_PLATFORM_OR_ARCH,
          canonical,
          EXPECTED_DESCRIPTION,
          "artifact targets ELF machine " + machine + ", not x86-64");
    }
  }

  private void requireSupportedPlatform(Path selected) {
    String os = normalise(systemProperty.apply("os.name"));
    String arch = normalise(systemProperty.apply("os.arch"));
    boolean osOk = os != null && os.toLowerCase(Locale.ROOT).contains(SUPPORTED_OS);
    boolean archOk = false;
    if (arch != null) {
      String normalisedArch = arch.toLowerCase(Locale.ROOT);
      for (String supported : SUPPORTED_ARCHITECTURES) {
        if (supported.equals(normalisedArch)) {
          archOk = true;
          break;
        }
      }
    }
    if (!osOk || !archOk) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.UNSUPPORTED_PLATFORM_OR_ARCH,
          selected,
          EXPECTED_DESCRIPTION,
          "host is not a supported Linux x86_64 platform (os.name="
              + os
              + ", os.arch="
              + arch
              + ")");
    }
  }

  private static int u16(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
  }

  private static String normalise(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}

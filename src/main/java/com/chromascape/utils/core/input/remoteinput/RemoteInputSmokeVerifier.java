package com.chromascape.utils.core.input.remoteinput;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Standalone native load/symbol smoke verifier.
 *
 * <p>Run as a bounded subprocess by {@code verifyRemoteInputLinux} (development, from the
 * repository build output). It resolves the artifact, verifies the adjacent provenance manifest and
 * the artifact/manifest contract, loads the library, confirms every required export is present, and
 * reports which build was loaded.
 *
 * <p>The packaged mode is retained for a future distribution that ships a licensed library again,
 * but no packaged verification task exists: the assembled distribution deliberately contains no
 * RemoteInput payload, so there is nothing packaged to verify (see {@code
 * docs/upstream-integration/sc-1662/native-loading-contract.md}).
 *
 * <p>The manifest is checked <b>before</b> the library is loaded. Loading is not side-effect free —
 * a load-time constructor self-instruments the process — and a pair whose manifest is absent or
 * inconsistent must fail closed rather than be reported as a successful verification.
 *
 * <p>It does <b>not</b> inject, pair, select a target, or send input, and it deliberately avoids
 * the upstream {@code RemoteInput(int pid)} constructor, which performs injection and pairing as a
 * side effect of construction. It also starts no application services: this class references no
 * Spring or {@code com.chromascape.web} type, which a test asserts.
 *
 * <p>Usage: {@code --library <abs path>} and/or {@code --app-root <abs dir>}, plus {@code --mode
 * <development|packaged>} and optional {@code --expect-sha256 <hex>}. With no library or root, the
 * configured system property / environment override is used.
 *
 * <p>On success it prints one machine-readable line, {@code SMOKE_REPORT <json>}, containing the
 * status, mode, canonical path, measured digest and size, the manifest revision and patch identity,
 * and the number of resolved symbols. A delimiter-free record is used because a whitespace
 * delimited one silently truncates any path containing a space.
 *
 * <p>Exit codes: {@code 0} success, {@code 1} resolution, manifest or loading failure, {@code 2}
 * bad usage.
 */
public final class RemoteInputSmokeVerifier {

  public static final String MODE_DEVELOPMENT = "development";
  public static final String MODE_PACKAGED = "packaged";

  private static final String EXIT_USAGE = "SMOKE_USAGE";
  private static final String MANIFEST_FILENAME = "remoteinput-manifest.json";
  private static final String REPORT_MARKER = "SMOKE_REPORT";
  private static final String STATUS_OK = "SMOKE_OK";

  private RemoteInputSmokeVerifier() {}

  /** Runs verification and terminates the JVM with its exit code. */
  public static void main(String[] args) {
    System.exit(run(args));
  }

  /**
   * Runs verification and returns an exit code instead of terminating the JVM, so the behaviour is
   * testable in-process.
   *
   * @return {@code 0} on success, {@code 1} on resolution/manifest/load failure, {@code 2} on bad
   *     usage
   */
  public static int run(String[] args) {
    Options options;
    try {
      options = Options.parse(args);
    } catch (UsageFailure usage) {
      System.err.println(EXIT_USAGE + " " + usage.getMessage());
      return 2;
    }

    try {
      if (options.library != null) {
        System.setProperty(
            NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY, options.library.toString());
      }

      Path canonical = new NativeArtifactResolver().resolve(options.applicationRoot);
      long measuredSizeBytes = measuredSizeOf(canonical);
      String measuredSha256 = sha256Of(canonical);

      Path manifestPath = locateManifest(canonical);
      if (manifestPath == null) {
        throw new RemoteInputArtifactException(
            RemoteInputDiagnostic.INVALID_ARTIFACT,
            canonical,
            NativeArtifactResolver.EXPECTED_DESCRIPTION,
            "no "
                + MANIFEST_FILENAME
                + " accompanies the artifact, so the claimed library/manifest contract cannot be"
                + " verified");
      }
      RemoteInputManifest manifest = RemoteInputManifest.read(manifestPath);

      if (!manifest.artifactSha256().equals(measuredSha256)) {
        throw new RemoteInputArtifactException(
            RemoteInputDiagnostic.INVALID_ARTIFACT,
            canonical,
            NativeArtifactResolver.EXPECTED_DESCRIPTION,
            "artifact sha256 does not match the adjacent manifest ("
                + measuredSha256
                + " != "
                + manifest.artifactSha256()
                + ")");
      }
      if (manifest.artifactSizeBytes() != measuredSizeBytes) {
        throw new RemoteInputArtifactException(
            RemoteInputDiagnostic.INVALID_ARTIFACT,
            canonical,
            NativeArtifactResolver.EXPECTED_DESCRIPTION,
            "artifact size does not match the adjacent manifest ("
                + measuredSizeBytes
                + " != "
                + manifest.artifactSizeBytes()
                + ")");
      }
      if (options.expectedSha256 != null && !options.expectedSha256.equals(measuredSha256)) {
        throw new RemoteInputArtifactException(
            RemoteInputDiagnostic.INVALID_ARTIFACT,
            canonical,
            NativeArtifactResolver.EXPECTED_DESCRIPTION,
            "artifact sha256 does not match the expected hash ("
                + measuredSha256
                + " != "
                + options.expectedSha256
                + ")");
      }

      // Reported only after the artifact loaded and every required symbol resolved.
      RemoteInputLibraryLoader.LoadedRemoteInput loaded =
          new RemoteInputLibraryLoader().load(canonical);

      System.out.println(
          report(
              options.mode, loaded.canonicalPath(), measuredSha256, measuredSizeBytes, manifest));
      System.out.flush();
      return 0;
    } catch (RemoteInputArtifactException failure) {
      System.err.println(
          "SMOKE_FAILED diagnostic=" + failure.diagnostic() + " message=" + failure.getMessage());
      if (failure.getCause() != null) {
        System.err.println("SMOKE_CAUSE " + failure.getCause());
      }
      return 1;
    } catch (Throwable unexpected) {
      System.err.println("SMOKE_FAILED diagnostic=LOAD_FAILED message=" + unexpected);
      return 1;
    }
  }

  /** The single machine-readable success record consumed by the Gradle verification tasks. */
  private static String report(
      String mode, Path canonical, String sha256, long sizeBytes, RemoteInputManifest manifest) {
    JsonObject report = new JsonObject();
    report.addProperty("status", STATUS_OK);
    report.addProperty("mode", mode);
    report.addProperty("canonical", canonical.toString());
    report.addProperty("sha256", sha256);
    report.addProperty("sizeBytes", sizeBytes);
    report.addProperty("revision", manifest.sourceRevision());
    report.addProperty("patch", manifest.localPatchIdentity());
    report.addProperty("symbols", RemoteInputLibraryLoader.REQUIRED_SYMBOLS.size());
    return REPORT_MARKER + " " + report;
  }

  /**
   * The manifest that belongs to the loaded artifact: always the artifact's sibling.
   *
   * <p>A managed artifact is canonicalised by {@link NativeArtifactResolver#resolve} into its
   * versioned payload directory, so its sibling is already the correct manifest. Preferring a
   * managed path derived from the application root would let an explicit library override be
   * verified against an unrelated manifest, and would silently accept a pair that does not match.
   */
  private static Path locateManifest(Path canonicalArtifact) {
    Path sibling = canonicalArtifact.resolveSibling(MANIFEST_FILENAME);
    return Files.isRegularFile(sibling) ? sibling : null;
  }

  static long measuredSizeOf(Path path) {
    try {
      return Files.size(path);
    } catch (IOException failure) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          path,
          NativeArtifactResolver.EXPECTED_DESCRIPTION,
          "artifact could not be measured",
          failure);
    }
  }

  static String sha256Of(Path path) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(path)) {
        byte[] buffer = new byte[1 << 16];
        int read;
        while ((read = in.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException failure) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          path,
          NativeArtifactResolver.EXPECTED_DESCRIPTION,
          "artifact could not be hashed",
          failure);
    }
  }

  private static final class UsageFailure extends Exception {
    private static final long serialVersionUID = 1L;

    UsageFailure(String message) {
      super(message);
    }
  }

  /** Parsed command line. */
  static final class Options {
    private final Path library;
    private final Path applicationRoot;
    private final String mode;
    private final String expectedSha256;

    private Options(Path library, Path applicationRoot, String mode, String expectedSha256) {
      this.library = library;
      this.applicationRoot = applicationRoot;
      this.mode = mode;
      this.expectedSha256 = expectedSha256;
    }

    static Options parse(String[] args) throws UsageFailure {
      Path library = null;
      Path appRoot = null;
      String mode = MODE_DEVELOPMENT;
      String expectedSha256 = null;

      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--library" -> library = Path.of(value(args, ++i, "--library"));
          case "--app-root" -> appRoot = Path.of(value(args, ++i, "--app-root"));
          case "--mode" -> mode = value(args, ++i, "--mode").toLowerCase(Locale.ROOT);
          case "--expect-sha256" -> expectedSha256 = value(args, ++i, "--expect-sha256");
          default -> throw new UsageFailure("unknown argument: " + args[i]);
        }
      }

      if (!MODE_DEVELOPMENT.equals(mode) && !MODE_PACKAGED.equals(mode)) {
        throw new UsageFailure("--mode must be 'development' or 'packaged', got: " + mode);
      }
      if (expectedSha256 != null && !expectedSha256.matches("[0-9a-f]{64}")) {
        throw new UsageFailure("--expect-sha256 must be a 64-character lowercase hex digest");
      }
      return new Options(library, appRoot, mode, expectedSha256);
    }

    private static String value(String[] args, int index, String option) throws UsageFailure {
      if (index >= args.length) {
        throw new UsageFailure(option + " requires a value");
      }
      return args[index];
    }

    String mode() {
      return mode;
    }
  }
}

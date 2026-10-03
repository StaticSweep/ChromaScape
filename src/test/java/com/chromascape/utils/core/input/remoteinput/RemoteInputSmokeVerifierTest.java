package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteInputSmokeVerifierTest {

  private static final String VERIFIER_RESOURCE =
      "/com/chromascape/utils/core/input/remoteinput/RemoteInputSmokeVerifier.class";
  private static final String REVISION = "5ea3b995f5d13e19c592a6399973fe7dadb5e656";
  private static final String MANIFEST_FILENAME = "remoteinput-manifest.json";

  @Test
  void unknownArgumentIsAUsageError() {
    assertEquals(2, RemoteInputSmokeVerifier.run(new String[] {"--not-an-option"}));
  }

  @Test
  void optionWithoutValueIsAUsageError() {
    assertEquals(2, RemoteInputSmokeVerifier.run(new String[] {"--mode"}));
  }

  @Test
  void unknownModeIsAUsageError() {
    assertEquals(
        2, RemoteInputSmokeVerifier.run(new String[] {"--mode", "ship-it", "--app-root", "/tmp"}));
  }

  @Test
  void malformedExpectedHashIsAUsageError() {
    assertEquals(2, RemoteInputSmokeVerifier.run(new String[] {"--expect-sha256", "not-a-hash"}));
  }

  @Test
  void modeDefaultsToDevelopmentAndAcceptsPackaged() throws Exception {
    assertEquals("development", RemoteInputSmokeVerifier.Options.parse(new String[] {}).mode());
    assertEquals(
        "packaged",
        RemoteInputSmokeVerifier.Options.parse(new String[] {"--mode", "packaged"}).mode());
    assertEquals(
        "packaged",
        RemoteInputSmokeVerifier.Options.parse(new String[] {"--mode", "PACKAGED"}).mode());
  }

  @Test
  void missingArtifactFailsWithoutReportingSuccess(@TempDir Path tmp) {
    // An empty application root has no native/linux-x86_64/libRemoteInput.so.
    int exitCode =
        RemoteInputSmokeVerifier.run(
            new String[] {"--app-root", tmp.toString(), "--mode", "development"});

    assertEquals(1, exitCode, "a missing artifact must not be reported as success");
  }

  /**
   * A manifest must be present. The check has to happen before the library is loaded, so that a
   * missing half of the pair cannot be reported as a successful verification.
   */
  @Test
  void absentManifestFailsBeforeTheLibraryIsLoaded(@TempDir Path tmp) throws IOException {
    assumeLinuxX86_64();
    Path root = writeDistribution(tmp, null);

    Transcript transcript = runCapturing("--app-root", root.toString(), "--mode", "development");

    assertEquals(1, transcript.exitCode(), transcript.all());
    assertTrue(transcript.all().contains("no " + MANIFEST_FILENAME), transcript.all());
    assertFalse(transcript.all().contains("SMOKE_OK"), transcript.all());
    assertFalse(
        transcript.all().contains("LOAD_FAILED"),
        "the manifest must be rejected before any native load: " + transcript.all());
  }

  /** A manifest that does not describe the artifact must fail, and must not reach the load step. */
  @Test
  void manifestThatDisagreesWithTheArtifactFailsBeforeLoading(@TempDir Path tmp)
      throws IOException {
    assumeLinuxX86_64();
    Path root = writeDistribution(tmp, disagreeingManifestJson());

    Transcript transcript = runCapturing("--app-root", root.toString(), "--mode", "development");

    assertEquals(1, transcript.exitCode(), transcript.all());
    assertTrue(transcript.all().contains("does not match the adjacent manifest"), transcript.all());
    assertFalse(
        transcript.all().contains("LOAD_FAILED"),
        "the manifest must be rejected before any native load: " + transcript.all());
  }

  /** An unsupported schema is a structural defect, not something to report and continue past. */
  @Test
  void structurallyInvalidManifestFailsBeforeLoading(@TempDir Path tmp) throws IOException {
    assumeLinuxX86_64();
    Path root = writeDistribution(tmp, unsupportedSchemaManifestJson());

    Transcript transcript = runCapturing("--app-root", root.toString(), "--mode", "development");

    assertEquals(1, transcript.exitCode(), transcript.all());
    assertTrue(transcript.all().contains("unsupported schemaVersion"), transcript.all());
    assertFalse(
        transcript.all().contains("LOAD_FAILED"),
        "the manifest must be rejected before any native load: " + transcript.all());
  }

  /**
   * An explicit library override must be verified against its own sibling manifest, never against a
   * managed manifest belonging to the application root. The managed manifest here is structurally
   * valid, so the old preference would have used it and reported a digest mismatch instead.
   */
  @Test
  void explicitLibraryIsVerifiedAgainstItsOwnSiblingManifest(@TempDir Path tmp) throws IOException {
    assumeLinuxX86_64();
    Path applicationRoot =
        writeDistribution(tmp.resolve("distribution"), disagreeingManifestJson());
    Path elsewhere = writeDistribution(tmp.resolve("elsewhere"), null);

    Transcript transcript;
    try {
      transcript =
          runCapturing(
              "--library",
              elsewhere.resolve("native/linux-x86_64/libRemoteInput.so").toString(),
              "--app-root",
              applicationRoot.toString(),
              "--mode",
              "development");
    } finally {
      System.clearProperty(NativeArtifactResolver.LIBRARY_SYSTEM_PROPERTY);
    }

    assertEquals(1, transcript.exitCode(), transcript.all());
    assertTrue(
        transcript.all().contains("no " + MANIFEST_FILENAME),
        "the application root's manifest must not be used for an explicit library: "
            + transcript.all());
  }

  /**
   * The verification entry point must not be able to start the application or invoke native input:
   * it references no Spring type, no {@code com.chromascape.web} type, and no {@code EIOS_*}
   * symbol. All native symbols live behind {@link RemoteInputInterface}, which this class never
   * touches.
   */
  @Test
  void verificationPathReferencesNoApplicationServicesOrNativeSymbols() throws IOException {
    String bytecode;
    try (InputStream in = RemoteInputSmokeVerifier.class.getResourceAsStream(VERIFIER_RESOURCE)) {
      assertNotNull(in, "verifier class file must be on the classpath");
      bytecode = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    }

    assertFalse(bytecode.contains("org/springframework"), "verification must not pull in Spring");
    assertFalse(
        bytecode.contains("com/chromascape/web"),
        "verification must not reference the web application");
    assertFalse(
        bytecode.contains("EIOS_"),
        "verification must not invoke native symbols directly (no attachment path)");
    assertFalse(
        bytecode.contains("RemoteInputInterface"),
        "verification must not bind the native interface");
  }

  private static void assumeLinuxX86_64() {
    Assumptions.assumeTrue(
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")
            && Set.of("x86_64", "amd64").contains(System.getProperty("os.arch", "")),
        "native artifact resolution is Linux x86_64 only");
  }

  /** Writes a well-formed ELF header at the managed native path, plus an optional manifest. */
  private static Path writeDistribution(Path applicationRoot, String manifestJson)
      throws IOException {
    Path nativeDir = applicationRoot.resolve("native/linux-x86_64");
    Files.createDirectories(nativeDir);
    byte[] header = new byte[64];
    header[0] = 0x7f;
    header[1] = 'E';
    header[2] = 'L';
    header[3] = 'F';
    header[4] = 2;
    header[5] = 1;
    header[16] = 3;
    header[18] = 62;
    Files.write(nativeDir.resolve("libRemoteInput.so"), header);
    if (manifestJson != null) {
      Files.writeString(nativeDir.resolve(MANIFEST_FILENAME), manifestJson, StandardCharsets.UTF_8);
    }
    return applicationRoot;
  }

  /** Structurally valid, but records a digest and size that cannot match the fixture library. */
  private static String disagreeingManifestJson() {
    return """
        {
          "schemaVersion": 1,
          "sourceRevision": "%s",
          "localPatchIdentity": "none",
          "artifact": { "sha256": "%s", "sizeBytes": 4096 }
        }
        """
        .formatted(REVISION, "a".repeat(64));
  }

  private static String unsupportedSchemaManifestJson() {
    return disagreeingManifestJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99");
  }

  private static Transcript runCapturing(String... args) {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
      int exitCode = RemoteInputSmokeVerifier.run(args);
      return new Transcript(
          exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }

  private record Transcript(int exitCode, String stdout, String stderr) {
    String all() {
      return "exit=" + exitCode + "\n--- stderr ---\n" + stderr + "\n--- stdout ---\n" + stdout;
    }
  }
}

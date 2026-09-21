package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteInputManifestTest {

  private static final String REVISION = "5ea3b995f5d13e19c592a6399973fe7dadb5e656";
  private static final String ARTIFACT_SHA = "b".repeat(64);

  /**
   * Includes the JDK version string that used to break the manifest: it contains quotes, and a
   * naive writer would have produced invalid JSON. The reader must tolerate it (and any other field
   * it does not need).
   */
  private static String manifestJson() {
    return """
        {
          "schemaVersion": 1,
          "generatedAtUtc": "2026-09-15T00:00:00Z",
          "sourceRepository": "https://example.invalid/repo",
          "sourceRevision": "%s",
          "localPatchIdentity": "none",
          "jdk": { "version": "openjdk version \\"17.0.20.1\\" 2026-08-18 LTS" },
          "artifact": {
            "filename": "libRemoteInput.so",
            "sizeBytes": 4096,
            "sha256": "%s",
            "elfClass": "ELF64",
            "elfMachine": "EM_X86_64",
            "elfType": "ET_DYN"
          }
        }
        """
        .formatted(REVISION, ARTIFACT_SHA);
  }

  private static Path write(Path path, String content) throws IOException {
    Files.writeString(path, content, StandardCharsets.UTF_8);
    return path;
  }

  @Test
  void readsTheReportedFields(@TempDir Path tmp) throws IOException {
    Path manifest = write(tmp.resolve("remoteinput-manifest.json"), manifestJson());

    RemoteInputManifest read = RemoteInputManifest.read(manifest);

    assertEquals(1, read.schemaVersion());
    assertEquals(REVISION, read.sourceRevision());
    assertEquals("none", read.localPatchIdentity());
    assertEquals(ARTIFACT_SHA, read.artifactSha256());
    assertEquals(4096L, read.artifactSizeBytes());
  }

  @Test
  void missingManifestIsRejected(@TempDir Path tmp) {
    RemoteInputArtifactException failure =
        assertThrows(
            RemoteInputArtifactException.class,
            () -> RemoteInputManifest.read(tmp.resolve("absent.json")));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
  }

  @Test
  void truncatedManifestIsRejected(@TempDir Path tmp) throws IOException {
    String full = manifestJson();
    Path manifest =
        write(tmp.resolve("remoteinput-manifest.json"), full.substring(0, full.length() / 2));

    RemoteInputArtifactException failure =
        assertThrows(RemoteInputArtifactException.class, () -> RemoteInputManifest.read(manifest));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
  }

  @Test
  void manifestWithoutArtifactObjectIsRejected(@TempDir Path tmp) throws IOException {
    Path manifest = write(tmp.resolve("remoteinput-manifest.json"), "{\"schemaVersion\": 1}");

    RemoteInputArtifactException failure =
        assertThrows(RemoteInputArtifactException.class, () -> RemoteInputManifest.read(manifest));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, failure.diagnostic());
  }

  @Test
  void unsupportedSchemaVersionIsRejected(@TempDir Path tmp) throws IOException {
    Path manifest =
        write(
            tmp.resolve("remoteinput-manifest.json"),
            manifestJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, readFailure(manifest).diagnostic());
  }

  @Test
  void malformedDigestIsRejected(@TempDir Path tmp) throws IOException {
    Path manifest =
        write(
            tmp.resolve("remoteinput-manifest.json"),
            manifestJson().replace(ARTIFACT_SHA, "B".repeat(64)));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, readFailure(manifest).diagnostic());
  }

  @Test
  void nonPositiveArtifactSizeIsRejected(@TempDir Path tmp) throws IOException {
    Path manifest =
        write(
            tmp.resolve("remoteinput-manifest.json"),
            manifestJson().replace("\"sizeBytes\": 4096", "\"sizeBytes\": 0"));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, readFailure(manifest).diagnostic());
  }

  @Test
  void malformedRevisionIsRejected(@TempDir Path tmp) throws IOException {
    Path manifest =
        write(
            tmp.resolve("remoteinput-manifest.json"),
            manifestJson().replace(REVISION, "not-a-revision"));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, readFailure(manifest).diagnostic());
  }

  @Test
  void blankPatchIdentityIsRejected(@TempDir Path tmp) throws IOException {
    Path manifest =
        write(
            tmp.resolve("remoteinput-manifest.json"),
            manifestJson()
                .replace("\"localPatchIdentity\": \"none\"", "\"localPatchIdentity\": \"  \""));

    assertEquals(RemoteInputDiagnostic.INVALID_ARTIFACT, readFailure(manifest).diagnostic());
  }

  private static RemoteInputArtifactException readFailure(Path manifest) {
    return assertThrows(
        RemoteInputArtifactException.class, () -> RemoteInputManifest.read(manifest));
  }
}

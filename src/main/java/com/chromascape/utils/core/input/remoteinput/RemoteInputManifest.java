package com.chromascape.utils.core.input.remoteinput;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The subset of {@code remoteinput-manifest.json} this story reports on.
 *
 * <p>The manifest is provenance, not a trust boundary: it is produced by the Linux build script
 * through a real JSON serializer and validated there. This reader exists so verification can report
 * <em>which</em> build was loaded (source revision and patch identity) rather than only that some
 * library loaded.
 *
 * <p>The manifest is nonetheless required to be structurally valid: verification must not report
 * success for a pair whose manifest is unsupported, malformed or incomplete, because that would
 * mean the claimed library/manifest contract was never checked.
 *
 * <p>Only these fields are read; unknown or additional fields are ignored so the manifest can
 * evolve without breaking this reader.
 *
 * @param schemaVersion manifest schema version
 * @param sourceRevision pinned upstream revision the artifact was built from
 * @param localPatchIdentity {@code none} for an unmodified pinned import
 * @param artifactSha256 SHA-256 the manifest records for the artifact
 * @param artifactSizeBytes byte size the manifest records for the artifact
 */
public record RemoteInputManifest(
    int schemaVersion,
    String sourceRevision,
    String localPatchIdentity,
    String artifactSha256,
    long artifactSizeBytes) {

  /** The only manifest schema version this reader understands. */
  public static final int SUPPORTED_SCHEMA_VERSION = 1;

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");

  /**
   * Rejects a manifest record that does not describe a plausible artifact pair.
   *
   * @throws IllegalArgumentException when the schema version is unsupported, the revision or digest
   *     is malformed, the recorded size is not positive, or a provenance value is blank
   */
  public RemoteInputManifest {
    Objects.requireNonNull(sourceRevision, "sourceRevision");
    Objects.requireNonNull(localPatchIdentity, "localPatchIdentity");
    Objects.requireNonNull(artifactSha256, "artifactSha256");
    if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "unsupported schemaVersion "
              + schemaVersion
              + "; supported: "
              + SUPPORTED_SCHEMA_VERSION);
    }
    if (!REVISION.matcher(sourceRevision).matches()) {
      throw new IllegalArgumentException(
          "sourceRevision must be a 40-character lowercase hex revision");
    }
    if (localPatchIdentity.isBlank()) {
      throw new IllegalArgumentException("localPatchIdentity must not be blank");
    }
    if (!SHA256.matcher(artifactSha256).matches()) {
      throw new IllegalArgumentException(
          "artifact sha256 must be a 64-character lowercase hex digest");
    }
    if (artifactSizeBytes <= 0) {
      throw new IllegalArgumentException("artifact sizeBytes must be positive");
    }
  }

  /**
   * Reads and validates a manifest.
   *
   * @throws RemoteInputArtifactException with {@code INVALID_ARTIFACT} when the file is missing,
   *     unreadable, not JSON, or does not satisfy the manifest contract
   */
  public static RemoteInputManifest read(Path manifestPath) {
    String text;
    try {
      text = Files.readString(manifestPath, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          manifestPath,
          "remoteinput-manifest.json",
          "manifest could not be read",
          unreadable);
    }

    try {
      JsonObject root = JsonParser.parseString(text).getAsJsonObject();
      JsonObject artifact = root.getAsJsonObject("artifact");
      if (artifact == null) {
        throw new IllegalStateException("manifest has no 'artifact' object");
      }
      return new RemoteInputManifest(
          root.get("schemaVersion").getAsInt(),
          root.get("sourceRevision").getAsString(),
          root.get("localPatchIdentity").getAsString(),
          artifact.get("sha256").getAsString(),
          artifact.get("sizeBytes").getAsLong());
    } catch (RemoteInputArtifactException alreadyCategorised) {
      throw alreadyCategorised;
    } catch (RuntimeException malformed) {
      throw new RemoteInputArtifactException(
          RemoteInputDiagnostic.INVALID_ARTIFACT,
          manifestPath,
          "remoteinput-manifest.json",
          "manifest is not valid JSON or does not satisfy the manifest contract ("
              + malformed.getMessage()
              + ")",
          malformed);
    }
  }
}

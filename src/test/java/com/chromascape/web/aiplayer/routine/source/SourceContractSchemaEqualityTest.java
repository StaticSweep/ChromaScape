package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * SC-810 cross-repository contract proof: the canonical {@code task-source-manifest/v1} JSON schema
 * is owned by Micro6 and mirrored byte-for-byte into ChromaScape. Any drift between the two files
 * fails this test, so neither repository can silently verify against a different contract.
 *
 * <p>The Micro6 checkout is located through {@code BUILDER_GOAL_MICRO6_PROJECT_DIR} (the same
 * variable the runtime uses), falling back only to the canonical Windows checkout. The test fails
 * closed when the mirror cannot be found — it never waives itself and never guesses a WSL mount.
 */
class SourceContractSchemaEqualityTest {

  private static final String SCHEMA_RESOURCE =
      "contracts/source/task-source-manifest-v1.schema.json";

  private static final String SCHEMA_IN_MICRO6 =
      "src/main/resources/micro6-contracts/task-source-manifest-v1.schema.json";

  /**
   * Pinned SHA-256 of the canonical schema; changing the contract must change this constant in both
   * repositories together (Micro6 pins the same value in TaskSourceManifestSchemaEqualityTest).
   */
  private static final String PINNED_SCHEMA_SHA256 =
      "64fc9939b8328562715fe9e4db8a1f3d70e600f171feae1137b1ef2cc7307990";

  /** Pinned SHA-256 of the shared canonical-vector fixture (pinned in both repositories). */
  private static final String PINNED_VECTORS_SHA256 =
      "6549e52cad22364fce78043ab9d1b5f4df39c4995bbd2b082a2c8d16d1f9cefa";

  /**
   * Deliberate public wire contract. Do not replace this with {@code values()}: new internal enum
   * values must be reviewed and mapped before they can cross the API boundary.
   */
  private static final Set<String> PUBLIC_SOURCE_FAILURE_CODES =
      Set.of(
          "SOURCE_UNAVAILABLE",
          "SOURCE_REDACTED",
          "SOURCE_PRUNED",
          "SOURCE_GENERATED_ONLY",
          "SOURCE_BUILD_MISMATCH",
          "SOURCE_MANIFEST_INVALID",
          "SOURCE_SIGNATURE_INVALID",
          "SOURCE_CONTENT_HASH_MISMATCH",
          "SOURCE_ACCESS_DENIED",
          "SOURCE_INDEX_PARTIAL",
          "SOURCE_SCHEMA_UNSUPPORTED",
          "SOURCE_FILE_NOT_FOUND",
          "SOURCE_SYMBOL_NOT_FOUND",
          "SOURCE_NETWORK_UNAVAILABLE",
          "SOURCE_RATE_LIMITED",
          "SOURCE_UNSAFE_PATH",
          "SOURCE_DIRTY_TREE",
          "SOURCE_PARSER_FAILURE",
          "SOURCE_PROMOTION_FAILURE",
          "SOURCE_AUDIT_CONTEXT_INVALID");

  /** Canonical project identity; verification is expected to run from native Windows. */
  private static final String WINDOWS_MICRO6_ROOT = "C:\\Dev\\IdeaProjects\\micro6";

  private static Path schemaUnder(String root) {
    return Path.of(root).resolve("runelite-client").resolve(SCHEMA_IN_MICRO6);
  }

  private static Path micro6Schema() {
    String configured = System.getenv("BUILDER_GOAL_MICRO6_PROJECT_DIR");
    if (configured == null || configured.isBlank()) {
      configured = System.getProperty("builder.goal.micro6.project-dir", "");
    }
    if (configured != null && !configured.isBlank()) {
      return schemaUnder(configured.trim());
    }
    // Nothing resolved: return the canonical location so the assertion reports it and fails closed.
    return schemaUnder(WINDOWS_MICRO6_ROOT);
  }

  @Test
  void canonicalSchemaIsByteIdenticalAcrossRepositories() throws Exception {
    Path mirrored = micro6Schema();
    assertTrue(
        Files.isRegularFile(mirrored),
        "Micro6 canonical schema is missing at "
            + mirrored
            + "; SC-810 requires both "
            + "repositories to share one byte-identical task-source-manifest/v1 contract");

    byte[] micro6Bytes = Files.readAllBytes(mirrored);
    assertEquals(
        PINNED_SCHEMA_SHA256,
        sha256(micro6Bytes),
        "canonical schema changed; update the pin in both repositories together");
    try (var stream = getClass().getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      assertTrue(stream != null, "ChromaScape mirror schema is missing on the classpath");
      byte[] mirrorBytes = stream.readAllBytes();
      assertEquals(
          PINNED_SCHEMA_SHA256,
          sha256(mirrorBytes),
          "ChromaScape mirror schema changed; update the pin in both repositories together");
      assertArrayEquals(
          micro6Bytes,
          mirrorBytes,
          "task-source-manifest/v1 schema must be byte-identical in Micro6 (owner) and "
              + "ChromaScape (mirror)");
    }
  }

  @Test
  void sharedCanonicalVectorsArePinned() throws Exception {
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("task-source-canonical-json-v1-vectors.json")) {
      assertTrue(stream != null, "shared canonical vectors must be on the test classpath");
      assertEquals(
          PINNED_VECTORS_SHA256,
          sha256(stream.readAllBytes()),
          "shared canonical vectors changed; update the pin in both repositories together");
    }
  }

  @Test
  void publicSourceFailureCodesHaveSchemaAndHttpMappings() throws Exception {
    Set<String> enumCodes =
        Arrays.stream(SourceManifestFailureCode.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    assertEquals(
        PUBLIC_SOURCE_FAILURE_CODES,
        enumCodes,
        "a source failure must be explicitly classified as public or mapped before controller serialization");

    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/source/source-error-v1.schema.json")) {
      assertTrue(stream != null, "source-error/v1 schema must be on the classpath");
      var schema = new ObjectMapper().readTree(stream);
      Set<String> schemaCodes = new java.util.HashSet<>();
      schema
          .path("properties")
          .path("code")
          .path("enum")
          .forEach(node -> schemaCodes.add(node.asText()));
      assertEquals(
          PUBLIC_SOURCE_FAILURE_CODES,
          schemaCodes,
          "source-error/v1 must list exactly public codes");
    }

    for (SourceManifestFailureCode code : SourceManifestFailureCode.values()) {
      SourceManifestException error = new SourceManifestException(code, "safe public message");
      assertTrue(error.status().is4xxClientError() || error.status().is5xxServerError());
      assertEquals(code, error.code());
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder hex = new StringBuilder();
    for (byte value : digest) {
      hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
    }
    return hex.toString();
  }
}

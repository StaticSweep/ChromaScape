package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskSourceManifestVerifierTest {
  @Test
  void preservesGeneratedMappingsBoundToAuthoredSource() throws Exception {
    ObjectNode manifest = manifestWithGeneratedMapping();
    KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    TaskSourceManifest verified = verifier(keyPair)
        .verify(SourceManifestTestFixtures.resealAndSign(manifest, keyPair)).manifest();
    assertEquals(1, verified.generatedMappings().size());
    assertEquals(java.util.List.of(SourceManifestTestFixtures.FILE_ID),
        verified.generatedMappings().get(0).authoredFileIds());
    assertThrows(UnsupportedOperationException.class,
        () -> verified.generatedMappings().get(0).authoredFileIds().add("other"));
  }

  @Test
  void rejectsMissingAndDuplicateGeneratedMappings() throws Exception {
    ObjectNode missing = SourceManifestTestFixtures.manifest();
    missing.remove("generated_mappings");
    assertMappingRejected(missing);
    ObjectNode duplicate = manifestWithGeneratedMapping();
    ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.get("generated_mappings"))
        .add(duplicate.get("generated_mappings").get(0).deepCopy());
    assertMappingRejected(duplicate);
  }

  @Test
  void rejectsInvalidMappingReferencesAndMetadata() throws Exception {
    for (String field : java.util.List.of("generated_file_id", "mapping_kind", "tool", "tool_version", "description")) {
      ObjectNode manifest = manifestWithGeneratedMapping();
      ((ObjectNode) manifest.get("generated_mappings").get(0)).put(field, "");
      assertMappingRejected(manifest);
    }
    ObjectNode unknown = manifestWithGeneratedMapping();
    ((ObjectNode) unknown.get("generated_mappings").get(0)).put("unexpected", true);
    assertMappingRejected(unknown);
    ObjectNode generatedAuthor = manifestWithGeneratedMapping();
    ((ObjectNode) generatedAuthor.get("generated_mappings").get(0))
        .putArray("authored_file_ids").add(SourceManifestTestFixtures.GENERATED_FILE_ID);
    assertMappingRejected(generatedAuthor);
    ObjectNode duplicateAuthor = manifestWithGeneratedMapping();
    ((ObjectNode) duplicateAuthor.get("generated_mappings").get(0))
        .putArray("authored_file_ids")
        .add(SourceManifestTestFixtures.FILE_ID).add(SourceManifestTestFixtures.FILE_ID);
    assertMappingRejected(duplicateAuthor);
  }

  private static ObjectNode manifestWithGeneratedMapping() throws Exception {
    return SourceManifestTestFixtures.signedManifestWithGeneratedMapping(
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
  }

  private static void assertMappingRejected(ObjectNode manifest) throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    SourceManifestTestFixtures.resealAndSign(manifest, keyPair);
    SourceManifestException error = assertThrows(SourceManifestException.class,
        () -> verifier(keyPair).verify(manifest));
    assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, error.code());
  }

  @Test
  void verifiesACompleteSignedV1Manifest() throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    TaskSourceManifestVerifier.Verified verified =
        verifier(keyPair).verify(SourceManifestTestFixtures.signedManifest(keyPair));

    assertEquals(SourceManifestContract.SCHEMA, verified.manifest().schema());
    assertEquals("EXAMPLE_TASK", verified.manifest().taskKey());
    assertEquals(1, verified.manifest().files().size());
    assertEquals(2, verified.manifest().symbols().size());
    assertTrue(verified.signatureVerified());
  }

  @Test
  void verifiesGeneratedMappingReferentialIntegrity() throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    TaskSourceManifestVerifier.Verified verified =
        verifier(keyPair)
            .verify(SourceManifestTestFixtures.signedManifestWithGeneratedMapping(keyPair));

    assertEquals(1, verified.manifest().generatedMappings().size());
    TaskSourceManifest.GeneratedMapping mapping = verified.manifest().generatedMappings().get(0);
    assertEquals(SourceManifestTestFixtures.GENERATED_FILE_ID, mapping.generatedFileId());
    assertEquals(List.of(SourceManifestTestFixtures.FILE_ID), mapping.authoredFileIds());
  }

  @Test
  void rejectsUnsignedV1EvenWhenItsCanonicalHashIsValid() {
    SourceManifestException exception =
        assertThrows(
            SourceManifestException.class,
            () -> new TaskSourceManifestVerifier().verify(SourceManifestTestFixtures.manifest()));
    assertEquals(SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID, exception.code());
  }

  @Test
  void rejectsUnknownSchemaBeforeCompatibilityParsing() {
    var unknown = SourceManifestTestFixtures.manifest();
    unknown.put("schema", "unknown-source-manifest/v1");

    SourceManifestException exception =
        assertThrows(
            SourceManifestException.class, () -> new TaskSourceManifestVerifier().verify(unknown));
    assertEquals(SourceManifestFailureCode.SOURCE_SCHEMA_UNSUPPORTED, exception.code());
  }

  @Test
  void rejectsChangedManifestBytesAndWrongSigningKey() throws Exception {
    KeyPair expected = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    ObjectNode manifest = SourceManifestTestFixtures.signedManifest(expected);
    manifest.put("task_family", "changed");

    SourceManifestException changed =
        assertThrows(SourceManifestException.class, () -> verifier(expected).verify(manifest));
    assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, changed.code());

    ObjectNode valid = SourceManifestTestFixtures.signedManifest(expected);
    SourceManifestException wrongKey =
        assertThrows(SourceManifestException.class, () -> verifier(wrong).verify(valid));
    assertEquals(SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID, wrongKey.code());
  }

  @Test
  void requiresEveryExpectedLaunchIdentityToMatch() throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    ObjectNode manifest = SourceManifestTestFixtures.signedManifest(keyPair);
    TaskSourceManifestVerifier.Verified verified = verifier(keyPair).verify(manifest);
    TaskSourceManifestVerifier.ExpectedIdentity expected =
        new TaskSourceManifestVerifier.ExpectedIdentity(
            "EXAMPLE_TASK",
            "micro6",
            "0123456789abcdef0123456789abcdef01234567",
            "fedcba9876543210fedcba9876543210fedcba98",
            "micro6-build-1",
            manifest.path("build").path("artifact_digest").asText(),
            verified.manifest().sourceSnapshotId(),
            verified.manifest().manifestHash());
    assertTrue(verifier(keyPair).verify(manifest, expected).signatureVerified());

    TaskSourceManifestVerifier.ExpectedIdentity wrongArtifact =
        new TaskSourceManifestVerifier.ExpectedIdentity(
            expected.taskKey(),
            expected.repositoryId(),
            expected.commitId(),
            expected.treeId(),
            expected.buildId(),
            "sha256:" + "0".repeat(64),
            expected.snapshotId(),
            expected.manifestHash());
    SourceManifestException exception =
        assertThrows(
            SourceManifestException.class, () -> verifier(keyPair).verify(manifest, wrongArtifact));
    assertEquals(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH, exception.code());
  }

  @Test
  void rejectsUnsafePathsAndInvalidRanges() throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    ObjectNode unsafePath = SourceManifestTestFixtures.signedManifest(keyPair);
    ((ObjectNode) unsafePath.path("files").get(0)).put("relative_path", "../escape.java");
    SourceManifestTestFixtures.resealAndSign(unsafePath, keyPair);
    SourceManifestException path =
        assertThrows(SourceManifestException.class, () -> verifier(keyPair).verify(unsafePath));
    assertEquals(SourceManifestFailureCode.SOURCE_UNSAFE_PATH, path.code());

    ObjectNode unsafeRange = SourceManifestTestFixtures.signedManifest(keyPair);
    ((ObjectNode) unsafeRange.path("symbols").get(1)).put("end_byte", 10_000_000);
    SourceManifestTestFixtures.resealAndSign(unsafeRange, keyPair);
    SourceManifestException range =
        assertThrows(SourceManifestException.class, () -> verifier(keyPair).verify(unsafeRange));
    assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, range.code());
  }

  private static TaskSourceManifestVerifier verifier(KeyPair keyPair) {
    return new TaskSourceManifestVerifier(
        new SourceManifestSignatureVerifier(keyPair.getPublic(), "test-key"));
  }

  @Test
  void sharedCanonicalVectorsAreByteIdentical() throws Exception {
    byte[] raw;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("task-source-canonical-json-v1-vectors.json")) {
      org.junit.jupiter.api.Assertions.assertNotNull(
          stream, "shared canonical vectors must be on the test classpath");
      raw = stream.readAllBytes();
    }
    // Pinned SHA-256 of the shared fixture: repository-to-repository byte identity is proven by
    // this test, not merely by a manual comparison.
    assertEquals(
        "6549e52cad22364fce78043ab9d1b5f4df39c4995bbd2b082a2c8d16d1f9cefa",
        hexSha256(raw),
        "shared canonical vectors changed; update the pin in both repositories together");
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.JsonNode document = mapper.readTree(raw);
    assertEquals(1, document.get("version").asInt());
    for (com.fasterxml.jackson.databind.JsonNode vector : document.get("vectors")) {
      String wire = vector.get("wire").asText();
      String canonical = vector.get("canonical").asText();
      String checksum = vector.get("checksum").asText();
      byte[] wireBytes = wire.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      com.fasterxml.jackson.databind.JsonNode parsed = SourceManifestCanonicalJson.parse(wireBytes);
      assertEquals(
          canonical,
          SourceManifestCanonicalJson.canonical(parsed),
          "canonical bytes differ for vector " + vector.get("name").asText());
      assertEquals(
          checksum,
          "sha256:" + hexSha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          "checksum differs for vector " + vector.get("name").asText());
      // Every production serialization path must emit the same bytes: the manifest hash path
      // (canonicalHashBytes excludes identity fields) and the detached signature path
      // (canonicalSignatureBytes excludes only the signature) both route through the same
      // canonicalizer, and the full-document canonical form must round-trip through the strict
      // parser unchanged.
      assertEquals(
          canonical,
          SourceManifestCanonicalJson.canonical(
              SourceManifestCanonicalJson.parse(
                  canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
          "canonical bytes must be a strict-parse fixpoint for vector "
              + vector.get("name").asText());
      assertEquals(
          SourceManifestCanonicalJson.canonical(parsed, java.util.Set.of()),
          SourceManifestCanonicalJson.canonical(parsed),
          "empty exclusion set must not change canonical bytes for vector "
              + vector.get("name").asText());
      assertEquals(
          SourceManifestCanonicalJson.canonical(parsed, (String) null),
          SourceManifestCanonicalJson.canonical(parsed),
          "null exclusion must not change canonical bytes for vector "
              + vector.get("name").asText());
    }
    // The manifest hash and signature production paths hash canonical bytes, not wire bytes:
    // prove both paths on a signed fixture so generation/signing can never drift from the
    // vector-pinned canonicalizer.
    KeyPair vectorKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    com.fasterxml.jackson.databind.node.ObjectNode signed =
        SourceManifestTestFixtures.signedManifest(vectorKeyPair);
    String manifestHash =
        SourceManifestTestFixtures.hash(SourceManifestContract.canonicalHashBytes(signed));
    assertEquals(
        signed.path("source_manifest_hash").asText(),
        manifestHash,
        "manifest hash production path must match the sealed fixture identity");
    byte[] signatureBytes = SourceManifestContract.canonicalSignatureBytes(signed);
    String signatureHash = SourceManifestTestFixtures.hash(signatureBytes);
    assertEquals(
        signed.path("signature").path("signed_bytes_hash").asText(),
        signatureHash,
        "signature production path must match the sealed signed_bytes_hash");
    assertEquals(
        new String(signatureBytes, java.nio.charset.StandardCharsets.UTF_8),
        SourceManifestCanonicalJson.canonical(
            SourceManifestCanonicalJson.parse(signatureBytes), java.util.Set.of("signature")),
        "signature bytes must be canonical-form vectors through the exclusion path");
    for (com.fasterxml.jackson.databind.JsonNode invalid : document.get("invalid")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              SourceManifestCanonicalJson.parse(
                  invalid.get("wire").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          "strict source JSON must reject vector " + invalid.get("name").asText());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> SourceManifestCanonicalJson.parse(new byte[] {(byte) 0xFF, (byte) 0xFE, 0x7B, 0x7D}),
        "strict source JSON must reject malformed UTF-8");
  }

  private static String hexSha256(byte[] bytes) throws Exception {
    byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder hex = new StringBuilder();
    for (byte value : digest) {
      hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
    }
    return hex.toString();
  }
}

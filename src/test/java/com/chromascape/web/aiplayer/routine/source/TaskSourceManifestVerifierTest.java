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
  void verifiesACompleteSignedV1Manifest() throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    TaskSourceManifestVerifier.Verified verified = verifier(keyPair).verify(
        SourceManifestTestFixtures.signedManifest(keyPair));

    assertEquals(SourceManifestContract.SCHEMA, verified.manifest().schema());
    assertEquals("EXAMPLE_TASK", verified.manifest().taskKey());
    assertEquals(1, verified.manifest().files().size());
    assertEquals(2, verified.manifest().symbols().size());
    assertTrue(verified.signatureVerified());
  }

  @Test
  void verifiesGeneratedMappingReferentialIntegrity() throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    TaskSourceManifestVerifier.Verified verified = verifier(keyPair).verify(
        SourceManifestTestFixtures.signedManifestWithGeneratedMapping(keyPair));

    assertEquals(1, verified.manifest().generatedMappings().size());
    TaskSourceManifest.GeneratedMapping mapping = verified.manifest().generatedMappings().get(0);
    assertEquals(SourceManifestTestFixtures.GENERATED_FILE_ID, mapping.generatedFileId());
    assertEquals(List.of(SourceManifestTestFixtures.FILE_ID), mapping.authoredFileIds());
  }

  @Test
  void rejectsUnsignedV1EvenWhenItsCanonicalHashIsValid() {
    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> new TaskSourceManifestVerifier().verify(SourceManifestTestFixtures.manifest()));
    assertEquals(SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID, exception.code());
  }

  @Test
  void rejectsUnknownSchemaBeforeCompatibilityParsing() {
    var unknown = SourceManifestTestFixtures.manifest();
    unknown.put("schema", "unknown-source-manifest/v1");

    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> new TaskSourceManifestVerifier().verify(unknown));
    assertEquals(SourceManifestFailureCode.SOURCE_SCHEMA_UNSUPPORTED, exception.code());
  }

  @Test
  void rejectsChangedManifestBytesAndWrongSigningKey() throws Exception {
    KeyPair expected = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    ObjectNode manifest = SourceManifestTestFixtures.signedManifest(expected);
    manifest.put("task_family", "changed");

    SourceManifestException changed = assertThrows(SourceManifestException.class,
        () -> verifier(expected).verify(manifest));
    assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, changed.code());

    ObjectNode valid = SourceManifestTestFixtures.signedManifest(expected);
    SourceManifestException wrongKey = assertThrows(SourceManifestException.class,
        () -> verifier(wrong).verify(valid));
    assertEquals(SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID, wrongKey.code());
  }

  @Test
  void requiresEveryExpectedLaunchIdentityToMatch() throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    ObjectNode manifest = SourceManifestTestFixtures.signedManifest(keyPair);
    TaskSourceManifestVerifier.Verified verified = verifier(keyPair).verify(manifest);
    TaskSourceManifestVerifier.ExpectedIdentity expected = new TaskSourceManifestVerifier.ExpectedIdentity(
        "EXAMPLE_TASK", "micro6", "0123456789abcdef0123456789abcdef01234567",
        "fedcba9876543210fedcba9876543210fedcba98", "micro6-build-1",
        manifest.path("build").path("artifact_digest").asText(),
        verified.manifest().sourceSnapshotId(), verified.manifest().manifestHash());
    assertTrue(verifier(keyPair).verify(manifest, expected).signatureVerified());

    TaskSourceManifestVerifier.ExpectedIdentity wrongArtifact = new TaskSourceManifestVerifier.ExpectedIdentity(
        expected.taskKey(), expected.repositoryId(), expected.commitId(), expected.treeId(),
        expected.buildId(), "sha256:" + "0".repeat(64), expected.snapshotId(), expected.manifestHash());
    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> verifier(keyPair).verify(manifest, wrongArtifact));
    assertEquals(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH, exception.code());
  }

  @Test
  void rejectsUnsafePathsAndInvalidRanges() throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    ObjectNode unsafePath = SourceManifestTestFixtures.signedManifest(keyPair);
    ((ObjectNode) unsafePath.path("files").get(0)).put("relative_path", "../escape.java");
    SourceManifestTestFixtures.resealAndSign(unsafePath, keyPair);
    SourceManifestException path = assertThrows(SourceManifestException.class,
        () -> verifier(keyPair).verify(unsafePath));
    assertEquals(SourceManifestFailureCode.SOURCE_UNSAFE_PATH, path.code());

    ObjectNode unsafeRange = SourceManifestTestFixtures.signedManifest(keyPair);
    ((ObjectNode) unsafeRange.path("symbols").get(1)).put("end_byte", 10_000_000);
    SourceManifestTestFixtures.resealAndSign(unsafeRange, keyPair);
    SourceManifestException range = assertThrows(SourceManifestException.class,
        () -> verifier(keyPair).verify(unsafeRange));
    assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, range.code());
  }

  private static TaskSourceManifestVerifier verifier(KeyPair keyPair) {
    return new TaskSourceManifestVerifier(
        new SourceManifestSignatureVerifier(keyPair.getPublic(), "test-key"));
  }
}

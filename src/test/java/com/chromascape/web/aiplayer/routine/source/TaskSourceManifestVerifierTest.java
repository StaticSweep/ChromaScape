package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.Test;

class TaskSourceManifestVerifierTest {
  @Test
  void verifiesDeterministicIdentityAndIndexedRanges() {
    TaskSourceManifestVerifier.Verified verified =
        new TaskSourceManifestVerifier(false, false, null)
            .verify(SourceManifestTestFixtures.manifest());

    assertEquals("EXAMPLE_TASK", verified.manifest().taskKey());
    assertEquals(1, verified.manifest().files().size());
    assertEquals(2, verified.manifest().symbols().size());
    assertEquals(SourceManifestTestFixtures.METHOD_SYMBOL_ID,
        verified.manifest().symbolsForFile(SourceManifestTestFixtures.FILE_ID).get(1)
            .sourceSymbolId());
    assertFalse(verified.signatureVerified());
  }

  @Test
  void rejectsChangedManifestBytesEvenWhenTheOldHashRemains() {
    ObjectNode manifest = SourceManifestTestFixtures.manifest();
    manifest.put("task_family", "changed");

    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> new TaskSourceManifestVerifier(false, false, null).verify(manifest));
    assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, exception.code());
  }

  @Test
  void verifiesEd25519SignatureAndRejectsTheWrongKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair expected = generator.generateKeyPair();
    KeyPair wrong = generator.generateKeyPair();
    ObjectNode manifest = SourceManifestTestFixtures.signedManifest(expected);

    TaskSourceManifestVerifier.Verified verified = new TaskSourceManifestVerifier(
        true, false, new SourceManifestSignatureVerifier(expected.getPublic(), "test-key"))
        .verify(manifest);
    assertTrue(verified.signatureVerified());

    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> new TaskSourceManifestVerifier(true, false,
            new SourceManifestSignatureVerifier(wrong.getPublic(), "test-key"))
            .verify(manifest));
    assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, exception.code());
  }

  @Test
  void legacyManifestsRemainReadableButAreMarkedLegacy() {
    TaskSourceManifestVerifier.Verified verified =
        new TaskSourceManifestVerifier(false, true, null)
            .verify(SourceManifestTestFixtures.legacyManifest());

    assertTrue(verified.legacy());
    assertFalse(verified.signatureVerified());
  }

  @Test
  void rejectsUnsafeModuleAndOutOfBoundsSymbol() {
    ObjectNode unsafeModule = SourceManifestTestFixtures.manifest();
    ((ObjectNode) unsafeModule.path("files").get(0)).put("module", "../escape");
    SourceManifestTestFixtures.reseal(unsafeModule);
    SourceManifestException moduleError = assertThrows(SourceManifestException.class,
        () -> new TaskSourceManifestVerifier(false, false, null).verify(unsafeModule));
    assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, moduleError.code());

    ObjectNode unsafeRange = SourceManifestTestFixtures.manifest();
    ((ObjectNode) unsafeRange.path("symbols").get(1)).put("end_byte", 10_000_000);
    SourceManifestTestFixtures.reseal(unsafeRange);
    SourceManifestException rangeError = assertThrows(SourceManifestException.class,
        () -> new TaskSourceManifestVerifier(false, false, null).verify(unsafeRange));
    assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, rangeError.code());
  }

  @Test
  void preservesStableUnavailableStates() {
    SourceManifestException exception = assertThrows(SourceManifestException.class,
        () -> new TaskSourceManifestVerifier(false, false, null)
            .verify(SourceManifestTestFixtures.unavailableManifest("PRUNED")));
    assertEquals(SourceManifestFailureCode.SOURCE_PRUNED, exception.code());
  }
}

package com.chromascape.web.aiplayer.contract;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CanonicalJsonHasherTest {
  @Test
  void equivalentDocumentsHaveSameHash() {
    assertEquals(
        CanonicalJsonHasher.hashUtf8("{\"b\":2,\"a\":\"x\\r\\ny\"}", null),
        CanonicalJsonHasher.hashUtf8("{ \"a\": \"x\\ny\", \"b\": 2 }", null));
  }

  @Test
  void rejectsDuplicateKeys() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalJsonHasher.parse("{\"a\":1,\"a\":2}".getBytes()));
  }

  @Test
  void rejectsFloatingPoint() {
    assertThrows(
        IllegalArgumentException.class, () -> CanonicalJsonHasher.parse("{\"a\":1.0}".getBytes()));
  }

  @Test
  void excludesDeclaredHash() {
    assertEquals(
        CanonicalJsonHasher.hashUtf8("{\"a\":1}", null),
        CanonicalJsonHasher.hashUtf8("{\"a\":1,\"hash\":\"sha256:bad\"}", "hash"));
  }
}

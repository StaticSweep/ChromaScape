package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.config.SourceTrustProperties;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourceAccessAndCacheTest {
  @Test
  void metadataAndContentTokensAreSeparate() {
    SourceTrustProperties trust = new SourceTrustProperties();
    trust.setEnvironment("production");
    SourceAccessPolicy policy = new SourceAccessPolicy("metadata", "content", trust);

    assertEquals("actor", policy.authorize("Bearer metadata", SourceAccessPolicy.Purpose.METADATA,
        "production", "actor", "manifest-read", "corr-1"));
    assertEquals("actor", policy.authorize("Bearer content", SourceAccessPolicy.Purpose.CONTENT,
        "production", "actor", "file-read", "corr-2"));
    assertThrows(SourceManifestException.class,
        () -> policy.authorize("Bearer content", SourceAccessPolicy.Purpose.METADATA,
            "production", "actor", "manifest-read", "corr-3"));
    assertThrows(SourceManifestException.class,
        () -> policy.authorize("Bearer metadata", SourceAccessPolicy.Purpose.CONTENT,
            "production", "actor", "file-read", "corr-4"));
  }

  @Test
  void cacheDefensivelyCopiesAndEvictsByBound() {
    VerifiedSourceContentCache cache = new VerifiedSourceContentCache(5);
    byte[] first = "one".getBytes(StandardCharsets.UTF_8);
    byte[] second = "two".getBytes(StandardCharsets.UTF_8);
    String firstHash = SourceManifestTestFixtures.hash(first);
    String secondHash = SourceManifestTestFixtures.hash(second);

    cache.put(firstHash, first);
    first[0] = 'X';
    assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), cache.get(firstHash).orElseThrow());
    cache.put(secondHash, second);

    assertTrue(cache.sizeBytes() <= 5);
    assertEquals(1, cache.size());
    assertThrows(SourceManifestException.class,
        () -> cache.put(firstHash, "different".getBytes(StandardCharsets.UTF_8)));
  }
}

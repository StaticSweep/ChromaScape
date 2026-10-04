package com.chromascape.web.artifact;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ImmutableArtifactStoreTest {
  @TempDir Path root;

  private ImmutableArtifactStore store() {
    return new ImmutableArtifactStore(root, 1024, 65536, new ObjectMapper());
  }

  private ImmutableArtifactStore.Manifest put(ImmutableArtifactStore s, String id, byte[] content) {
    return s.put(
        "owner",
        id,
        ImmutableArtifactStore.Kind.DATASET,
        content,
        "application/json",
        Map.of("runId", "run"),
        Map.of());
  }

  @Test
  void duplicatePublicationAndConflictingIdentity() {
    var s = store();
    byte[] bytes = "data".getBytes();
    var m = put(s, "id", bytes);
    assertEquals(m, put(s, "id", bytes));
    assertEquals(
        HttpStatus.CONFLICT,
        assertThrows(ResponseStatusException.class, () -> put(s, "id", "changed".getBytes()))
            .getStatusCode());
    assertArrayEquals(bytes, s.read("owner", "id"));
  }

  @Test
  void everyDeclaredArtifactKindCanBeStoredWithImmutableLineage() {
    var s = store();
    for (var kind : ImmutableArtifactStore.Kind.values()) {
      var m =
          s.put(
              "owner",
              kind.name(),
              kind,
              new byte[] {1},
              "application/octet-stream",
              Map.of("sourceRun", "exact"),
              Map.of());
      assertEquals(kind, s.get("owner", m.id()).kind());
      assertEquals("exact", m.lineage().get("sourceRun"));
    }
    assertEquals(ImmutableArtifactStore.Kind.values().length, s.list("owner").size());
  }

  @Test
  void corruptAndMissingContentNeverResolve() throws Exception {
    var s = store();
    var m = put(s, "id", new byte[] {1});
    Files.write(s.contentPath(m), new byte[] {2});
    assertEquals(
        "ARTIFACT_CORRUPT",
        assertThrows(ResponseStatusException.class, () -> s.get("owner", "id")).getReason());
    Files.delete(s.contentPath(m));
    assertThrows(ResponseStatusException.class, () -> s.read("owner", "id"));
  }

  @Test
  void dependencyIntegrityAndOwnerIsolation() throws Exception {
    var s = store();
    var source = put(s, "source", new byte[] {1});
    s.put(
        "owner",
        "model",
        ImmutableArtifactStore.Kind.MODEL,
        new byte[] {2},
        "application/json",
        Map.of("recordingId", "original"),
        Map.of("dataset", "source"));
    assertEquals("source", s.get("owner", "model").dependencies().get("dataset"));
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(ResponseStatusException.class, () -> s.read("other", "model"))
            .getStatusCode());
    assertTrue(s.list("other").isEmpty());
    Files.write(s.contentPath(source), new byte[] {3});
    assertThrows(ResponseStatusException.class, () -> s.get("owner", "model"));
  }

  @Test
  void oversizeAndQuotaRejectWithoutRemovingPublishedEvidence() {
    var s = new ImmutableArtifactStore(root, 1024, 1024, new ObjectMapper());
    put(s, "small", new byte[] {1});
    assertEquals(
        HttpStatus.PAYLOAD_TOO_LARGE,
        assertThrows(ResponseStatusException.class, () -> put(s, "large", new byte[1025]))
            .getStatusCode());
    assertEquals(
        HttpStatus.INSUFFICIENT_STORAGE,
        assertThrows(ResponseStatusException.class, () -> put(s, "quota", new byte[900]))
            .getStatusCode());
    assertArrayEquals(new byte[] {1}, s.read("owner", "small"));
  }

  @Test
  void interruptedPublishLeavesNoValidReferenceAndOnlyTempsAreCleaned() throws Exception {
    var s = store();
    var original = put(s, "original", new byte[] {1});
    Path temp = root.resolve("pending/interrupted.tmp");
    Files.write(temp, new byte[] {2});
    Path orphan = root.resolve("objects/00/" + "0".repeat(64));
    Files.createDirectories(orphan.getParent());
    Files.write(orphan, new byte[] {3});
    assertThrows(ResponseStatusException.class, () -> s.get("owner", "interrupted"));
    s.cleanTemporary();
    assertFalse(Files.exists(temp));
    assertTrue(Files.exists(orphan));
    assertArrayEquals(new byte[] {1}, s.read("owner", original.id()));
  }

  @Test
  void concurrentWritersReuseOneImmutableReference() throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    try {
      var a = executor.submit(() -> put(store(), "id", new byte[] {1}));
      var b = executor.submit(() -> put(store(), "id", new byte[] {1}));
      assertEquals(a.get(), b.get());
      assertEquals(1, store().list("owner").size());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void developmentResetIsExplicitAndOwnerScoped() {
    var s = store();
    var m = put(s, "id", new byte[] {1});
    s.put("other", "id", m.kind(), new byte[] {1}, m.mediaType(), m.lineage(), Map.of());
    assertThrows(ResponseStatusException.class, () -> s.resetDevelopment("owner", false));
    s.resetDevelopment("owner", true);
    assertThrows(ResponseStatusException.class, () -> s.get("owner", "id"));
    assertArrayEquals(new byte[] {1}, s.read("other", "id"));
  }

  @Test
  void malformedMetadataAndPathsFailClosed() throws Exception {
    var s = store();
    assertThrows(ResponseStatusException.class, () -> put(s, "../escape", new byte[] {1}));
    put(s, "id", new byte[] {1});
    try (var files = Files.walk(root.resolve("refs"))) {
      Path ref = files.filter(Files::isRegularFile).findFirst().orElseThrow();
      Files.writeString(ref, "{}");
    }
    assertThrows(ResponseStatusException.class, () -> s.get("owner", "id"));
  }

  @Test
  void quarantineAndIncompleteStatesNeverResolveAsValidArtifacts() {
    var s = store();
    assertEquals(ImmutableArtifactStore.State.INCOMPLETE, s.state("owner", "missing"));
    put(s, "id", new byte[] {1});
    s.quarantine("owner", "id");
    assertEquals(ImmutableArtifactStore.State.QUARANTINED, store().state("owner", "id"));
    assertThrows(ResponseStatusException.class, () -> s.read("owner", "id"));
  }

  @Test
  void symlinkDirectoryCannotRedirectPublication() throws Exception {
    var s = store();
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.createSymbolicLink(root.resolve("objects"), outside);
    assertThrows(ResponseStatusException.class, () -> put(s, "id", new byte[] {1}));
    try (var files = Files.list(outside)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void symlinkReferenceDirectoryCannotRedirectReset() throws Exception {
    var s = store();
    var m = put(s, "id", new byte[] {1});
    Path refs =
        root.resolve("refs")
            .resolve(ImmutableArtifactStore.checksum("owner".getBytes()).substring(7));
    Files.delete(refs.resolve("id.json"));
    Files.delete(refs);
    Path outside = Files.createDirectory(root.resolve("outside"));
    Path protectedFile = Files.write(outside.resolve("id.json"), new byte[] {7});
    Files.createSymbolicLink(refs, outside);
    assertThrows(ResponseStatusException.class, () -> s.resetDevelopment("owner", true));
    assertThrows(ResponseStatusException.class, () -> s.list("owner"));
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(protectedFile));
  }

  @Test
  void invalidKnowledgeActivationPreservesPreviousValidatedDigestAcrossRestart() throws Exception {
    var s = store();
    var source = put(s, "source", new byte[] {1});
    var first =
        s.put(
            "owner",
            "snapshot1",
            ImmutableArtifactStore.Kind.KNOWLEDGE_SNAPSHOT,
            new byte[] {2},
            "application/json",
            Map.of("sourceRun", "run"),
            Map.of("corpus", "source"));
    var reference = s.activateKnowledgeSnapshot("owner", first.id());
    assertEquals(first.checksum(), reference.checksum());
    assertThrows(
        ResponseStatusException.class, () -> s.activateKnowledgeSnapshot("owner", "source"));
    var candidate =
        s.put(
            "owner",
            "snapshot2",
            ImmutableArtifactStore.Kind.KNOWLEDGE_SNAPSHOT,
            new byte[] {3},
            "application/json",
            Map.of("sourceRun", "run"),
            Map.of("corpus", "source"));
    Files.write(s.contentPath(candidate), new byte[] {4});
    assertThrows(
        ResponseStatusException.class, () -> s.activateKnowledgeSnapshot("owner", candidate.id()));
    assertEquals(reference, store().activeKnowledgeSnapshot("owner"));
    assertThrows(ResponseStatusException.class, () -> s.activeKnowledgeSnapshot("other"));
    Files.write(s.contentPath(source), new byte[] {5});
    assertThrows(ResponseStatusException.class, () -> s.activeKnowledgeSnapshot("owner"));
  }
}

package com.chromascape.web.artifact;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ImmutableArtifactStoreTest {
  @TempDir Path root;

  private ImmutableArtifactStore store() {
    return new ImmutableArtifactStore(
        root, 1024, 65536, new ObjectMapper(), TestDurability.positiveMode());
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
    var s =
        new ImmutableArtifactStore(
            root, 1024, 1024, new ObjectMapper(), TestDurability.positiveMode());
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

  @Test
  void strictLogicForcesEveryWritePathThroughInjectedPrimitive() throws Exception {
    var forced = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    var force =
        (ImmutableArtifactStore.DirectoryForce)
            directory -> forced.add(directory.getFileName().toString());
    var s =
        new ImmutableArtifactStore(
            root,
            1024,
            65536,
            new ObjectMapper(),
            ImmutableArtifactStore.DurabilityMode.STRICT,
            force);
    assertTrue(s.durabilityQualified());
    put(s, "id", new byte[] {1});
    var snapshot =
        s.put(
            "owner",
            "snapshot",
            ImmutableArtifactStore.Kind.KNOWLEDGE_SNAPSHOT,
            new byte[] {2},
            "application/json",
            Map.of("sourceRun", "run"),
            Map.of("corpus", "id"));
    s.activateKnowledgeSnapshot("owner", snapshot.id());
    s.quarantine("owner", "id");
    s.resetDevelopment("owner", true);
    Files.writeString(root.resolve("pending").resolve("stale.tmp"), "x");
    s.cleanTemporary();
    assertFalse(Files.exists(root.resolve("pending").resolve("stale.tmp")));
    assertTrue(forced.contains("pending"));
    assertTrue(forced.size() >= 8);
    assertTrue(forced.stream().anyMatch(name -> name.length() == 64));
  }

  private static void forceNative(Path directory) throws java.io.IOException {
    try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static ImmutableArtifactStore.DirectoryForce failOnRefs() {
    return directory -> {
      if (directory.toString().contains(java.io.File.separator + "refs"))
        throw new AccessDeniedException(directory.toString());
      forceNative(directory);
    };
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void strictModeRefusesUnsupportedPlatformBeforeAnyRegistryWrite() {
    var exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                new ImmutableArtifactStore(
                    root,
                    1024,
                    65536,
                    new ObjectMapper(),
                    ImmutableArtifactStore.DurabilityMode.STRICT));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    assertTrue(String.valueOf(exception.getReason()).startsWith("ARTIFACT_DURABILITY_UNAVAILABLE"));
    assertFalse(Files.exists(root.resolve("objects")));
    assertFalse(Files.exists(root.resolve("refs")));
    assertFalse(Files.exists(root.resolve("durability.json")));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void developmentRootIsExplicitlyMarkedUnqualified() throws Exception {
    var s =
        new ImmutableArtifactStore(
            root,
            1024,
            65536,
            new ObjectMapper(),
            ImmutableArtifactStore.DurabilityMode.DEVELOPMENT);
    assertFalse(s.durabilityQualified());
    var marker = Files.readString(root.resolve("durability.json"));
    assertTrue(marker.contains("\"qualified\":false"));
    put(s, "id", new byte[] {1});
    assertArrayEquals(new byte[] {1}, s.read("owner", "id"));
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void strictRootIsMarkedQualifiedForcedAndBootstrapped() throws Exception {
    var s =
        new ImmutableArtifactStore(
            root, 1024, 65536, new ObjectMapper(), ImmutableArtifactStore.DurabilityMode.STRICT);
    assertTrue(s.durabilityQualified());
    assertTrue(Files.readString(root.resolve("durability.json")).contains("\"qualified\":true"));
    var m = put(s, "id", new byte[] {1});
    var snapshot =
        s.put(
            "owner",
            "snapshot",
            ImmutableArtifactStore.Kind.KNOWLEDGE_SNAPSHOT,
            new byte[] {2},
            "application/json",
            Map.of("sourceRun", "run"),
            Map.of("corpus", "id"));
    var reference = s.activateKnowledgeSnapshot("owner", snapshot.id());
    s.quarantine("owner", "id");
    assertEquals(reference, s.activeKnowledgeSnapshot("owner"));
    assertEquals(ImmutableArtifactStore.State.QUARANTINED, s.state("owner", m.id()));
    s.resetDevelopment("owner", true);
    assertThrows(ResponseStatusException.class, () -> s.get("owner", "snapshot"));
    Files.delete(root.resolve("durability.json"));
    var restarted =
        new ImmutableArtifactStore(
            root, 1024, 65536, new ObjectMapper(), ImmutableArtifactStore.DurabilityMode.STRICT);
    assertTrue(restarted.durabilityQualified());
    assertTrue(Files.readString(root.resolve("durability.json")).contains("\"qualified\":true"));
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void strictRefusesDevelopmentRootAndDevelopmentRefusesQualifiedRoot() {
    new ImmutableArtifactStore(
        root, 1024, 65536, new ObjectMapper(), ImmutableArtifactStore.DurabilityMode.DEVELOPMENT);
    var refused =
        assertThrows(
            ResponseStatusException.class,
            () ->
                new ImmutableArtifactStore(
                    root,
                    1024,
                    65536,
                    new ObjectMapper(),
                    ImmutableArtifactStore.DurabilityMode.STRICT));
    assertTrue(String.valueOf(refused.getReason()).startsWith("ARTIFACT_DURABILITY_UNAVAILABLE"));
    var qualified = root.resolve("qualified");
    new ImmutableArtifactStore(
        qualified, 1024, 65536, new ObjectMapper(), ImmutableArtifactStore.DurabilityMode.STRICT);
    var conflict =
        assertThrows(
            ResponseStatusException.class,
            () ->
                new ImmutableArtifactStore(
                    qualified,
                    1024,
                    65536,
                    new ObjectMapper(),
                    ImmutableArtifactStore.DurabilityMode.DEVELOPMENT));
    assertEquals("ARTIFACT_DURABILITY_CONFLICT", conflict.getReason());
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void forceFailureAfterObjectPublicationLeavesNoReferenceAndRetainsSharedContent() {
    var writer =
        new ImmutableArtifactStore(
            root, 1024, 65536, new ObjectMapper(), ImmutableArtifactStore.DurabilityMode.STRICT);
    var shared = put(writer, "shared", new byte[] {1});
    var failing =
        new ImmutableArtifactStore(
            root,
            1024,
            65536,
            new ObjectMapper(),
            ImmutableArtifactStore.DurabilityMode.STRICT,
            failOnRefs());
    var exception =
        assertThrows(ResponseStatusException.class, () -> put(failing, "orphan", new byte[] {9}));
    assertTrue(String.valueOf(exception.getReason()).startsWith("ARTIFACT_DURABILITY_UNAVAILABLE"));
    assertThrows(ResponseStatusException.class, () -> failing.get("owner", "orphan"));
    var orphanChecksum = ImmutableArtifactStore.checksum(new byte[] {9}).substring(7);
    assertTrue(
        Files.exists(
            root.resolve("objects")
                .resolve(orphanChecksum.substring(0, 2))
                .resolve(orphanChecksum)));
    assertArrayEquals(new byte[] {1}, writer.read("owner", shared.id()));
    assertTrue(Files.exists(writer.contentPath(shared)));
    var retried =
        new ImmutableArtifactStore(
            root, 1024, 65536, new ObjectMapper(), ImmutableArtifactStore.DurabilityMode.STRICT);
    var recovered = put(retried, "orphan", new byte[] {9});
    assertEquals("sha256:" + orphanChecksum, recovered.checksum());
    assertArrayEquals(new byte[] {9}, retried.read("owner", "orphan"));
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void forceFailureDuringActivationSurfacesDurabilityErrorAndRetryIsIdempotent() throws Exception {
    var armed = new AtomicBoolean(false);
    var force =
        (ImmutableArtifactStore.DirectoryForce)
            directory -> {
              if (armed.get() && directory.toString().contains(java.io.File.separator + "refs"))
                throw new AccessDeniedException(directory.toString());
              forceNative(directory);
            };
    var s =
        new ImmutableArtifactStore(
            root,
            1024,
            65536,
            new ObjectMapper(),
            ImmutableArtifactStore.DurabilityMode.STRICT,
            force);
    put(s, "source", new byte[] {1});
    var first =
        s.put(
            "owner",
            "snapshot1",
            ImmutableArtifactStore.Kind.KNOWLEDGE_SNAPSHOT,
            new byte[] {2},
            "application/json",
            Map.of("sourceRun", "run"),
            Map.of("corpus", "source"));
    var second =
        s.put(
            "owner",
            "snapshot2",
            ImmutableArtifactStore.Kind.KNOWLEDGE_SNAPSHOT,
            new byte[] {3},
            "application/json",
            Map.of("sourceRun", "run"),
            Map.of("corpus", "source"));
    var reference = s.activateKnowledgeSnapshot("owner", first.id());
    armed.set(true);
    var exception =
        assertThrows(
            ResponseStatusException.class, () -> s.activateKnowledgeSnapshot("owner", second.id()));
    assertTrue(String.valueOf(exception.getReason()).startsWith("ARTIFACT_DURABILITY_UNAVAILABLE"));
    armed.set(false);
    assertEquals(second.checksum(), s.activateKnowledgeSnapshot("owner", second.id()).checksum());
    assertNotEquals(reference.checksum(), second.checksum());
    assertEquals(second.checksum(), s.activeKnowledgeSnapshot("owner").checksum());
    assertArrayEquals(new byte[] {2}, s.read("owner", first.id()));
  }
}

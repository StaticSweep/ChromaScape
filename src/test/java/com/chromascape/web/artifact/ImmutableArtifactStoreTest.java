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
    assertEquals(reference, s.activeKnowledgeSnapshot("owner"));
    s.quarantine("owner", "id");
    assertThrows(ResponseStatusException.class, () -> s.activeKnowledgeSnapshot("owner"));
    assertEquals(ImmutableArtifactStore.State.QUARANTINED, s.state("owner", m.id()));
    s.resetDevelopment("owner", true);
    assertThrows(ResponseStatusException.class, () -> s.get("owner", "snapshot"));
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

  private static final ImmutableArtifactStore.DirectoryForce NOOP = directory -> {};

  private ImmutableArtifactStore store(
      Path at, ImmutableArtifactStore.DurabilityMode mode, ImmutableArtifactStore.DirectoryForce f) {
    return new ImmutableArtifactStore(at, 1024, 65536, new ObjectMapper(), mode, f);
  }

  @Test
  void populatedRootWithDeletedOrNullMarkerIsNeverPromoted() throws Exception {
    var dev = store(root, ImmutableArtifactStore.DurabilityMode.DEVELOPMENT, NOOP);
    put(dev, "id", new byte[] {1});
    Files.delete(root.resolve("durability.json"));
    for (var mode : ImmutableArtifactStore.DurabilityMode.values()) {
      var missing = assertThrows(ResponseStatusException.class, () -> store(root, mode, NOOP));
      assertEquals("ARTIFACT_DURABILITY_MARKER_MISSING", missing.getReason());
    }
    Files.writeString(root.resolve("durability.json"), "null");
    for (var mode : ImmutableArtifactStore.DurabilityMode.values()) {
      var nulled = assertThrows(ResponseStatusException.class, () -> store(root, mode, NOOP));
      assertEquals("ARTIFACT_DURABILITY_MARKER_CORRUPT", nulled.getReason());
    }
    Files.writeString(
        root.resolve("durability.json"),
        "{\"mode\":\"DEVELOPMENT\",\"qualified\":true,\"directoryForce\":true}");
    var inconsistent =
        assertThrows(
            ResponseStatusException.class,
            () -> store(root, ImmutableArtifactStore.DurabilityMode.STRICT, NOOP));
    assertEquals("ARTIFACT_DURABILITY_MARKER_CORRUPT", inconsistent.getReason());
    assertTrue(Files.exists(root.resolve("objects")), "forensic content is retained");
  }

  @Test
  void markerDeletedAfterAdmissionFailsLaterOperations() throws Exception {
    var s = store(root, ImmutableArtifactStore.DurabilityMode.STRICT, NOOP);
    put(s, "id", new byte[] {1});
    Files.delete(root.resolve("durability.json"));
    var e = assertThrows(ResponseStatusException.class, () -> s.read("owner", "id"));
    assertEquals("ARTIFACT_DURABILITY_CONFLICT", e.getReason());
  }

  @Test
  void concurrentOppositeModeAdmissionAdmitsExactlyOneMode() throws Exception {
    for (int round = 0; round < 20; round++) {
      Path at = root.resolve("race" + round);
      var gate = new java.util.concurrent.CyclicBarrier(2);
      var pool = Executors.newFixedThreadPool(2);
      try {
        var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
        for (var mode : ImmutableArtifactStore.DurabilityMode.values())
          results.add(
              pool.submit(
                  () -> {
                    gate.await();
                    try {
                      store(at, mode, NOOP);
                      return true;
                    } catch (ResponseStatusException refused) {
                      return false;
                    }
                  }));
        int admittedCount = 0;
        for (var r : results) if (r.get()) admittedCount++;
        assertEquals(1, admittedCount, "exactly one mode may be admitted");
      } finally {
        pool.shutdownNow();
      }
      var marker = Files.readString(at.resolve("durability.json"));
      var winner =
          marker.contains("\"qualified\":true")
              ? ImmutableArtifactStore.DurabilityMode.STRICT
              : ImmutableArtifactStore.DurabilityMode.DEVELOPMENT;
      assertTrue(store(at, winner, NOOP).durabilityMode() == winner);
    }
  }

  @Test
  void concurrentSameModeAdmissionBothSucceed() throws Exception {
    var gate = new java.util.concurrent.CyclicBarrier(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 2; i++)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  return store(root, ImmutableArtifactStore.DurabilityMode.STRICT, NOOP);
                }));
      for (var f : futures) assertNotNull(f.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void freshNestedRootForcesEveryChangedParentInOrder() throws Exception {
    var forced = java.util.Collections.synchronizedList(new java.util.ArrayList<Path>());
    Path nested = root.resolve("a").resolve("b").resolve("store");
    var s =
        store(nested, ImmutableArtifactStore.DurabilityMode.STRICT, d -> forced.add(d.normalize()));
    // Trusted boundary probe first, then each new entry's parent in creation order.
    assertEquals(root, forced.get(0));
    assertEquals(
        java.util.List.of(root, root, root.resolve("a"), root.resolve("a").resolve("b")),
        forced.subList(0, 4));
    assertTrue(forced.indexOf(nested) > 3, "root forced after pending and marker are created");
    forced.clear();
    put(s, "id", new byte[] {1});
    var objects = nested.resolve("objects");
    var refs = nested.resolve("refs");
    assertTrue(forced.indexOf(nested) >= 0 && forced.indexOf(nested) < forced.indexOf(objects));
    assertTrue(forced.contains(objects) && forced.contains(refs));
    assertTrue(
        forced.stream().filter(nested::equals).count() >= 2,
        "root forced again after objects and refs entries were created");
  }

  @Test
  void parentForceFailureDuringAdmissionLeavesNoMarker() {
    Path nested = root.resolve("x").resolve("store");
    var failing =
        (ImmutableArtifactStore.DirectoryForce)
            d -> {
              if (d.normalize().equals(root.resolve("x")))
                throw new AccessDeniedException(d.toString());
            };
    var e =
        assertThrows(
            ResponseStatusException.class,
            () -> store(nested, ImmutableArtifactStore.DurabilityMode.STRICT, failing));
    assertTrue(String.valueOf(e.getReason()).startsWith("ARTIFACT_DURABILITY_UNAVAILABLE"));
    assertFalse(Files.exists(nested.resolve("durability.json")));
  }

  @Test
  void legacyRootCanOnlyBeAdoptedAsReviewedDevelopmentAndNeverPromoted() throws Exception {
    var dev = store(root, ImmutableArtifactStore.DurabilityMode.DEVELOPMENT, NOOP);
    put(dev, "id", new byte[] {1});
    Files.delete(root.resolve("durability.json"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            ImmutableArtifactStore.adoptLegacyRootAsDevelopment(
                root, 1024, 65536, new ObjectMapper(), " "));
    var adopted =
        ImmutableArtifactStore.adoptLegacyRootAsDevelopment(
            root, 1024, 65536, new ObjectMapper(), "REVIEW-1");
    assertFalse(adopted.durabilityQualified());
    assertTrue(Files.readString(root.resolve("durability.json")).contains("REVIEW-1"));
    assertArrayEquals(new byte[] {1}, adopted.read("owner", "id"));
    var refused =
        assertThrows(
            ResponseStatusException.class,
            () -> store(root, ImmutableArtifactStore.DurabilityMode.STRICT, NOOP));
    assertTrue(String.valueOf(refused.getReason()).startsWith("ARTIFACT_DURABILITY_UNAVAILABLE"));
  }

  @Test
  void initializationRacingOperationsNeverObservesAnotherMode() throws Exception {
    for (int round = 0; round < 10; round++) {
      Path at = root.resolve("op" + round);
      var strict = store(at, ImmutableArtifactStore.DurabilityMode.STRICT, NOOP);
      var gate = new java.util.concurrent.CyclicBarrier(2);
      var pool = Executors.newFixedThreadPool(2);
      try {
        var init =
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    store(at, ImmutableArtifactStore.DurabilityMode.DEVELOPMENT, NOOP);
                    return "admitted";
                  } catch (ResponseStatusException e) {
                    return e.getReason();
                  }
                });
        var op =
            pool.submit(
                () -> {
                  gate.await();
                  return put(strict, "id" + System.nanoTime(), new byte[] {1});
                });
        assertEquals("ARTIFACT_DURABILITY_CONFLICT", init.get());
        assertNotNull(op.get());
      } finally {
        pool.shutdownNow();
      }
      assertTrue(Files.readString(at.resolve("durability.json")).contains("\"qualified\":true"));
    }
  }

  @Test
  void everyParentForceFailureDuringAdmissionIsRefusedWithoutMarker() {
    for (int i = 0; i < 4; i++) {
      Path base = root.resolve("case" + i);
      Path nested = base.resolve("a").resolve("b").resolve("store");
      Path failAt =
          java.util.List.of(root, base, base.resolve("a"), base.resolve("a").resolve("b")).get(i);
      var failing =
          (ImmutableArtifactStore.DirectoryForce)
              d -> {
                if (d.normalize().equals(failAt)) throw new AccessDeniedException(d.toString());
              };
      var e =
          assertThrows(
              ResponseStatusException.class,
              () -> store(nested, ImmutableArtifactStore.DurabilityMode.STRICT, failing));
      assertTrue(
          String.valueOf(e.getReason()).startsWith("ARTIFACT_DURABILITY_UNAVAILABLE"),
          failAt.toString());
      assertFalse(Files.exists(nested.resolve("durability.json")), failAt.toString());
    }
  }

  @Test
  void emptyObjectsDirectoryWithoutMarkerCountsAsPopulated() throws Exception {
    Files.createDirectories(root.resolve("objects"));
    for (var mode : ImmutableArtifactStore.DurabilityMode.values())
      assertEquals(
          "ARTIFACT_DURABILITY_MARKER_MISSING",
          assertThrows(ResponseStatusException.class, () -> store(root, mode, NOOP)).getReason());
  }

  @Test
  void separateProcessesRacingOppositeModesAdmitExactlyOne() throws Exception {
    for (int round = 0; round < 5; round++) {
      Path at = root.resolve("proc" + round);
      Path go = root.resolve("go" + round);
      var processes = new java.util.ArrayList<Process>();
      // A long classpath exceeds the Windows command-line limit, so it travels in an argument file.
      Path args = root.resolve("args" + round);
      Files.writeString(
          args,
          "-cp \""
              + System.getProperty("java.class.path").replace("\\", "\\\\")
              + "\"\n");
      for (var mode : ImmutableArtifactStore.DurabilityMode.values())
        processes.add(
            new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "@" + args,
                    CrossProcessAdmissionMain.class.getName(),
                    at.toString(),
                    mode.name(),
                    go.toString())
                .redirectErrorStream(true)
                .start());
      Thread.sleep(1500);
      Files.createFile(go);
      int admittedCount = 0;
      for (Process process : processes) {
        assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
        var out = new String(process.getInputStream().readAllBytes()).trim();
        if (out.equals("ADMITTED")) admittedCount++;
        else assertTrue(out.contains("ARTIFACT_DURABILITY"), out);
      }
      assertEquals(1, admittedCount);
    }
  }
}

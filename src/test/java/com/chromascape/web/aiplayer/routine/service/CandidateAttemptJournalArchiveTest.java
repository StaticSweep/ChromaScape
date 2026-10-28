package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.artifact.ImmutableArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SC-2106 R5.1: the attempt journal moves into managed storage and back, verified both ways. */
class CandidateAttemptJournalArchiveTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path temp;

  private InMemoryCandidateAttemptStore store;
  private ImmutableArtifactStore artifacts;
  private CandidateAttemptJournalArchive archive;
  private CandidateAttemptStore.Attempt held;
  private Spool spool;

  @BeforeEach
  void setUp() throws Exception {
    store = new InMemoryCandidateAttemptStore();
    artifacts = new ImmutableArtifactStore(temp.resolve("artifacts"), 1 << 20, 1 << 24, MAPPER);
    archive = new CandidateAttemptJournalArchive(store, artifacts, MAPPER);
    var reserved =
        store.reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMinutes(1)).attempt();
    store.markRunning(reserved.attemptId(), reserved.leaseId(), reserved.epoch(), "sha256:request");
    held = store.find(reserved.attemptId()).orElseThrow();
    spool = new Spool(temp.resolve("spool"), held.attemptId());
  }

  @Test
  void aFreshHostRebuildsTheJournalWithTheSpoolGone() throws Exception {
    spool.append(event("RECOVERED_FROM"), event("CHECKPOINT"));
    archive.ingest(held, spool.root);
    spool.append(event("STEP"));
    spool.terminal();
    CandidateAttemptStore.JournalHead head = archive.ingest(held, spool.root);
    assertEquals(2L, head.sequence());
    assertEquals(3L, head.eventCount());
    assertTrue(head.terminal());

    Map<String, byte[]> original = spool.files();
    deleteTree(spool.root);
    Path rebuilt = archive.materialize(held, temp.resolve("fresh-host"));

    Map<String, byte[]> restored = new Spool(rebuilt, held.attemptId()).files();
    assertEquals(original.keySet(), restored.keySet());
    for (String name : original.keySet()) {
      if (!name.equals("HEAD")) {
        assertArrayEquals(original.get(name), restored.get(name), name);
      }
    }
    Map<?, ?> restoredHead = MAPPER.readValue(restored.get("HEAD"), Map.class);
    Map<?, ?> originalHead = MAPPER.readValue(original.get("HEAD"), Map.class);
    assertEquals(originalHead, restoredHead, "the rebuilt HEAD says exactly what the runner's did");
  }

  @Test
  void anUploadWhoseReferencesWereNeverCommittedIsNotPartOfTheJournal() throws Exception {
    spool.append(event("CHECKPOINT"));
    archive.ingest(held, spool.root);
    spool.append(event("STEP"));
    // The owner is fenced after the next segment's bytes reach the store but before it commits.
    store.expireAndReconcile(held.attemptId());
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class, () -> archive.ingest(held, spool.root));
    assertEquals(2, artifacts.list(held.ownerId()).size(), "the orphan upload exists");

    assertEquals(1L, store.journalHead(held.attemptId()).sequence());
    Path rebuilt = archive.materialize(held, temp.resolve("fresh-host"));
    assertEquals(1, new Spool(rebuilt, held.attemptId()).segmentCount());
  }

  @Test
  void aCommitBuiltOnAMovedHeadConflicts() throws Exception {
    spool.append(event("CHECKPOINT"));
    archive.ingest(held, spool.root);
    CandidateAttemptStore.JournalHead stale =
        CandidateAttemptStore.JournalHead.empty(held.attemptId());
    var again = store.journalSegments(held.attemptId());
    assertThrows(
        CandidateAttemptStore.JournalConflictException.class,
        () ->
            store.commitJournal(
                held.attemptId(), held.leaseId(), held.epoch(), stale, again, false));
  }

  @Test
  void aSpoolThatDoesNotVerifyIsNeverPublished() throws Exception {
    spool.append(event("CHECKPOINT"));
    spool.append(event("STEP"));
    Path second = spool.segment(2);
    Files.write(second, "{\"tampered\":true}".getBytes(StandardCharsets.UTF_8));
    var corrupt =
        assertThrows(
            CandidateAttemptJournalArchive.JournalRejectedException.class,
            () -> archive.ingest(held, spool.root));
    assertEquals("JOURNAL_SEGMENT_CORRUPT", corrupt.code());
    assertEquals(0L, store.journalHead(held.attemptId()).sequence());

    Files.delete(second);
    var missing =
        assertThrows(
            CandidateAttemptJournalArchive.JournalRejectedException.class,
            () -> archive.ingest(held, spool.root));
    assertEquals("JOURNAL_SEGMENT_MISSING", missing.code());
    assertEquals(0L, store.journalHead(held.attemptId()).sequence());
  }

  @Test
  void anOrphanSiblingOfAnInterruptedAppendIsIgnored() throws Exception {
    spool.append(event("CHECKPOINT"));
    spool.orphan(2, event("LOST"));
    spool.append(event("STEP"));
    assertEquals(2L, archive.ingest(held, spool.root).sequence());
    assertEquals(2, store.journalSegments(held.attemptId()).size());
  }

  @Test
  void aCorruptStoredSegmentRefusesTheRestore() throws Exception {
    spool.append(event("CHECKPOINT"));
    archive.ingest(held, spool.root);
    String checksum = artifacts.list(held.ownerId()).get(0).checksum();
    Path object =
        temp.resolve("artifacts")
            .resolve("objects")
            .resolve(checksum.substring(7, 9))
            .resolve(checksum.substring(7));
    object.toFile().setWritable(true);
    Files.write(object, "x".getBytes(StandardCharsets.UTF_8));

    var refused =
        assertThrows(
            CandidateAttemptJournalArchive.JournalRejectedException.class,
            () -> archive.materialize(held, temp.resolve("fresh-host")));
    assertEquals("JOURNAL_SEGMENT_UNAVAILABLE", refused.code());
  }

  @Test
  void anotherProposalCannotReadTheSegments() throws Exception {
    spool.append(event("CHECKPOINT"));
    archive.ingest(held, spool.root);
    String id = store.journalSegments(held.attemptId()).get(0).artifactId();
    assertThrows(RuntimeException.class, () -> artifacts.read("proposal:p2", id));
  }

  @Test
  void anAttemptWithoutAManagedJournalHasNothingToRestore() throws Exception {
    assertNull(archive.materialize(held, temp.resolve("fresh-host")));
  }

  static Map<String, Object> event(String type) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", type);
    return event;
  }

  static void deleteTree(Path root) throws Exception {
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }

  /** Writes a journal exactly as micro6's {@code SegmentedAttemptJournal} lays it out. */
  static final class Spool {
    final Path root;
    final String attemptId;
    long sequence;
    String chain = CandidateAttemptJournalArchive.GENESIS;
    long events;
    String lastDigest;

    Spool(Path root, String attemptId) {
      this.root = root;
      this.attemptId = attemptId;
    }

    @SafeVarargs
    final void append(Map<String, Object>... batch) throws Exception {
      String digest = write(sequence + 1, chain, batch);
      sequence++;
      events += batch.length;
      lastDigest = digest;
      chain =
          ImmutableArtifactStore.checksum((chain + "|" + digest).getBytes(StandardCharsets.UTF_8));
      head("OPEN");
    }

    @SafeVarargs
    final void orphan(long at, Map<String, Object>... batch) throws Exception {
      write(at, "sha256:" + "f".repeat(64), batch);
    }

    void terminal() throws Exception {
      head("TERMINAL");
    }

    Path segment(long at) throws Exception {
      try (Stream<Path> files = Files.list(root.resolve(attemptId).resolve("segments"))) {
        return files
            .filter(f -> f.getFileName().toString().startsWith(String.format("%010d-", at)))
            .findFirst()
            .orElseThrow();
      }
    }

    int segmentCount() throws Exception {
      try (Stream<Path> files = Files.list(root.resolve(attemptId).resolve("segments"))) {
        return (int) files.count();
      }
    }

    Map<String, byte[]> files() throws Exception {
      Map<String, byte[]> files = new LinkedHashMap<>();
      try (Stream<Path> walk = Files.walk(root.resolve(attemptId))) {
        for (Path p : walk.filter(Files::isRegularFile).toList()) {
          files.put(p.getFileName().toString(), Files.readAllBytes(p));
        }
      }
      return files;
    }

    private String write(long at, String previous, Map<String, Object>[] batch) throws Exception {
      Map<String, Object> segment = new LinkedHashMap<>();
      segment.put("schema", CandidateAttemptJournalArchive.SEGMENT_SCHEMA);
      segment.put("attemptId", attemptId);
      segment.put("sequence", at);
      segment.put("previousChainDigest", previous);
      segment.put("eventCount", batch.length);
      segment.put("events", new ArrayList<>(List.of(batch)));
      byte[] bytes = MAPPER.writeValueAsBytes(segment);
      String digest = ImmutableArtifactStore.checksum(bytes);
      Path dir = Files.createDirectories(root.resolve(attemptId).resolve("segments"));
      Files.write(dir.resolve(String.format("%010d-%s.seg", at, digest.substring(7))), bytes);
      return digest;
    }

    private void head(String state) throws Exception {
      Map<String, Object> head = new LinkedHashMap<>();
      head.put("schema", CandidateAttemptJournalArchive.HEAD_SCHEMA);
      head.put("attemptId", attemptId);
      head.put("lastSequence", sequence);
      head.put("lastSegmentDigest", lastDigest);
      head.put("chainDigest", chain);
      head.put("eventCount", events);
      head.put("state", state);
      Files.write(root.resolve(attemptId).resolve("HEAD"), MAPPER.writeValueAsBytes(head));
    }
  }
}

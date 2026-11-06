package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.artifact.ImmutableArtifactStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Moves an attempt's SC-1916 journal from the runner's local spool into managed storage (SC-2106
 * R5.1). Segment bytes go to the {@link ImmutableArtifactStore} first; only then does the owner
 * commit their references and advance the attempt's managed head, under its fence and by
 * compare-and-set on the previous head. An uploaded segment whose references were never committed
 * is not part of the journal. A recovery reads the journal back through the artifact store and
 * rebuilds it in a fresh directory, so the original spool is never the recovery authority.
 *
 * <p>The on-disk format is micro6's {@code SegmentedAttemptJournal}: {@code
 * <attemptId>/segments/<seq:010d>-<sha256 hex>.seg} and {@code <attemptId>/HEAD}; the chain digest
 * is {@code sha256(previousChain + "|" + segmentDigest)}. Everything is verified here
 * independently; nothing the runner wrote is trusted unchecked.
 */
@Component
public class CandidateAttemptJournalArchive {

  static final String SEGMENT_SCHEMA = "sc1916-attempt-segment/v1";
  static final String HEAD_SCHEMA = "sc1916-attempt-head/v1";
  static final String GENESIS = "sha256:" + "0".repeat(64);
  static final String MEDIA_TYPE = "application/vnd.sc1916.attempt-segment+json";

  private static final Logger logger =
      LoggerFactory.getLogger(CandidateAttemptJournalArchive.class);
  private static final Pattern SEGMENT_FILE = Pattern.compile("(\\d{10})-([0-9a-f]{64})\\.seg");
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** A spool or managed journal that does not verify; never published or restored. */
  public static final class JournalRejectedException extends RuntimeException {
    private final String code;

    JournalRejectedException(String code, String message) {
      super(code + ": " + message);
      this.code = code;
    }

    /** The precise refusal, such as {@code JOURNAL_SEGMENT_CORRUPT}. */
    public String code() {
      return code;
    }
  }

  private final CandidateAttemptStore attempts;
  private final ImmutableArtifactStore artifacts;
  private final ObjectMapper mapper;

  /** An archive over the owner's attempt store and the shared artifact store. */
  public CandidateAttemptJournalArchive(
      CandidateAttemptStore attempts, ImmutableArtifactStore artifacts, ObjectMapper mapper) {
    this.attempts = attempts;
    this.artifacts = artifacts;
    this.mapper = mapper;
  }

  /**
   * Publishes the segments the spool holds beyond the managed head, then advances the head under
   * {@code held}'s fence. Returns the managed head afterwards; a spool with nothing new changes
   * nothing.
   *
   * @throws JournalRejectedException when the spool does not verify against the managed head
   * @throws CandidateAttemptStore.StaleOwnerException when {@code held} is no longer the owner
   */
  public CandidateAttemptStore.JournalHead ingest(
      CandidateAttemptStore.Attempt held, Path spoolRoot) throws IOException {
    String attemptId = held.attemptId();
    CandidateAttemptStore.JournalHead managed = attempts.journalHead(attemptId);
    Path dir = spoolRoot.resolve(attemptId);
    Path headFile = dir.resolve("HEAD");
    if (!Files.isRegularFile(headFile, LinkOption.NOFOLLOW_LINKS)) {
      return managed;
    }
    Map<String, Object> head = mapper.readValue(Files.readAllBytes(headFile), MAP);
    if (!HEAD_SCHEMA.equals(head.get("schema")) || !attemptId.equals(head.get("attemptId"))) {
      throw new JournalRejectedException("JOURNAL_HEAD_CORRUPT", "HEAD names another attempt");
    }
    long last = whole(head, "lastSequence");
    boolean terminal = "TERMINAL".equals(head.get("state"));
    if (last < managed.sequence()) {
      throw new JournalRejectedException(
          "JOURNAL_BEHIND",
          "spool HEAD " + last + " is behind the managed head " + managed.sequence());
    }
    if (last == managed.sequence() && terminal == managed.terminal()) {
      return managed;
    }
    Map<Long, List<Path>> bySequence = segmentFiles(dir.resolve("segments"));
    List<CandidateAttemptStore.JournalSegment> fresh = new ArrayList<>();
    String chain = managed.chainDigest();
    long events = managed.eventCount();
    for (long sequence = managed.sequence() + 1; sequence <= last; sequence++) {
      CandidateAttemptStore.JournalSegment segment = null;
      for (Path candidate : bySequence.getOrDefault(sequence, List.of())) {
        byte[] bytes = Files.readAllBytes(candidate);
        Verified verified = verify(attemptId, sequence, chain, bytes, candidate);
        if (verified == null) {
          continue; // an orphan of an interrupted, superseded append
        }
        String artifactId = "sc2106-seg-" + verified.digest.substring("sha256:".length());
        artifacts.put(
            held.ownerId(),
            artifactId,
            ImmutableArtifactStore.Kind.ATTEMPT_JOURNAL_SEGMENT,
            bytes,
            MEDIA_TYPE,
            Map.of("attemptId", attemptId, "sequence", Long.toString(sequence)),
            Map.of());
        segment =
            new CandidateAttemptStore.JournalSegment(
                sequence, verified.digest, chain, verified.chain, verified.events, artifactId);
        break;
      }
      if (segment == null) {
        throw new JournalRejectedException(
            "JOURNAL_SEGMENT_MISSING",
            "segment " + sequence + " of " + last + " is missing or off the chain");
      }
      fresh.add(segment);
      chain = segment.chainDigest();
      events += segment.eventCount();
    }
    if (!chain.equals(head.get("chainDigest")) || events != whole(head, "eventCount")) {
      throw new JournalRejectedException(
          "JOURNAL_HEAD_MISMATCH", "verified chain " + chain + " does not match the spool HEAD");
    }
    return attempts.commitJournal(
        attemptId, held.leaseId(), held.epoch(), managed, fresh, terminal);
  }

  /**
   * Rebuilds {@code source}'s managed journal under {@code root} from the artifact store, verifying
   * every segment's bytes and the chain again on the way. Returns the root a runner can recover
   * from, or null when the attempt has no managed journal.
   *
   * @throws JournalRejectedException when a stored segment is missing or does not verify
   */
  public Path materialize(CandidateAttemptStore.Attempt source, Path root) throws IOException {
    CandidateAttemptStore.JournalHead managed = attempts.journalHead(source.attemptId());
    if (managed.sequence() == 0) {
      return null;
    }
    Path segments = Files.createDirectories(root.resolve(source.attemptId()).resolve("segments"));
    String chain = GENESIS;
    long events = 0;
    String lastDigest = null;
    long expected = 1;
    for (CandidateAttemptStore.JournalSegment segment :
        attempts.journalSegments(source.attemptId())) {
      if (segment.sequence() != expected++ || !chain.equals(segment.previousChainDigest())) {
        throw new JournalRejectedException(
            "JOURNAL_SEGMENT_MISSING",
            "managed segment " + segment.sequence() + " is out of order");
      }
      byte[] bytes;
      try {
        bytes = artifacts.read(source.ownerId(), segment.artifactId());
      } catch (RuntimeException e) {
        throw new JournalRejectedException(
            "JOURNAL_SEGMENT_UNAVAILABLE",
            "segment " + segment.sequence() + " cannot be read: " + e.getMessage());
      }
      Verified verified = verify(source.attemptId(), segment.sequence(), chain, bytes, null);
      if (verified == null || !verified.digest.equals(segment.segmentDigest())) {
        throw new JournalRejectedException(
            "JOURNAL_SEGMENT_CORRUPT", "segment " + segment.sequence() + " does not verify");
      }
      Files.write(
          segments.resolve(
              String.format(
                  "%010d-%s.seg",
                  segment.sequence(), verified.digest.substring("sha256:".length()))),
          bytes);
      chain = verified.chain;
      events += verified.events;
      lastDigest = verified.digest;
    }
    if (expected - 1 != managed.sequence()
        || !chain.equals(managed.chainDigest())
        || events != managed.eventCount()) {
      throw new JournalRejectedException(
          "JOURNAL_HEAD_MISMATCH", "managed segments do not reach the managed head");
    }
    Map<String, Object> head = new LinkedHashMap<>();
    head.put("schema", HEAD_SCHEMA);
    head.put("attemptId", source.attemptId());
    head.put("lastSequence", managed.sequence());
    head.put("lastSegmentDigest", lastDigest);
    head.put("chainDigest", chain);
    head.put("eventCount", events);
    head.put("state", managed.terminal() ? "TERMINAL" : "OPEN");
    Path temp = Files.createTempFile(root.resolve(source.attemptId()), ".head-", ".part");
    Files.write(temp, mapper.writeValueAsBytes(head));
    Files.move(
        temp,
        root.resolve(source.attemptId()).resolve("HEAD"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    return root;
  }

  private record Verified(String digest, String chain, long events) {}

  /**
   * Checks one segment's bytes at {@code sequence} after {@code chain}. Returns null for a
   * well-formed segment that does not continue the chain (an orphan); throws for corruption.
   */
  private Verified verify(String attemptId, long sequence, String chain, byte[] bytes, Path file)
      throws IOException {
    String digest = ImmutableArtifactStore.checksum(bytes);
    if (file != null) {
      Matcher name = SEGMENT_FILE.matcher(file.getFileName().toString());
      if (!name.matches() || !digest.equals("sha256:" + name.group(2))) {
        throw new JournalRejectedException(
            "JOURNAL_SEGMENT_CORRUPT", file.getFileName() + " does not hash to its name");
      }
    }
    Map<String, Object> segment;
    try {
      segment = mapper.readValue(bytes, MAP);
    } catch (IOException e) {
      throw new JournalRejectedException(
          "JOURNAL_SEGMENT_CORRUPT", "segment " + sequence + " is not JSON");
    }
    if (!SEGMENT_SCHEMA.equals(segment.get("schema"))
        || !attemptId.equals(segment.get("attemptId"))
        || !(segment.get("sequence") instanceof Number n)
        || n.longValue() != sequence) {
      throw new JournalRejectedException(
          "JOURNAL_SEGMENT_CORRUPT", "segment identity does not match position " + sequence);
    }
    if (!chain.equals(segment.get("previousChainDigest"))) {
      return null;
    }
    if (!(segment.get("events") instanceof List<?> list)
        || list.isEmpty()
        || list.size() != whole(segment, "eventCount")) {
      throw new JournalRejectedException(
          "JOURNAL_SEGMENT_CORRUPT", "segment " + sequence + " event count does not match");
    }
    String next =
        ImmutableArtifactStore.checksum((chain + "|" + digest).getBytes(StandardCharsets.UTF_8));
    return new Verified(digest, next, list.size());
  }

  private static Map<Long, List<Path>> segmentFiles(Path segments) throws IOException {
    Map<Long, List<Path>> bySequence = new LinkedHashMap<>();
    if (!Files.isDirectory(segments, LinkOption.NOFOLLOW_LINKS)) {
      return bySequence;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(segments)) {
      for (Path file : stream) {
        Matcher matcher = SEGMENT_FILE.matcher(file.getFileName().toString());
        if (matcher.matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          bySequence
              .computeIfAbsent(Long.parseLong(matcher.group(1)), k -> new ArrayList<>())
              .add(file);
        }
      }
    }
    return bySequence;
  }

  private static long whole(Map<String, Object> map, String field) {
    if (map.get(field) instanceof Integer || map.get(field) instanceof Long) {
      return ((Number) map.get(field)).longValue();
    }
    throw new JournalRejectedException("JOURNAL_HEAD_CORRUPT", field + " must be an integer");
  }

  /**
   * Removes local copies that managed storage makes redundant, older than {@code grace} (R5.4):
   * rebuilt recovery sources (always rebuildable from the artifact store), and the spool of a
   * settled attempt whose sealed managed head covers it exactly. An open attempt, a spool the
   * managed journal does not fully cover, and anything younger than the grace period are pinned.
   * Each removal is logged as an audit record. Returns what was removed.
   */
  public List<Path> sweep(Path runRoot, Duration grace, Instant now) throws IOException {
    List<Path> removed = new ArrayList<>();
    if (!Files.isDirectory(runRoot, LinkOption.NOFOLLOW_LINKS)) {
      return removed;
    }
    Instant cutoff = now.minus(grace);
    Path sources = runRoot.resolve("recovery-sources");
    for (Path source : children(sources)) {
      if (older(source, cutoff)) {
        delete(source, "rebuilt recovery source", removed);
      }
    }
    for (Path run : children(runRoot)) {
      String attemptId = run.getFileName().toString();
      Path spool = run.resolve("journal");
      if (attemptId.equals("recovery-sources")
          || !Files.isDirectory(spool, LinkOption.NOFOLLOW_LINKS)
          || !older(spool, cutoff)) {
        continue;
      }
      CandidateAttemptStore.Attempt attempt = attempts.find(attemptId).orElse(null);
      if (attempt == null || attempt.state().open()) {
        continue;
      }
      CandidateAttemptStore.JournalHead managed = attempts.journalHead(attemptId);
      Long spoolSequence = spoolSequence(spool, attemptId);
      if (managed.terminal() && spoolSequence != null && spoolSequence == managed.sequence()) {
        delete(spool, "spool covered by sealed managed journal " + managed.chainDigest(), removed);
      }
    }
    return removed;
  }

  private Long spoolSequence(Path spool, String attemptId) {
    try {
      Map<String, Object> head =
          mapper.readValue(Files.readAllBytes(spool.resolve(attemptId).resolve("HEAD")), MAP);
      return HEAD_SCHEMA.equals(head.get("schema")) ? whole(head, "lastSequence") : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static List<Path> children(Path dir) throws IOException {
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    try (Stream<Path> list = Files.list(dir)) {
      return list.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList();
    }
  }

  private static boolean older(Path path, Instant cutoff) throws IOException {
    FileTime modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
    return modified.toInstant().isBefore(cutoff);
  }

  private static void delete(Path root, String why, List<Path> removed) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
    logger.info("SC-2106 retention removed {} ({})", root, why);
    removed.add(root);
  }
}

package com.chromascape.web.artifact;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Local immutable manifests and content addresses. All writers/cleanup use one OS file lock. */
public final class ImmutableArtifactStore {
  public enum Kind {
    RECORDING,
    CURATION_SNAPSHOT,
    GRAPH,
    DATASET,
    DATASET_MANIFEST,
    MODEL,
    REPLAY_REPORT,
    SOURCE_SNAPSHOT,
    PATCH_REPORT,
    BUILD_REPORT,
    PLUGIN_CANDIDATE,
    SOURCE_LICENSE_MANIFEST,
    WIKI_CORPUS,
    WIKI_CHUNK,
    REVISION_MAP,
    RETRIEVAL_INDEX,
    CANDIDATE_KG,
    PREREQUISITE_GRAPH,
    DATASET_SPLIT,
    KNOWLEDGE_EVALUATION,
    KNOWLEDGE_SNAPSHOT
  }

  public record Manifest(
      String id,
      String owner,
      Kind kind,
      String checksum,
      long sizeBytes,
      String mediaType,
      Map<String, String> lineage,
      Map<String, String> dependencies) {
    public Manifest {
      lineage = Map.copyOf(lineage);
      dependencies = Map.copyOf(dependencies);
    }
  }

  public enum State {
    FINALIZED,
    INCOMPLETE,
    CORRUPT,
    QUARANTINED
  }

  public long maxArtifactBytes() {
    return maxBytes;
  }

  public State state(String owner, String id) {
    try {
      get(owner, id);
      return State.FINALIZED;
    } catch (ResponseStatusException e) {
      if ("ARTIFACT_NOT_FOUND".equals(e.getReason())) return State.INCOMPLETE;
      if ("ARTIFACT_QUARANTINED".equals(e.getReason())) return State.QUARANTINED;
      if (e.getStatusCode().equals(HttpStatus.CONFLICT)) return State.CORRUPT;
      throw e;
    }
  }

  public void quarantine(String owner, String id) {
    identity(owner, id);
    locked(
        () -> {
          manifest(owner, id);
          Path marker = refs(owner).resolve(id + ".quarantined");
          if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (usedBytes() >= quotaBytes)
              throw error(HttpStatus.INSUFFICIENT_STORAGE, "ARTIFACT_QUOTA_EXCEEDED");
            publish(marker, new byte[] {1});
          }
          return null;
        });
  }

  public record SnapshotReference(String id, String checksum) {}

  /** Activation references verified immutable evidence and never changes gameplay configuration. */
  public SnapshotReference activateKnowledgeSnapshot(String owner, String id) {
    identity(owner, id);
    return locked(
        () -> {
          Manifest manifest = verifyTree(owner, id, new HashSet<>(), 0);
          if (manifest.kind() != Kind.KNOWLEDGE_SNAPSHOT || manifest.dependencies().isEmpty())
            throw error(HttpStatus.CONFLICT, "KNOWLEDGE_SNAPSHOT_INVALID");
          SnapshotReference reference = new SnapshotReference(id, manifest.checksum());
          byte[] bytes = json.writeValueAsBytes(reference);
          Path pointer = refs(owner).resolve("active-knowledge.pointer");
          validateAncestors(pointer);
          long priorSize =
              Files.exists(pointer, LinkOption.NOFOLLOW_LINKS) ? Files.size(pointer) : 0;
          if (bytes.length - priorSize > quotaBytes - usedBytes())
            throw error(HttpStatus.INSUFFICIENT_STORAGE, "ARTIFACT_QUOTA_EXCEEDED");
          Path temp = root.resolve("pending").resolve(UUID.randomUUID() + ".tmp");
          try {
            try (var channel =
                FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
              ByteBuffer buffer = ByteBuffer.wrap(bytes);
              while (buffer.hasRemaining()) channel.write(buffer);
              channel.force(true);
            }
            if (!checksum(bounded(temp, MAX_MANIFEST_BYTES)).equals(checksum(bytes)))
              throw error(HttpStatus.CONFLICT, "ARTIFACT_CORRUPT");
            // Commit point: all fallible validation/writes precede the atomic pointer switch.
            Files.move(
                temp,
                pointer,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return reference;
          } finally {
            Files.deleteIfExists(temp);
          }
        });
  }

  public SnapshotReference activeKnowledgeSnapshot(String owner) {
    identity(owner, "active");
    return locked(
        () -> {
          Path pointer = refs(owner).resolve("active-knowledge.pointer");
          if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS))
            throw error(HttpStatus.NOT_FOUND, "KNOWLEDGE_SNAPSHOT_NOT_ACTIVE");
          SnapshotReference reference;
          try {
            reference =
                json.readValue(bounded(pointer, MAX_MANIFEST_BYTES), SnapshotReference.class);
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw error(HttpStatus.CONFLICT, "KNOWLEDGE_SNAPSHOT_POINTER_CORRUPT");
          }
          if (reference == null)
            throw error(HttpStatus.CONFLICT, "KNOWLEDGE_SNAPSHOT_POINTER_CORRUPT");
          Manifest manifest = verifyTree(owner, reference.id(), new HashSet<>(), 0);
          if (manifest.kind() != Kind.KNOWLEDGE_SNAPSHOT
              || !manifest.checksum().equals(reference.checksum()))
            throw error(HttpStatus.CONFLICT, "KNOWLEDGE_SNAPSHOT_POINTER_CORRUPT");
          return reference;
        });
  }

  private record Envelope(String checksum, Manifest manifest) {}

  private static final int MAX_MANIFEST_BYTES = 65536;
  private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
  private final Path root;
  private final long maxBytes;
  private final long quotaBytes;
  private final ObjectMapper json;

  public ImmutableArtifactStore(Path root, long maxBytes, long quotaBytes, ObjectMapper json) {
    if (maxBytes < 1 || quotaBytes < maxBytes)
      throw new IllegalArgumentException("Invalid artifact limits");
    this.root = root.toAbsolutePath().normalize();
    this.maxBytes = maxBytes;
    this.quotaBytes = quotaBytes;
    this.json =
        json.copy()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  /** A missing reference never resolves, even if a crash left published but unregistered bytes. */
  public Manifest put(
      String owner,
      String id,
      Kind kind,
      byte[] content,
      String mediaType,
      Map<String, String> lineage,
      Map<String, String> dependencies) {
    identity(owner, id);
    if (kind == null || content == null || mediaType == null || mediaType.isBlank())
      throw error(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_MANIFEST");
    if (content.length > maxBytes) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_TOO_LARGE");
    if (lineage == null || lineage.isEmpty() || dependencies == null)
      throw error(HttpStatus.BAD_REQUEST, "ARTIFACT_LINEAGE_REQUIRED");
    if (lineage.entrySet().stream()
            .anyMatch(e -> e.getKey() == null || e.getKey().isBlank() || e.getValue() == null)
        || dependencies.entrySet().stream()
            .anyMatch(e -> e.getKey() == null || e.getKey().isBlank() || e.getValue() == null))
      throw error(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_MANIFEST");
    Manifest manifest =
        new Manifest(
            id, owner, kind, checksum(content), content.length, mediaType, lineage, dependencies);
    return locked(
        () -> {
          byte[] payload = json.writeValueAsBytes(manifest);
          byte[] envelope = json.writeValueAsBytes(new Envelope(checksum(payload), manifest));
          if (envelope.length > MAX_MANIFEST_BYTES)
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_MANIFEST_TOO_LARGE");
          for (String dependency : dependencies.values())
            verifyTree(owner, dependency, new HashSet<>(), 0);
          Path ref = reference(owner, id);
          if (Files.exists(ref, LinkOption.NOFOLLOW_LINKS)) {
            Manifest existing = manifest(owner, id);
            if (!manifest.equals(existing))
              throw error(HttpStatus.CONFLICT, "ARTIFACT_ID_CONFLICT");
            verifyTree(owner, id, new HashSet<>(), 0);
            return existing;
          }
          Path blob = object(manifest.checksum());
          boolean reuse = Files.exists(blob, LinkOption.NOFOLLOW_LINKS);
          if (reuse) verify(blob, manifest.checksum(), manifest.sizeBytes());
          long needed = Math.addExact(reuse ? 0 : content.length, envelope.length);
          if (needed > quotaBytes - usedBytes())
            throw error(HttpStatus.INSUFFICIENT_STORAGE, "ARTIFACT_QUOTA_EXCEEDED");
          if (!reuse) publish(blob, content);
          verify(blob, manifest.checksum(), manifest.sizeBytes());
          publish(ref, envelope); // only this atomic publication makes the artifact discoverable
          return manifest;
        });
  }

  public Manifest get(String owner, String id) {
    identity(owner, id);
    return locked(() -> verifyTree(owner, id, new HashSet<>(), 0));
  }

  public byte[] read(String owner, String id) {
    identity(owner, id);
    return locked(
        () -> {
          Manifest m = verifyTree(owner, id, new HashSet<>(), 0);
          byte[] bytes = bounded(object(m.checksum()), maxBytes);
          if (bytes.length != m.sizeBytes() || !checksum(bytes).equals(m.checksum()))
            throw error(HttpStatus.CONFLICT, "ARTIFACT_CORRUPT");
          return bytes;
        });
  }

  public List<Manifest> list(String owner) {
    identity(owner, "list");
    return locked(
        () -> {
          Path dir = refs(owner);
          validateAncestors(dir);
          if (!Files.exists(dir)) return List.of();
          List<Manifest> found = new ArrayList<>();
          try (var entries = Files.list(dir)) {
            for (Path p : entries.sorted().toList()) {
              String name = p.getFileName().toString();
              if (name.endsWith(".json"))
                found.add(
                    verifyTree(owner, name.substring(0, name.length() - 5), new HashSet<>(), 0));
            }
          }
          return List.copyOf(found);
        });
  }

  /** Explicit development reset removes only this owner's references, retaining shared content. */
  public void resetDevelopment(String owner, boolean confirmed) {
    identity(owner, "reset");
    if (!confirmed) throw error(HttpStatus.BAD_REQUEST, "ARTIFACT_RESET_CONFIRMATION_REQUIRED");
    locked(
        () -> {
          Path dir = refs(owner);
          validateAncestors(dir);
          if (Files.exists(dir))
            try (var entries = Files.list(dir)) {
              for (Path p : entries.toList()) {
                String name = p.getFileName().toString();
                if ((!name.endsWith(".json")
                        && !name.endsWith(".quarantined")
                        && !name.equals("active-knowledge.pointer"))
                    || Files.isSymbolicLink(p))
                  throw error(HttpStatus.CONFLICT, "ARTIFACT_REGISTRY_INVALID");
                Files.delete(p);
              }
            }
          return null;
        });
  }

  /** The OS lock excludes active writers; everything in pending is then an abandoned temp file. */
  public void cleanTemporary() {
    locked(() -> null);
  }

  public Path contentPath(Manifest manifest) {
    return object(manifest.checksum());
  }

  private Manifest verifyTree(String owner, String id, Set<String> visiting, int depth)
      throws IOException {
    identity(owner, id);
    if (depth > 32 || !visiting.add(id)) throw error(HttpStatus.CONFLICT, "ARTIFACT_LINEAGE_CYCLE");
    if (Files.exists(refs(owner).resolve(id + ".quarantined"), LinkOption.NOFOLLOW_LINKS))
      throw error(HttpStatus.CONFLICT, "ARTIFACT_QUARANTINED");
    Manifest m = manifest(owner, id);
    verify(object(m.checksum()), m.checksum(), m.sizeBytes());
    for (String dependency : m.dependencies().values())
      verifyTree(owner, dependency, visiting, depth + 1);
    visiting.remove(id);
    return m;
  }

  private Manifest manifest(String owner, String id) throws IOException {
    Path ref = reference(owner, id);
    if (!Files.exists(ref, LinkOption.NOFOLLOW_LINKS))
      throw error(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND");
    try {
      Envelope envelope = json.readValue(bounded(ref, MAX_MANIFEST_BYTES), Envelope.class);
      if (envelope == null) throw error(HttpStatus.CONFLICT, "ARTIFACT_MANIFEST_CORRUPT");
      Manifest m = envelope.manifest();
      if (m == null
          || !owner.equals(m.owner())
          || !id.equals(m.id())
          || m.kind() == null
          || !checksum(json.writeValueAsBytes(m)).equals(envelope.checksum()))
        throw error(HttpStatus.CONFLICT, "ARTIFACT_MANIFEST_CORRUPT");
      object(m.checksum());
      return m;
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
      throw error(HttpStatus.CONFLICT, "ARTIFACT_MANIFEST_CORRUPT");
    }
  }

  private void verify(Path path, String checksum, long size) throws IOException {
    if (size < 0
        || size > maxBytes
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.size(path) != size) throw error(HttpStatus.CONFLICT, "ARTIFACT_CORRUPT");
    byte[] bytes = bounded(path, maxBytes);
    if (bytes.length != size || !checksum(bytes).equals(checksum))
      throw error(HttpStatus.CONFLICT, "ARTIFACT_CORRUPT");
  }

  private byte[] bounded(Path path, long limit) throws IOException {
    validateAncestors(path);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
      throw error(HttpStatus.CONFLICT, "ARTIFACT_CORRUPT");
    try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        var out = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      long total = 0;
      int count;
      while ((count = in.read(buffer)) != -1) {
        total += count;
        if (total > limit) throw error(HttpStatus.CONFLICT, "ARTIFACT_CORRUPT");
        out.write(buffer, 0, count);
      }
      return out.toByteArray();
    }
  }

  private void publish(Path path, byte[] bytes) throws IOException {
    directory(path.getParent());
    Path temp = root.resolve("pending").resolve(UUID.randomUUID() + ".tmp");
    try {
      try (var channel =
          FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      if (!checksum(bounded(temp, Math.max(maxBytes, MAX_MANIFEST_BYTES))).equals(checksum(bytes)))
        throw error(HttpStatus.CONFLICT, "ARTIFACT_CORRUPT");
      // Hard-link creation is atomic and never replaces a winner. No non-atomic fallback.
      Files.createLink(path, temp);
      try (var channel = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
        channel.force(true);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private long usedBytes() throws IOException {
    long total = 0;
    for (String dir : List.of("objects", "refs")) {
      Path path = root.resolve(dir);
      if (!Files.exists(path)) continue;
      try (var files = Files.walk(path)) {
        for (Path p : files.toList()) {
          if (Files.isSymbolicLink(p))
            throw error(HttpStatus.CONFLICT, "ARTIFACT_REGISTRY_INVALID");
          if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
            total = Math.addExact(total, Files.size(p));
        }
      }
    }
    return total;
  }

  private Path object(String checksum) {
    if (checksum == null || !checksum.matches("sha256:[a-f0-9]{64}"))
      throw error(HttpStatus.CONFLICT, "ARTIFACT_CHECKSUM_INVALID");
    String hex = checksum.substring(7);
    return root.resolve("objects").resolve(hex.substring(0, 2)).resolve(hex);
  }

  private Path refs(String owner) {
    return root.resolve("refs")
        .resolve(checksum(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(7));
  }

  private Path reference(String owner, String id) {
    return refs(owner).resolve(id + ".json");
  }

  private static void identity(String owner, String id) {
    if (owner == null
        || owner.isBlank()
        || owner.length() > 256
        || id == null
        || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,159}"))
      throw error(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_IDENTITY");
  }

  public static String checksum(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void validateAncestors(Path path) {
    for (Path p = path; p != null && p.startsWith(root); p = p.getParent()) {
      if (Files.isSymbolicLink(p)) throw error(HttpStatus.CONFLICT, "ARTIFACT_REGISTRY_INVALID");
    }
  }

  private void directory(Path dir) throws IOException {
    validateAncestors(dir);
    Files.createDirectories(dir);
  }

  @FunctionalInterface
  private interface Io<T> {
    T run() throws IOException;
  }

  private <T> T locked(Io<T> operation) {
    ReentrantLock mutex = LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
    mutex.lock();
    try {
      directory(root);
      try (var channel =
              FileChannel.open(
                  root.resolve("registry.lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS);
          var lock = channel.lock()) {
        directory(root.resolve("pending"));
        try (var abandoned = Files.list(root.resolve("pending"))) {
          for (Path p : abandoned.toList()) {
            if (Files.isSymbolicLink(p) || !p.getFileName().toString().endsWith(".tmp"))
              throw error(HttpStatus.CONFLICT, "ARTIFACT_REGISTRY_INVALID");
            Files.delete(p);
          }
        }
        return operation.run();
      }
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "ARTIFACT_STORAGE_UNAVAILABLE", e);
    } finally {
      mutex.unlock();
    }
  }

  private static ResponseStatusException error(HttpStatus status, String code) {
    return new ResponseStatusException(status, code);
  }
}

package com.chromascape.web.accounttask.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local filesystem artifact store for verified stabilization bundles (SC-572). Artifacts are
 * addressed by opaque UUID ids, written atomically, and never exposed as host paths. Hash and
 * size are computed from the final bytes; every read rechecks existence and refuses symlinks and
 * path traversal. Sidecar metadata (checksum, size, media type, evidence type, run/attempt) is
 * stored next to the artifact for retrieval without database scans.
 */
@Component
public class StabilizationArtifactStore {

  public static final long MAX_ARTIFACT_BYTES = 64L * 1024L * 1024L;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path baseDir;
  private final Path stagingDir;

  public Path baseDir() {
    return baseDir;
  }

  public Path stagingDir() {
    return stagingDir;
  }

  public StabilizationArtifactStore(
      @Value("${stabilization.artifact-store.path:./data/stabilization-artifacts}") String path) {
    this.baseDir = Path.of(path).toAbsolutePath().normalize();
    this.stagingDir = baseDir.resolve("staging");
  }

  @PostConstruct
  void ensureBaseDir() {
    try {
      ensureRealDirectory(baseDir);
      ensureRealDirectory(stagingDir);
    } catch (IOException e) {
      throw new IllegalStateException("cannot create stabilization artifact store at " + baseDir, e);
    }
  }

  /** Metadata of one stored artifact; the bytes live under an opaque UUID file. */
  public record StoredArtifact(
      UUID id,
      String sha256,
      long sizeBytes,
      String mediaType,
      String evidenceType,
      String runId,
      String attemptId,
      String createdAt) {}

  /**
   * Stages artifact bytes into the staging namespace without promoting them. Validation and
   * checksumming happen in staging, before any database work; {@link #promote} is invoked only
   * inside the persistence transaction, and any failure deletes every staged/promoted artifact.
   */
  public StoredArtifact stage(byte[] bytes, String mediaType, String evidenceType, String runId,
      String attemptId) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("cannot stage an empty stabilization artifact");
    }
    if (bytes.length > MAX_ARTIFACT_BYTES) {
      throw new IllegalArgumentException("stabilization artifact exceeds size bound");
    }
    Path temp = null;
    try {
      ensureRealDirectory(stagingDir);
      temp = Files.createTempFile(stagingDir, "input-", ".bin");
      Files.write(temp, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
          LinkOption.NOFOLLOW_LINKS);
      return stage(temp, mediaType, evidenceType, runId, attemptId, true);
    } catch (IOException e) {
      throw new IllegalStateException("failed to stage stabilization artifact", e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // best-effort temp cleanup; stage(..., true) also removes the source on normal paths
        }
      }
    }
  }

  /**
   * Streams a validated staged input into the ingestion-specific staging namespace while
   * calculating the raw SHA-256 and byte count. The source is never loaded into heap memory; the
   * bytes are copied stream-to-stream and the checksum is computed over the final staged bytes.
   */
  public StoredArtifact stage(Path source, String mediaType, String evidenceType, String runId,
      String attemptId) {
    return stage(source, mediaType, evidenceType, runId, attemptId, false);
  }

  private StoredArtifact stage(Path source, String mediaType, String evidenceType, String runId,
      String attemptId, boolean deleteSourceAfter) {
    if (source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(source)) {
      throw new IllegalArgumentException("cannot stage a missing or non-regular stabilization artifact");
    }
    long sourceSize;
    try {
      sourceSize = Files.size(source);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read staged artifact size", e);
    }
    if (sourceSize <= 0) {
      throw new IllegalArgumentException("cannot stage an empty stabilization artifact");
    }
    if (sourceSize > MAX_ARTIFACT_BYTES) {
      throw new IllegalArgumentException("stabilization artifact exceeds size bound");
    }
    UUID id = UUID.randomUUID();
    Path tmp = null;
    long stagedBytes = 0;
    try {
      ensureStagingDirectory();
      Path target = stagingPath(id);
      tmp = target.resolveSibling(target.getFileName() + ".tmp");
      MessageDigest digest;
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 unavailable", e);
      }
      try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
          java.io.OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
          if (read > MAX_ARTIFACT_BYTES - stagedBytes) {
            throw new IllegalArgumentException(
                "stabilization artifact exceeds size bound while staging");
          }
          out.write(buffer, 0, read);
          digest.update(buffer, 0, read);
          stagedBytes += read;
        }
      }
      if (Files.isSymbolicLink(source)
          || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(
            "stabilization artifact source changed while staging: " + source);
      }
      long finalSourceSize = Files.size(source);
      if (stagedBytes != sourceSize || finalSourceSize != sourceSize) {
        throw new IllegalArgumentException(
            "stabilization artifact changed while staging: " + source);
      }
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(tmp, target);
      }
      String sha256 = "sha256:" + hex(digest.digest());
      String createdAt = Instant.now().toString();
      return new StoredArtifact(id, sha256, stagedBytes, mediaType, evidenceType, runId,
          attemptId, createdAt);
    } catch (IOException e) {
      throw new IllegalStateException("failed to stage stabilization artifact " + id, e);
    } finally {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
          // best-effort journal cleanup
        }
      }
      if (deleteSourceAfter && source != null) {
        try {
          Files.deleteIfExists(source);
        } catch (IOException ignored) {
          // best-effort temp cleanup
        }
      }
    }
  }

  /**
   * Promotes a staged artifact into the final namespace: the bytes are atomically moved, re-read,
   * size-checked and raw-byte checksummed, then the sidecar metadata is written. Rejects missing
   * staging files and checksum/size drift.
   */
  public StoredArtifact promote(StoredArtifact staged) {
    if (staged == null || staged.id() == null) {
      throw new IllegalArgumentException("cannot promote a null staged artifact");
    }
    UUID id = staged.id();
    ensureStagingDirectory();
    Path stagedPath = stagingPath(id);
    if (!Files.isRegularFile(stagedPath, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(stagedPath)) {
      throw new IllegalStateException("staged artifact is missing: " + id);
    }
    Path target = resolveArtifactPath(id);
    Path metadataPath = target.resolveSibling(id + ".meta");
    boolean moved = false;
    try {
      ensureRealDirectory(baseDir);
      if (Files.isSymbolicLink(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("artifact target already exists: " + id);
      }
      try {
        Files.move(stagedPath, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(stagedPath, target);
      }
      moved = true;
      // Re-hash and re-size the promoted bytes stream-to-stream with bounds; the artifact is never
      // loaded into heap memory, so a full-size certification artifact cannot exhaust the JVM.
      long finalSize;
      String sha256;
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream in = Files.newInputStream(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
          byte[] buffer = new byte[64 * 1024];
          int read;
          while ((read = in.read(buffer)) != -1) {
            size += read;
            digest.update(buffer, 0, read);
            if (size > MAX_ARTIFACT_BYTES) {
              Files.deleteIfExists(target);
              throw new IllegalStateException("promoted stabilization artifact exceeds size bound for " + id);
            }
          }
        }
        finalSize = size;
        sha256 = "sha256:" + hex(digest.digest());
      } catch (java.security.NoSuchAlgorithmException e) {
        Files.deleteIfExists(target);
        throw new IllegalStateException("SHA-256 unavailable", e);
      }
      if (!sha256.equals(staged.sha256()) || finalSize != staged.sizeBytes()) {
        Files.deleteIfExists(target);
        throw new IllegalStateException("staged artifact checksum/size drift for " + id);
      }
      writeMetadata(id, sha256, finalSize, staged.mediaType(), staged.evidenceType(),
          staged.runId(), staged.attemptId(), staged.createdAt());
      return new StoredArtifact(id, sha256, finalSize, staged.mediaType(),
          staged.evidenceType(), staged.runId(), staged.attemptId(), staged.createdAt());
    } catch (IOException | RuntimeException e) {
      // Once the staged file is moved, this promotion owns the new target. If hashing or metadata
      // persistence fails, remove only what this call created; never replace/delete a pre-existing
      // artifact target or sidecar.
      if (moved) {
        try {
          Files.deleteIfExists(target);
        } catch (IOException cleanup) {
          e.addSuppressed(cleanup);
        }
      }
      if (e instanceof IllegalStateException state) {
        throw state;
      }
      throw new IllegalStateException("failed to promote stabilization artifact " + id, e);
    }
  }

  /** Removes a staged artifact (used for rollback when staging or persistence fails). */
  public void deleteStaged(UUID id) {
    if (id == null) {
      return;
    }
    try {
      if (!isRealDirectory(stagingDir)) {
        return;
      }
      Path staged = stagingPath(id);
      if (Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(staged);
      }
      Files.deleteIfExists(staged.resolveSibling(id + ".tmp"));
    } catch (IOException ignored) {
      // best-effort rollback
    }
  }

  /** Backwards-compatible direct store: stage then immediately promote. */
  public StoredArtifact store(byte[] bytes, String mediaType, String evidenceType, String runId,
      String attemptId) {
    return promote(stage(bytes, mediaType, evidenceType, runId, attemptId));
  }

  /** Resolves an opaque artifact id to a regular, non-symlink file inside the store. */
  public Optional<Path> resolve(UUID id) {
    if (id == null) {
      return Optional.empty();
    }
    try {
      if (!isRealDirectory(baseDir)) {
        return Optional.empty();
      }
      Path resolved = resolveArtifactPath(id);
      if (!resolved.startsWith(baseDir)) {
        return Optional.empty();
      }
      if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(resolved)) {
        return Optional.empty();
      }
      return Optional.of(resolved);
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  /** Bounded read of an artifact; rechecks existence and refuses oversized content. */
  public Optional<byte[]> read(UUID id) {
    Optional<Path> resolved = resolve(id);
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    try {
      long size = Files.size(resolved.get());
      if (size > MAX_ARTIFACT_BYTES) {
        return Optional.empty();
      }
      try (InputStream input = Files.newInputStream(resolved.get(), LinkOption.NOFOLLOW_LINKS)) {
        ByteArrayOutputStream output =
            new ByteArrayOutputStream((int) Math.min(size, MAX_ARTIFACT_BYTES));
        byte[] buffer = new byte[64 * 1024];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
          if (read > MAX_ARTIFACT_BYTES - count) {
            return Optional.empty();
          }
          output.write(buffer, 0, read);
          count += read;
        }
        return Optional.of(output.toByteArray());
      }
    } catch (IOException ignored) {
      return Optional.empty();
    }
  }

  /** Reads the sidecar metadata without scanning the database. */
  public Optional<StoredArtifact> metadata(UUID id) {
    if (id == null) {
      return Optional.empty();
    }
    try {
      if (!isRealDirectory(baseDir)) {
        return Optional.empty();
      }
      Path meta = resolveArtifactPath(id).resolveSibling(id + ".meta");
      if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(meta)) {
        return Optional.empty();
      }
      Map<?, ?> node;
      try (InputStream input = Files.newInputStream(meta, LinkOption.NOFOLLOW_LINKS)) {
        node = MAPPER.readValue(input, Map.class);
      }
      return Optional.of(new StoredArtifact(
          id,
          String.valueOf(node.get("sha256")),
          ((Number) node.get("size_bytes")).longValue(),
          String.valueOf(node.get("media_type")),
          String.valueOf(node.get("evidence_type")),
          String.valueOf(node.get("run_id")),
          String.valueOf(node.get("attempt_id")),
          String.valueOf(node.get("created_at"))));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  /** Removes the artifact bytes and its sidecar; used for rollback on failed ingestion. */
  public void delete(UUID id) {
    if (id == null || !isRealDirectory(baseDir)) {
      return;
    }
    resolve(id).ifPresent(path -> {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
        // best-effort rollback
      }
    });
    try {
      Files.deleteIfExists(resolveArtifactPath(id).resolveSibling(id + ".meta"));
    } catch (IOException ignored) {
      // best-effort rollback
    }
  }

  private void writeMetadata(UUID id, String sha256, long sizeBytes, String mediaType,
      String evidenceType, String runId, String attemptId, String createdAt) throws IOException {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("artifact_id", id.toString());
    meta.put("sha256", sha256);
    meta.put("size_bytes", sizeBytes);
    meta.put("media_type", mediaType);
    meta.put("evidence_type", evidenceType);
    meta.put("run_id", runId);
    meta.put("attempt_id", attemptId);
    meta.put("created_at", createdAt);
    Path metaPath = resolveArtifactPath(id).resolveSibling(id + ".meta");
    // Write the sidecar through a unique temporary file and publish it with a no-replace move.
    // A direct CREATE_NEW write can leave a partial sidecar on disk; deleting that path from the
    // promotion catch block is also race-prone because another writer could create it between the
    // existence check and the failure. The temporary name is owned exclusively by this call.
    Path temp = metaPath.resolveSibling(
        metaPath.getFileName() + "." + UUID.randomUUID() + ".tmp");
    boolean published = false;
    try {
      Files.writeString(temp, MAPPER.writeValueAsString(meta), StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      try {
        Files.move(temp, metaPath, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(temp, metaPath);
      }
      published = true;
    } finally {
      if (!published) {
        Files.deleteIfExists(temp);
      }
    }
  }

  private void ensureStagingDirectory() {
    try {
      ensureRealDirectory(stagingDir);
    } catch (IOException e) {
      throw new IllegalStateException("stabilization staging directory is not safe: " + stagingDir, e);
    }
  }

  private static boolean isRealDirectory(Path directory) {
    return directory != null && !Files.isSymbolicLink(directory)
        && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS);
  }

  /** Creates or verifies a directory without accepting a symlink at the directory boundary. */
  private static void ensureRealDirectory(Path directory) throws IOException {
    if (directory == null) {
      throw new IOException("directory is required");
    }
    if (Files.isSymbolicLink(directory)) {
      throw new IOException("directory must not be a symbolic link: " + directory);
    }
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(directory);
    }
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("path is not a real directory: " + directory);
    }
  }

  private Path resolveArtifactPath(UUID id) {
    return baseDir.resolve(id.toString());
  }

  private Path stagingPath(UUID id) {
    return stagingDir.resolve(id.toString());
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte b : bytes) {
      result.append(String.format("%02x", b));
    }
    return result.toString();
  }
}

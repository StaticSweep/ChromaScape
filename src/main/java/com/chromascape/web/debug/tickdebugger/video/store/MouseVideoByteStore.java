package com.chromascape.web.debug.tickdebugger.video.store;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Content-addressed byte store for external media. Bytes are streamed through a bounded digest with
 * a hard byte ceiling so a single upload can never allocate an unbounded buffer or write past its
 * cap. Staging and publication are separate: publication uses an atomic create-without-replace into
 * a generated final path derived only from the validated SHA-256 address, so no caller-supplied
 * path segment ever reaches the filesystem.
 */
@Component
public class MouseVideoByteStore {

  public static final String ALGORITHM = "sha256";
  private static final String HASH_PREFIX = "sha256:";
  private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
  private static final int BUFFER_BYTES = 64 * 1024;

  private final Path root;
  private final Path bytes;
  private final Path staging;
  private final Path lockRoot;
  private final ThreadLocal<PublicationBatch> publicationBatch = new ThreadLocal<>();

  public MouseVideoByteStore(MouseVideoProperties properties) throws IOException {
    this.root = properties.rootPath();
    this.bytes = root.resolve("bytes");
    this.staging = root.resolve("staging");
    Files.createDirectories(bytes);
    Files.createDirectories(staging);
    lockRoot = root.toRealPath();
  }

  /** Excludes collection while bytes and their references are being published. */
  public interface PublicationLease extends AutoCloseable {
    @Override
    void close();
  }

  public PublicationLease publicationLease() {
    return MouseVideoPublicationLock.acquire(lockRoot);
  }

  /** Worker scope: acquires exclusion lazily at the first byte publication, after decoding. */
  public PublicationBatch publicationBatch() {
    if (publicationBatch.get() != null) throw new IllegalStateException("Nested publication batch");
    PublicationBatch batch = new PublicationBatch();
    publicationBatch.set(batch);
    return batch;
  }

  public final class PublicationBatch implements AutoCloseable {
    private MouseVideoPublicationLock.Lease lease;

    private void ensure() {
      if (lease == null) lease = MouseVideoPublicationLock.acquire(lockRoot);
    }

    /** Releases an already-committed acquisition before later decode work begins. */
    public void checkpoint() {
      if (lease != null) {
        lease.close();
        lease = null;
      }
    }

    @Override
    public void close() {
      checkpoint();
      publicationBatch.remove();
    }
  }

  /** A published-ready blob: checksum plus the staging path holding the streamed bytes. */
  public record StagedArtifact(String checksum, long sizeBytes, Path stagingPath) {}

  /**
   * Streams {@code input} into an owned staging file, enforcing {@code maxBytes}, and returns the
   * digest address. The caller must either {@link #publish} or {@link #discard} the result.
   */
  public StagedArtifact stage(InputStream input, long maxBytes) throws IOException {
    Path stagingDir = staging.resolve(UUID.randomUUID().toString());
    Files.createDirectories(stagingDir);
    Path target = stagingDir.resolve("blob");
    MessageDigest digest = newDigest();
    long total = 0L;
    byte[] buffer = new byte[BUFFER_BYTES];
    try (DigestInputStream digesting = new DigestInputStream(input, digest);
        OutputStream out = Files.newOutputStream(target)) {
      int read;
      while ((read = digesting.read(buffer)) != -1) {
        total += read;
        if (total > maxBytes) {
          throw new MouseVideoException(
              MouseVideoException.CODE_MEDIA_LIMIT,
              "Uploaded bytes exceed the configured limit of " + maxBytes + " bytes");
        }
        out.write(buffer, 0, read);
      }
    } catch (IOException | RuntimeException failure) {
      deleteQuietly(stagingDir);
      throw failure;
    }
    String checksum = HASH_PREFIX + HexFormat.of().formatHex(digest.digest());
    return new StagedArtifact(checksum, total, target);
  }

  /** Creates a fresh, server-owned staging directory for one intake attempt. */
  public Path newStagingDir() throws IOException {
    Path directory = staging.resolve(UUID.randomUUID().toString());
    Files.createDirectories(directory);
    return directory;
  }

  /**
   * Wraps an already streamed and hashed intake file without reading it again. The caller must have
   * computed the SHA-256 while writing and must not mutate the file afterwards.
   */
  public StagedArtifact adopt(Path file, String checksum, long sizeBytes) throws IOException {
    long actual = Files.size(file);
    if (actual != sizeBytes) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CHECKSUM_MISMATCH,
          "Staged upload length changed before publication");
    }
    return new StagedArtifact(checksum, sizeBytes, file);
  }

  /** Atomically publishes a staged blob to its content address and returns the final path. */
  public Path publish(StagedArtifact staged) throws IOException {
    try {
      PublicationBatch batch = publicationBatch.get();
      if (batch != null) batch.ensure();
      try (var ignored = MouseVideoPublicationLock.acquire(lockRoot)) {
        return publishLocked(staged);
      }
    } catch (IOException | RuntimeException failure) {
      discard(staged);
      throw failure;
    }
  }

  private Path publishLocked(StagedArtifact staged) throws IOException {
    Path destination = finalPath(staged.checksum());
    Files.createDirectories(destination.getParent());
    if (Files.exists(destination)) {
      verifyFile(destination, staged.checksum(), staged.sizeBytes());
      discard(staged);
      return destination;
    }
    try {
      Files.createLink(destination, staged.stagingPath());
      discard(staged);
      return destination;
    } catch (IOException unsupportedOrExisting) {
      if (Files.exists(destination)) {
        verifyFile(destination, staged.checksum(), staged.sizeBytes());
        discard(staged);
        return destination;
      }
    }
    // Hard links can be unavailable across filesystems. Copy into a sibling temporary file, flush
    // it, verify the address and publish with an atomic no-replace move so no partial file ever
    // appears at the final content-address pathname.
    Path temporary = Files.createTempFile(destination.getParent(), ".intake-", ".tmp");
    try {
      Files.copy(staged.stagingPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
      try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      verifyFile(temporary, staged.checksum(), staged.sizeBytes());
      try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.FileAlreadyExistsException raced) {
        verifyFile(destination, staged.checksum(), staged.sizeBytes());
      } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
        try {
          Files.move(temporary, destination);
        } catch (java.nio.file.FileAlreadyExistsException raced) {
          verifyFile(destination, staged.checksum(), staged.sizeBytes());
        }
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
    discard(staged);
    return destination;
  }

  public void discard(StagedArtifact staged) {
    if (staged == null || staged.stagingPath() == null) {
      return;
    }
    Path parent = staged.stagingPath().getParent();
    deleteQuietly(parent == null ? staged.stagingPath() : parent);
  }

  /** Absolute final path for a validated content address. */
  public Path finalPath(String checksum) {
    String hex = hexOf(checksum);
    return bytes.resolve(hex.substring(0, 2)).resolve(hex);
  }

  /** All published content-addressed files, for reference-aware orphan reconciliation. */
  public java.util.List<Path> publishedPaths() throws IOException {
    if (!Files.isDirectory(bytes)) {
      return java.util.List.of();
    }
    try (var walk = Files.walk(bytes, 2)) {
      return walk.filter(Files::isRegularFile).toList();
    }
  }

  /** Removes a published file if it is unreferenced; returns true when it was deleted. */
  public boolean deletePublished(Path path) {
    try {
      String filename = path.getFileName().toString();
      if (!HEX64.matcher(filename).matches()) {
        return false;
      }
      return Files.deleteIfExists(path);
    } catch (IOException ignored) {
      return false;
    }
  }

  public Optional<Path> locate(String checksum) {
    Path path = finalPath(checksum);
    return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
  }

  public boolean verify(String checksum) {
    return locate(checksum).map(path -> verifyQuietly(path, checksum)).orElse(false);
  }

  /** Re-streams the stored file and confirms both the length and the SHA-256 address. */
  public void verifyFile(Path path, String checksum, long expectedSize) throws IOException {
    String actual = digestFile(path, expectedSize);
    if (!actual.equals(checksum)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CHECKSUM_MISMATCH,
          "Stored artifact does not match its content address");
    }
  }

  public String digestFile(Path path, long maxBytes) throws IOException {
    MessageDigest digest = newDigest();
    long total = 0L;
    byte[] buffer = new byte[BUFFER_BYTES];
    try (InputStream in = Files.newInputStream(path);
        DigestInputStream digesting = new DigestInputStream(in, digest)) {
      int read;
      while ((read = digesting.read(buffer)) != -1) {
        total += read;
        if (total > maxBytes) {
          throw new MouseVideoException(
              MouseVideoException.CODE_MEDIA_LIMIT, "Stored artifact exceeds its recorded size");
        }
      }
    }
    return HASH_PREFIX + HexFormat.of().formatHex(digest.digest());
  }

  public String digest(byte[] content) {
    MessageDigest digest = newDigest();
    return HASH_PREFIX + HexFormat.of().formatHex(digest.digest(content));
  }

  public String digest(String value) {
    return digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private boolean verifyQuietly(Path path, String checksum) {
    try {
      return digestFile(path, Long.MAX_VALUE).equals(checksum);
    } catch (IOException failure) {
      return false;
    }
  }

  private static String hexOf(String checksum) {
    String hex =
        checksum.startsWith(HASH_PREFIX) ? checksum.substring(HASH_PREFIX.length()) : checksum;
    if (!HEX64.matcher(hex).matches()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "Invalid content address");
    }
    return hex;
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is always available", impossible);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      if (path == null) {
        return;
      }
      if (Files.isDirectory(path)) {
        try (var entries = Files.list(path)) {
          for (Path child : entries.toList()) {
            deleteQuietly(child);
          }
        }
      }
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort staging cleanup; orphans are reconciled by the job recovery sweep.
    }
  }
}

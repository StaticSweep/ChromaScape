package com.chromascape.web.replay.artifact;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Content-addressed artifact storage.
 *
 * <p>Model: the final store is globally content-addressed ({@code <root>/<xx>/<sha256hex>}) while
 * {@code artifact_refs} rows are per-run references with uniqueness {@code (run_id, kind,
 * checksum)}.
 *
 * <p>Lifecycle split for fencing correctness:
 *
 * <ol>
 *   <li>{@link #prepareArtifact} writes bytes to per-run staging and touches no database row.
 *   <li>Registration (STAGING row insert) happens only through the fenced worker persistence
 *       service.
 *   <li>{@link #publishArtifacts} verifies and moves staged bytes into the final content address
 *       and returns a receipt — it never mutates database rows. FINALIZED marking happens inside
 *       the fenced {@code finalizeSuccessfulRun} transaction.
 * </ol>
 *
 * <p>Publication races: an existing final file is never overwritten with {@code REPLACE_EXISTING}.
 * The winner is verified instead; identical content is reused (the redundant staged copy is
 * deleted), differing content fails publication while preserving both files as evidence.
 */
@Service
public class ReplayArtifactService {

  private static final String HASH_PREFIX = "sha256:";

  private final ArtifactRefRepository artifactRefRepository;
  private final ReplayProperties properties;

  public ReplayArtifactService(
      ArtifactRefRepository artifactRefRepository, ReplayProperties properties) {
    this.artifactRefRepository = artifactRefRepository;
    this.properties = properties;
  }

  /**
   * Stages artifact content without creating a database row. The file lands at the deterministic
   * path {@code <root>/staging/<runId>/<kind>/<checksumHex>}: repeated preparation of identical
   * {@code (run, kind, checksum)} content reuses the same staged bytes instead of orphaning a
   * UUID-named duplicate.
   *
   * <p>Write protocol: bytes go to a temporary sibling file first, are re-verified there, and are
   * then moved into place atomically. If the deterministic path already exists its content is
   * verified against the freshly computed checksum and the temporary duplicate is discarded.
   *
   * @throws ReplayException when the content exceeds the configured maximum
   */
  public PreparedArtifact prepareArtifact(
      UUID runId, ArtifactKind kind, byte[] content, String mediaType) {
    long maxBytes = properties.getArtifacts().getMaxArtifactBytes();
    if (content.length > maxBytes) {
      throw new ReplayException(
          ReplayErrorCode.INTERNAL_ERROR,
          org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
          false,
          "Artifact exceeds the configured maximum size",
          "Increase replay.artifacts.max-artifact-bytes or reduce artifact size",
          null,
          null);
    }
    String checksum = HASH_PREFIX + sha256Hex(content);
    Path stagingPath = stagingPath(runId, kind, checksum);
    try {
      Files.createDirectories(stagingPath.getParent());
      if (Files.exists(stagingPath)) {
        // Deterministic winner already staged: verify it and drop nothing new.
        if (!checksum.equals(HASH_PREFIX + sha256HexBounded(stagingPath, content.length, kind))) {
          throw new ReplayException(
              ReplayErrorCode.CHECKSUM_MISMATCH,
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
              false,
              "Existing staged artifact failed verification against identical content address",
              null,
              "kind=" + kind,
              null);
        }
        return new PreparedArtifact(
            UUID.randomUUID(), runId, kind, checksum, content.length, mediaType, stagingPath);
      }
      Path temp = stagingPath.resolveSibling("." + UUID.randomUUID() + ".tmp");
      try {
        Files.write(temp, content);
        if (!checksum.equals(HASH_PREFIX + sha256HexBounded(temp, content.length, kind))) {
          throw new ReplayException(
              ReplayErrorCode.CHECKSUM_MISMATCH,
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
              false,
              "Staged temporary artifact failed verification before move",
              null,
              "kind=" + kind,
              null);
        }
        try {
          Files.move(temp, stagingPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
          Files.move(temp, stagingPath);
        }
      } finally {
        deleteQuietly(temp);
      }
    } catch (IOException e) {
      throw storageUnavailable("Failed to stage artifact content", e);
    }
    return new PreparedArtifact(
        UUID.randomUUID(), runId, kind, checksum, content.length, mediaType, stagingPath);
  }

  /** Deterministic staging location for one (run, kind, checksum) triple. */
  private Path stagingPath(UUID runId, ArtifactKind kind, String checksum) {
    return Path.of(properties.getArtifacts().getRoot())
        .resolve("staging")
        .resolve(runId.toString())
        .resolve(kind.name())
        .resolve(hexOf(checksum));
  }

  /**
   * Deletes a prepared staging file after a failed registration attempt. Best effort: retention
   * cleanup removes any remaining orphan.
   */
  public void discardPrepared(PreparedArtifact prepared) {
    deleteQuietly(prepared.stagingPath());
  }

  /**
   * Publishes every STAGING artifact of a run to its content-addressed final path and returns a
   * receipt of verified publications carrying exact reference row identities. Performs no database
   * writes whatsoever.
   */
  public ArtifactPublicationReceipt publishArtifacts(UUID runId) {
    List<ArtifactRef> artifacts =
        artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING);
    List<PublishedArtifact> published = new ArrayList<>();
    for (ArtifactRef ref : artifacts) {
      PublishedArtifact.PublicationDisposition disposition = publishOne(ref);
      published.add(
          new PublishedArtifact(
              ref.getId(),
              runId,
              ref.getKind(),
              ref.getChecksum(),
              ref.getSizeBytes(),
              ref.getMediaType(),
              disposition));
    }
    cleanEmptyStagingDirectory(runId);
    return new ArtifactPublicationReceipt(runId, List.copyOf(published));
  }

  private PublishedArtifact.PublicationDisposition publishOne(ArtifactRef ref) {
    String hex = hexOf(ref.getChecksum());
    Path root = Path.of(properties.getArtifacts().getRoot());
    Path finalPath = root.resolve(hex.substring(0, 2)).resolve(hex);
    Path staged = stagingPath(ref.getRunId(), ref.getKind(), ref.getChecksum());
    if (!Files.exists(staged)) {
      // Legacy pre-V18 staged file named by reference id: verify before use.
      Path legacy =
          root.resolve("staging")
              .resolve(ref.getRunId().toString())
              .resolve(ref.getId().toString());
      if (Files.exists(legacy)) {
        staged = legacy;
      }
    }

    boolean stagedExists = Files.exists(staged);
    boolean finalExists = Files.exists(finalPath);

    if (!stagedExists && !finalExists) {
      // Preserve evidence: neither source nor destination exists.
      throw new ReplayException(
          ReplayErrorCode.ARTIFACT_PUBLICATION_FAILED,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          true,
          "Staged artifact content is missing",
          null,
          "kind=" + ref.getKind() + ",checksum=" + ref.getChecksum(),
          null);
    }

    if (finalExists && !verifyFinalFile(ref, finalPath)) {
      throw new ReplayException(
          ReplayErrorCode.CHECKSUM_MISMATCH,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Existing final artifact failed checksum verification; blocking publication",
          null,
          "kind=" + ref.getKind() + ",checksum=" + ref.getChecksum(),
          null);
    }
    if (finalExists) {
      // Verified winner already published by another run: reuse it.
      deleteQuietly(staged);
      return PublishedArtifact.PublicationDisposition.REUSED_EXISTING;
    }

    try {
      verifyStagedFile(ref, staged);
      Files.createDirectories(finalPath.getParent());
      PublishedArtifact.PublicationDisposition disposition = moveIntoPlace(ref, staged, finalPath);
      if (!verifyFinalFile(ref, finalPath)) {
        throw new ReplayException(
            ReplayErrorCode.ARTIFACT_PUBLICATION_FAILED,
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
            false,
            "Published artifact failed post-move verification",
            null,
            null,
            null);
      }
      return disposition;
    } catch (IOException e) {
      throw storageUnavailable("Artifact publication I/O failure", e);
    }
  }

  /**
   * Moves staged bytes into their final path. Atomic first; on failure the plain move is attempted
   * WITHOUT REPLACE_EXISTING so a concurrent winner's file can never be clobbered. If a winner
   * appears, it is verified and reused (identical) or publication fails loudly (different bytes at
   * the same content address means corruption).
   */
  private PublishedArtifact.PublicationDisposition moveIntoPlace(
      ArtifactRef ref, Path staged, Path finalPath) throws IOException {
    // A hard link is an atomic create-without-replace operation on the same filesystem. It gives
    // concurrent publishers an unambiguous CREATED/REUSED_EXISTING result without allowing an
    // ATOMIC_MOVE provider to replace an existing content-addressed file.
    try {
      Files.createLink(finalPath, staged);
      deleteQuietly(staged);
      return PublishedArtifact.PublicationDisposition.CREATED;
    } catch (FileAlreadyExistsException alreadyExists) {
      return reuseExisting(ref, staged, finalPath);
    } catch (UnsupportedOperationException | SecurityException unsupported) {
      // Fall back to a non-replacing move on providers that do not support hard links.
    } catch (IOException linkFailure) {
      // A concurrent publisher may have created the destination, or this provider may reject hard
      // links. Check for the deterministic winner before using the non-replacing fallback.
      if (Files.exists(finalPath)) {
        return reuseExisting(ref, staged, finalPath);
      }
    }
    if (Files.exists(finalPath)) {
      return reuseExisting(ref, staged, finalPath);
    }
    try {
      // No REPLACE_EXISTING anywhere: a race loser must not overwrite a winner.
      Files.move(staged, finalPath);
      return PublishedArtifact.PublicationDisposition.CREATED;
    } catch (IOException lostRace) {
      if (Files.exists(finalPath) && verifyFinalFile(ref, finalPath)) {
        deleteQuietly(staged);
        return PublishedArtifact.PublicationDisposition.REUSED_EXISTING;
      }
      throw lostRace;
    }
  }

  private PublishedArtifact.PublicationDisposition reuseExisting(
      ArtifactRef ref, Path staged, Path finalPath) {
    if (!verifyFinalFile(ref, finalPath)) {
      throw new ReplayException(
          ReplayErrorCode.ARTIFACT_PUBLICATION_FAILED,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Concurrent publication produced different content at the same address",
          null,
          "kind=" + ref.getKind() + ",checksum=" + ref.getChecksum(),
          null);
    }
    deleteQuietly(staged); // identical winner; drop the redundant copy
    return PublishedArtifact.PublicationDisposition.REUSED_EXISTING;
  }

  /**
   * Bounded streaming verification: size is checked BEFORE any read, content is streamed through a
   * digest with an enforced byte ceiling, and BOTH exact declared size and checksum must match.
   * {@code Files.readAllBytes()} is never used on stored artifact content.
   */
  private void verifyStagedFile(ArtifactRef ref, Path staged) throws IOException {
    long declared = ref.getSizeBytes();
    if (Files.size(staged) != declared
        || declared > properties.getArtifacts().getMaxArtifactBytes()) {
      throw new ReplayException(
          ReplayErrorCode.ARTIFACT_PUBLICATION_FAILED,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Staged artifact size does not match its declared reference size",
          null,
          "kind=" + ref.getKind(),
          null);
    }
    String actual = HASH_PREFIX + sha256HexBounded(staged, declared, ref.getKind());
    if (!ref.getChecksum().equals(actual)) {
      throw new ReplayException(
          ReplayErrorCode.CHECKSUM_MISMATCH,
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Staged artifact failed checksum verification before publication",
          null,
          null,
          null);
    }
  }

  /** Returns true when the final file exists and matches size and checksum exactly. */
  private boolean verifyFinalFile(ArtifactRef ref, Path finalPath) {
    try {
      long declared = ref.getSizeBytes();
      if (Files.size(finalPath) != declared
          || declared > properties.getArtifacts().getMaxArtifactBytes()) {
        return false;
      }
      return ref.getChecksum()
          .equals(HASH_PREFIX + sha256HexBounded(finalPath, declared, ref.getKind()));
    } catch (IOException e) {
      return false;
    }
  }

  /** Streams at most {@code maxBytes} through SHA-256; larger content fails closed. */
  private String sha256HexBounded(Path file, long maxBytes, ArtifactKind kind) throws IOException {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable; refusing insecure fallback", e);
    }
    long total = 0;
    try (java.io.InputStream in = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > maxBytes) {
          throw new ReplayException(
              ReplayErrorCode.ARTIFACT_PUBLICATION_FAILED,
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
              false,
              "Stored artifact exceeded its declared size during verification",
              null,
              "kind=" + kind,
              null);
        }
        md.update(buffer, 0, read);
      }
    }
    StringBuilder sb = new StringBuilder(64);
    for (byte b : md.digest()) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  /** Removes the run's staging tree when fully empty; failed staging is retained per config. */
  private void cleanEmptyStagingDirectory(UUID runId) {
    Path stagingDir =
        Path.of(properties.getArtifacts().getRoot()).resolve("staging").resolve(runId.toString());
    try (Stream<Path> kindDirs = Files.list(stagingDir)) {
      for (Path kindDir : kindDirs.toList()) {
        if (Files.isDirectory(kindDir)) {
          try (Stream<Path> entries = Files.list(kindDir)) {
            if (entries.findAny().isEmpty()) {
              Files.deleteIfExists(kindDir);
            }
          }
        }
      }
      try (Stream<Path> remaining = Files.list(stagingDir)) {
        if (remaining.findAny().isEmpty()) {
          Files.deleteIfExists(stagingDir);
        }
      }
    } catch (IOException ignored) {
      // Non-empty or unreadable: leave in place for retention cleanup.
    }
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException ignored) {
      // best effort
    }
  }

  private static String hexOf(String checksum) {
    return checksum.startsWith(HASH_PREFIX) ? checksum.substring(HASH_PREFIX.length()) : checksum;
  }

  /** Returns the finalized content bytes for an artifact reference after bounded validation. */
  public byte[] readFinalized(ArtifactRef ref) {
    String hex = hexOf(ref.getChecksum());
    Path root = Path.of(properties.getArtifacts().getRoot());
    Path finalPath = root.resolve(hex.substring(0, 2)).resolve(hex);
    try {
      long declared = ref.getSizeBytes();
      if (declared < 0 || declared > properties.getArtifacts().getMaxArtifactBytes()) {
        throw new ReplayException(
            ReplayErrorCode.CHECKSUM_MISMATCH,
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
            false,
            "Finalized artifact size exceeds its content address record",
            null,
            null,
            null);
      }
      MessageDigest digest = newDigest();
      ByteArrayOutputStream content = new ByteArrayOutputStream((int) Math.min(declared, 8192L));
      long total = 0;
      try (java.io.InputStream in = Files.newInputStream(finalPath)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
          total += read;
          if (total > declared || total > properties.getArtifacts().getMaxArtifactBytes()) {
            throw new ReplayException(
                ReplayErrorCode.CHECKSUM_MISMATCH,
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                false,
                "Finalized artifact size does not match its content address record",
                null,
                null,
                null);
          }
          digest.update(buffer, 0, read);
          content.write(buffer, 0, read);
        }
      }
      if (total != declared) {
        throw new ReplayException(
            ReplayErrorCode.CHECKSUM_MISMATCH,
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
            false,
            "Finalized artifact size does not match its content address record",
            null,
            null,
            null);
      }
      String actual = HASH_PREFIX + hexDigest(digest.digest());
      if (!ref.getChecksum().equals(actual)) {
        throw new ReplayException(
            ReplayErrorCode.CHECKSUM_MISMATCH,
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
            false,
            "Finalized artifact failed checksum verification",
            null,
            null,
            null);
      }
      return content.toByteArray();
    } catch (IOException e) {
      throw storageUnavailable("Artifact content is unavailable", e);
    }
  }

  /** Compatibility name used by the viewer; readFinalized already verifies size and checksum. */
  public byte[] readVerified(ArtifactRef ref) {
    return readFinalized(ref);
  }

  /** Returns the canonical content address used by artifact references. */
  public String checksum(byte[] content) {
    return HASH_PREFIX + sha256Hex(content);
  }

  /** Narrowly classified artifact-storage failure: the ONLY class eligible for DB-only fallback. */
  private static ReplayException storageUnavailable(String message, IOException cause) {
    return new ReplayException(
        ReplayErrorCode.ARTIFACT_STORAGE_UNAVAILABLE,
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
        true,
        message,
        null,
        null,
        cause);
  }

  private static String sha256Hex(byte[] content) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return hexDigest(md.digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable; refusing insecure fallback", e);
    }
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable; refusing insecure fallback", e);
    }
  }

  private static String hexDigest(byte[] digest) {
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}

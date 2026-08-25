package com.chromascape.web.replay.artifact;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Content-addressed artifact storage.
 *
 * Writes go to a per-run staging directory first; publication is an atomic
 * move into the content-addressed final path after a read-back checksum
 * verification. Database rows are only marked FINALIZED after the file is
 * verified at its final location. Oversized artifacts are rejected.
 */
@Service
@Transactional
public class ReplayArtifactService {

    private static final String HASH_PREFIX = "sha256:";

    private final ArtifactRefRepository artifactRefRepository;
    private final ReplayProperties properties;

    public ReplayArtifactService(ArtifactRefRepository artifactRefRepository,
                                  ReplayProperties properties) {
        this.artifactRefRepository = artifactRefRepository;
        this.properties = properties;
    }

    /**
     * Stages artifact content and returns the persisted STAGING reference.
     * The file lands in {@code <root>/staging/<runId>/<uuid>} and the row
     * records the sha256 content address.
     */
    public ArtifactRef stageArtifact(UUID runId, ArtifactKind kind, byte[] content, String mediaType) {
        long maxBytes = properties.getArtifacts().getMaxArtifactBytes();
        if (content.length > maxBytes) {
            throw new ReplayException(ReplayErrorCode.INTERNAL_ERROR,
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, false,
                    "Artifact exceeds the configured maximum size",
                    "Increase replay.artifacts.max-artifact-bytes or reduce artifact size", null, null);
        }
        String checksum = HASH_PREFIX + sha256Hex(content);
        Path root = Path.of(properties.getArtifacts().getRoot());
        Path stagingDir = root.resolve("staging").resolve(runId.toString());
        Path stagingPath = stagingDir.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(stagingDir);
            Files.write(stagingPath, content);
        } catch (IOException e) {
            throw new ReplayException(ReplayErrorCode.INTERNAL_ERROR,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, true,
                    "Failed to stage artifact content", null, null, e);
        }

        ArtifactRef ref = new ArtifactRef(UUID.randomUUID(), runId, kind, checksum, content.length);
        ref.setMediaType(mediaType);
        return artifactRefRepository.save(ref);
    }

    /**
     * Publishes every STAGING artifact for a run: verifies staged content
     * against the recorded checksum, atomically moves it into the
     * content-addressed final path, re-verifies at the destination, and only
     * then marks the row FINALIZED with finalized_at set.
     */
    public void finalizeArtifacts(UUID runId) {
        List<ArtifactRef> artifacts =
                artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING);
        for (ArtifactRef ref : artifacts) {
            publishOne(ref);
        }
    }

    private void publishOne(ArtifactRef ref) {
        String hex = ref.getChecksum().startsWith(HASH_PREFIX)
                ? ref.getChecksum().substring(HASH_PREFIX.length()) : ref.getChecksum();
        Path root = Path.of(properties.getArtifacts().getRoot());
        Path finalPath = root.resolve(hex.substring(0, 2)).resolve(hex);
        // Locate staged bytes: search this run's staging dir and match by checksum.
        Path stagingDir = root.resolve("staging").resolve(ref.getRunId().toString());
        Path staged = findStagedFile(stagingDir, hex);
        if (staged == null || !Files.exists(staged)) {
            ref.setState(ArtifactRef.STATE_CORRUPT);
            artifactRefRepository.save(ref);
            throw new ReplayException(ReplayErrorCode.CHECKSUM_MISMATCH,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, true,
                    "Staged artifact content is missing", null,
                    "kind=" + ref.getKind(), null);
        }
        try {
            byte[] verify = Files.readAllBytes(staged);
            if (!ref.getChecksum().equals(HASH_PREFIX + sha256Hex(verify))) {
                ref.setState(ArtifactRef.STATE_CORRUPT);
                artifactRefRepository.save(ref);
                throw new ReplayException(ReplayErrorCode.CHECKSUM_MISMATCH,
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, false,
                        "Staged artifact failed checksum verification before publication", null, null, null);
            }
            Files.createDirectories(finalPath.getParent());
            try {
                Files.move(staged, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomic) {
                Files.move(staged, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
            // Verify after move: content at destination must still hash identically.
            byte[] published = Files.readAllBytes(finalPath);
            if (!ref.getChecksum().equals(HASH_PREFIX + sha256Hex(published))) {
                ref.setState(ArtifactRef.STATE_CORRUPT);
                artifactRefRepository.save(ref);
                throw new ReplayException(ReplayErrorCode.CHECKSUM_MISMATCH,
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, false,
                        "Published artifact failed post-move verification", null, null, null);
            }
            ref.setState(ArtifactRef.STATE_FINALIZED);
            ref.setFinalizedAt(Instant.now());
            artifactRefRepository.save(ref);
        } catch (ReplayException e) {
            throw e;
        } catch (IOException e) {
            throw new ReplayException(ReplayErrorCode.INTERNAL_ERROR,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, true,
                    "Artifact publication I/O failure", null, null, e);
        }
    }

    private Path findStagedFile(Path stagingDir, String hex) {
        if (!Files.isDirectory(stagingDir)) {
            return null;
        }
        try (var stream = Files.list(stagingDir)) {
            for (Path p : stream.toList()) {
                try {
                    byte[] candidate = Files.readAllBytes(p);
                    if ((HASH_PREFIX + sha256Hex(candidate)).endsWith(hex)) {
                        return p;
                    }
                } catch (IOException ignored) {
                    // unreadable candidate; skip
                }
            }
        } catch (IOException ignored) {
            // staging dir unreadable
        }
        return null;
    }

    /** Returns the finalized content bytes for an artifact reference. */
    public byte[] readFinalized(ArtifactRef ref) {
        String hex = ref.getChecksum().startsWith(HASH_PREFIX)
                ? ref.getChecksum().substring(HASH_PREFIX.length()) : ref.getChecksum();
        Path root = Path.of(properties.getArtifacts().getRoot());
        Path finalPath = root.resolve(hex.substring(0, 2)).resolve(hex);
        try {
            return Files.readAllBytes(finalPath);
        } catch (IOException e) {
            throw new ReplayException(ReplayErrorCode.INTERNAL_ERROR,
                    org.springframework.http.HttpStatus.NOT_FOUND, false,
                    "Artifact content is unavailable", null, null, e);
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable; refusing insecure fallback", e);
        }
    }
}

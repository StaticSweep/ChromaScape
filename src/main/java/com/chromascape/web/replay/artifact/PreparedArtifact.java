package com.chromascape.web.replay.artifact;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Staged-but-not-yet-registered artifact. Carries everything the fenced persistence service needs
 * to insert the STAGING reference row plus the filesystem location of the prepared bytes.
 *
 * <p>The staging path is deterministic ({@code <root>/staging/<runId>/<kind>/<checksumHex>}), so
 * repeated preparation of identical {@code (run, kind, checksum)} content reuses the same staged
 * bytes and registration reuse can never orphan a UUID-named duplicate. The proposed reference id
 * is a database identity only — never a filesystem name.
 *
 * @param proposedReferenceId pre-generated reference row id (database identity only)
 * @param runId owning run
 * @param kind artifact kind
 * @param checksum sha256:&lt;hex&gt; content address of the bytes
 * @param sizeBytes content length in bytes
 * @param mediaType media type recorded on the reference
 * @param stagingPath deterministic staged file location under {@code <root>/staging}
 */
public record PreparedArtifact(
    UUID proposedReferenceId,
    UUID runId,
    ArtifactKind kind,
    String checksum,
    long sizeBytes,
    String mediaType,
    Path stagingPath) {}

package com.chromascape.web.replay.artifact;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import java.util.UUID;

/**
 * One verified publication entry inside an {@link ArtifactPublicationReceipt}.
 *
 * @param artifactRefId database identity of the published reference row
 * @param runId owning run (verified against both the receipt and the reference row)
 * @param kind artifact kind of the published reference
 * @param checksum sha256:&lt;hex&gt; content address verified at the final path
 * @param sizeBytes verified content length
 * @param mediaType verified media type recorded on the reference row
 * @param disposition whether this call created the final file or reused an already verified winner
 */
public record PublishedArtifact(
    UUID artifactRefId,
    UUID runId,
    ArtifactKind kind,
    String checksum,
    long sizeBytes,
    String mediaType,
    PublicationDisposition disposition) {

  /** Internal publication result; it is not part of the replay HTTP contract. */
  public enum PublicationDisposition {
    CREATED,
    REUSED_EXISTING,
    UNKNOWN
  }

  /** Compatibility constructor for callers that only need receipt identity fields. */
  public PublishedArtifact(
      UUID artifactRefId,
      UUID runId,
      ArtifactKind kind,
      String checksum,
      long sizeBytes,
      String mediaType) {
    this(
        artifactRefId, runId, kind, checksum, sizeBytes, mediaType, PublicationDisposition.UNKNOWN);
  }
}

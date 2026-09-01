package com.chromascape.web.replay.artifact;

import java.util.List;
import java.util.UUID;

/**
 * Receipt returned by {@code publishArtifacts}: every listed reference has been byte-verified at
 * its final content-addressed path during this call. Database rows are NOT marked FINALIZED by
 * publication; that happens only inside the fenced {@code finalizeSuccessfulRun} transaction, so a
 * stale worker can never finalize another worker's artifacts.
 */
public record ArtifactPublicationReceipt(UUID runId, List<PublishedArtifact> published) {}

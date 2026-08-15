package com.chromascape.web.accounttask.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks every staged and promoted artifact id allocated during one ingestion so file cleanup is
 * exact and loss-proof (SC-572). A promoted id is recorded immediately after each successful
 * promote, before the next artifact is promoted, so a partial promotion, staging failure, JPA
 * flush failure or commit failure can clean every prior file. On rollback every staged and
 * promoted artifact is deleted; on commit only staging/journal files are removed.
 */
public final class StabilizationIngestionResourceTracker {

  private final Set<UUID> staged = Collections.synchronizedSet(new LinkedHashSet<>());
  private final Set<UUID> promoted = Collections.synchronizedSet(new LinkedHashSet<>());

  public void trackStaged(UUID id) {
    if (id != null) {
      staged.add(id);
    }
  }

  public void trackPromoted(UUID id) {
    if (id != null) {
      promoted.add(id);
    }
  }

  public void rollback(StabilizationArtifactStore artifactStore) {
    if (artifactStore == null) {
      return;
    }
    for (UUID id : promoted) {
      artifactStore.delete(id);
    }
    for (UUID id : staged) {
      artifactStore.deleteStaged(id);
    }
  }

  /** Commit path: only staging/journal files are removed; promoted final artifacts are durable. */
  public void commit(StabilizationArtifactStore artifactStore) {
    if (artifactStore == null) {
      return;
    }
    for (UUID id : staged) {
      artifactStore.deleteStaged(id);
    }
  }
}

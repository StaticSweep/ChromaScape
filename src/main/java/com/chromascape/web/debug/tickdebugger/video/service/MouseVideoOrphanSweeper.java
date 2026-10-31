package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reference-aware reconciliation for content-addressed byte objects. A crash between byte
 * publication and SQL registration can leave a complete but unreferenced object; such objects are
 * removed only after a safety grace period and only when no artifact reference names their address.
 * Shared objects referenced by any owner are never deleted.
 */
@Service
public class MouseVideoOrphanSweeper {

  private final MouseVideoProperties properties;
  private final MouseVideoArtifactRefRepository artifacts;
  private final MouseVideoFramePreviewRepository previews;
  private final MouseVideoByteStore byteStore;

  public MouseVideoOrphanSweeper(
      MouseVideoProperties properties,
      MouseVideoArtifactRefRepository artifacts,
      MouseVideoFramePreviewRepository previews,
      MouseVideoByteStore byteStore) {
    this.properties = properties;
    this.artifacts = artifacts;
    this.previews = previews;
    this.byteStore = byteStore;
  }

  @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public void sweep() {
    if (!properties.isEnabled()) {
      return;
    }
    sweepOnce(Instant.now().minusSeconds(Math.max(60L, properties.getOrphanGraceSeconds())), false);
  }

  /** Testable sweep: {@code agingOnly} disables the grace cutoff when true. */
  @Transactional(
      readOnly = true,
      propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
      isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public int sweepOnce(Instant cutoff, boolean includeAll) {
    try (var publication = byteStore.publicationLease()) {
      artifacts.lockPublication();
      Set<String> referenced = new HashSet<>(artifacts.findDistinctChecksums());
      // Exact previews are shared content-addressed bytes too; a preview checksum is a live
      // reference even when no artifact row names it.
      referenced.addAll(previews.findDistinctChecksums());
      referenced.addAll(artifacts.findDistinctTemplateChecksums());
      int removed = 0;
      try {
        for (Path path : byteStore.publishedPaths()) {
          String checksum = "sha256:" + path.getFileName().toString();
          if (referenced.contains(checksum)) {
            continue;
          }
          if (!includeAll && Files.getLastModifiedTime(path).toInstant().isAfter(cutoff)) {
            continue;
          }
          if (byteStore.deletePublished(path)) {
            removed++;
          }
        }
      } catch (IOException ignored) {
        // A concurrent publish/delete race is benign; the next sweep retries.
      }
      return removed + MouseVideoWorkspace.sweepUnmounted(properties, cutoff);
    }
  }
}

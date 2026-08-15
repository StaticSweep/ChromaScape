package com.chromascape.web.accounttask.service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.chromascape.web.accounttask.repository.StabilizationArtifactRepository;

/**
 * Startup recovery sweeper for uncommitted ingestion namespaces (SC-572). On startup it deletes
 * every artifact file (final opaque id or staging namespace) that has no verified database record,
 * covering process death between file promotion and database completion. A promoted artifact with a
 * database row is kept; anything else (including {@code .tmp} journal files) is removed so an
 * uncommitted namespace can never leak or be confused with a certified artifact.
 */
@Component
public class StabilizationRecoverySweeper {

  private static final Logger log = LoggerFactory.getLogger(StabilizationRecoverySweeper.class);

  private final StabilizationArtifactStore artifactStore;
  private final StabilizationArtifactRepository artifactRepository;

  public StabilizationRecoverySweeper(StabilizationArtifactStore artifactStore,
      StabilizationArtifactRepository artifactRepository) {
    this.artifactStore = artifactStore;
    this.artifactRepository = artifactRepository;
  }

  @PostConstruct
  void sweep() {
    Path baseDir = artifactStore.baseDir();
    if (baseDir == null || !Files.isDirectory(baseDir)) {
      return;
    }
    Path stagingDir = artifactStore.stagingDir();
    if (stagingDir != null && Files.isDirectory(stagingDir)) {
      sweepDirectory(stagingDir, true);
    }
    sweepDirectory(baseDir, false);
  }

  private void sweepDirectory(Path directory, boolean staging) {
    try (Stream<Path> entries = Files.list(directory)) {
      entries.forEach(path -> {
        try {
          if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            return;
          }
          String fileName = path.getFileName().toString();
          if (fileName.endsWith(".tmp")) {
            Files.deleteIfExists(path);
            log.info("[StabilizationRecovery] removed journal file {}", fileName);
            return;
          }
          // Staging files are uncommitted by definition and have no database record.
          if (staging) {
            Files.deleteIfExists(path);
            log.info("[StabilizationRecovery] removed uncommitted staging file {}", fileName);
            return;
          }
          // Final files: keep only when a verified artifact row references the opaque id.
          String uuid = fileName.endsWith(".meta")
              ? fileName.substring(0, fileName.length() - ".meta".length())
              : fileName;
          if (looksLikeUuid(uuid) && artifactRepository.findByArtifactId(uuid).isPresent()) {
            return;
          }
          Files.deleteIfExists(path);
          log.info("[StabilizationRecovery] removed orphaned artifact file {}", fileName);
        } catch (IOException e) {
          log.warn("[StabilizationRecovery] failed to sweep {}", path, e);
        }
      });
    } catch (IOException e) {
      log.warn("[StabilizationRecovery] failed to scan {}", directory, e);
    }
  }

  private static boolean looksLikeUuid(String value) {
    if (value == null) {
      return false;
    }
    try {
      java.util.UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}

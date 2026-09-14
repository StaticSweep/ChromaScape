package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.StabilizationArtifact;
import com.chromascape.web.accounttask.repository.StabilizationArtifactRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Startup recovery sweeper contract (SC-572): on startup, final artifact files and staging/journal
 * files with no verified database record are removed, covering process death between file promotion
 * and database completion. Files backed by a verified artifact row are retained.
 */
@ExtendWith(MockitoExtension.class)
class StabilizationRecoverySweeperTest {

  @Mock private StabilizationArtifactRepository artifactRepository;

  @TempDir Path tempDir;

  @Test
  @DisplayName("orphaned final and staging files are removed while verified artifacts are kept")
  void sweeperRemovesOrphansAndKeepsVerified() throws Exception {
    StabilizationArtifactStore store =
        new StabilizationArtifactStore(tempDir.resolve("store").toString());
    Path baseDir = store.baseDir();
    Files.createDirectories(baseDir);
    Path staging = store.stagingDir();
    Files.createDirectories(staging);

    // Orphaned staging file (no DB record).
    Path orphanStaging = staging.resolve("staging-orphan");
    Files.writeString(orphanStaging, "staged");

    // Orphaned final artifact + meta sidecar (no DB record).
    String orphanUuid = UUID.randomUUID().toString();
    Files.writeString(baseDir.resolve(orphanUuid), "orphan");
    Files.writeString(baseDir.resolve(orphanUuid + ".meta"), "{}");

    // Journal file.
    Path journal = baseDir.resolve("some-uuid.tmp");
    Files.writeString(journal, "journal");

    // Verified artifact: a DB record exists for this id.
    String verifiedUuid = UUID.randomUUID().toString();
    Files.writeString(baseDir.resolve(verifiedUuid), "verified-bytes");
    StabilizationArtifact verified = new StabilizationArtifact();
    verified.setArtifactId(verifiedUuid);
    when(artifactRepository.findByArtifactId(verifiedUuid)).thenReturn(Optional.of(verified));
    when(artifactRepository.findByArtifactId(orphanUuid)).thenReturn(Optional.empty());

    new StabilizationRecoverySweeper(store, artifactRepository).sweep();

    assertThat(orphanStaging).doesNotExist();
    assertThat(baseDir.resolve(orphanUuid)).doesNotExist();
    assertThat(baseDir.resolve(orphanUuid + ".meta")).doesNotExist();
    assertThat(journal).doesNotExist();
    assertThat(baseDir.resolve(verifiedUuid)).exists();
  }
}

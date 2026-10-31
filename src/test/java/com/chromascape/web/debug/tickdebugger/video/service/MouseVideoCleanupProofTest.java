package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The cleanup proof gates the only owner action that settles a NEEDS_RECONCILIATION job, so every
 * condition must fail closed: a live worker, a still-mounted workspace of any generation, an
 * unattributed video scope, or scope state that cannot be read.
 */
class MouseVideoCleanupProofTest {

  private static final Path JOBS = Path.of("/data/video/jobs");
  private final UUID jobId = UUID.randomUUID();
  private final MouseVideoWorkerRegistry workers = new MouseVideoWorkerRegistry();

  private MouseVideoCleanupProof proof(
      List<String> mounts, Optional<List<String>> active, Set<String> held) {
    return new MouseVideoCleanupProof(workers, JOBS, () -> mounts, () -> active, () -> held);
  }

  @Test
  void provesCleanupWhenNothingOfTheJobRemains() {
    MouseVideoCleanupProof.Result result =
        proof(
                List.of("/", "/data/video/jobs/" + UUID.randomUUID() + "-3"),
                Optional.of(List.of("chromascape-video-a.scope")),
                Set.of("chromascape-video-a.scope"))
            .prove(jobId);

    assertThat(result.proven()).isTrue();
    assertThat(result.evidence()).containsEntry("unattributedScopes", List.of());
  }

  @Test
  void aRegisteredWorkerBlocksTheProof() {
    workers.register(jobId, 2L);

    MouseVideoCleanupProof.Result result =
        proof(List.of(), Optional.of(List.of()), Set.of()).prove(jobId);

    assertThat(result.proven()).isFalse();
    assertThat(result.blocker()).contains("worker");
  }

  @Test
  void aMountedWorkspaceOfAnyGenerationBlocksTheProof() {
    MouseVideoCleanupProof.Result result =
        proof(List.of("/data/video/jobs/" + jobId + "-2"), Optional.of(List.of()), Set.of())
            .prove(jobId);

    assertThat(result.proven()).isFalse();
    assertThat(result.evidence()).containsEntry("workspacesMounted", List.of(jobId + "-2"));
  }

  @Test
  void anUnattributedScopeBlocksTheProof() {
    MouseVideoCleanupProof.Result result =
        proof(
                List.of(),
                Optional.of(List.of("chromascape-video-a.scope", "chromascape-video-b.scope")),
                Set.of("chromascape-video-a.scope"))
            .prove(jobId);

    assertThat(result.proven()).isFalse();
    assertThat(result.evidence())
        .containsEntry("unattributedScopes", List.of("chromascape-video-b.scope"));
  }

  @Test
  void unreadableScopeStateCannotProveCleanup() {
    MouseVideoCleanupProof.Result result =
        proof(List.of(), Optional.empty(), Set.of()).prove(jobId);

    assertThat(result.proven()).isFalse();
    assertThat(result.blocker()).contains("cannot be inspected");
  }

  @Test
  void parsesOnlyNonInactiveVideoScopes() {
    String listing =
        "chromascape-video-a.scope loaded active running chromascape-video-a.scope\n"
            + "chromascape-video-b.scope loaded deactivating stop-sigterm x\n"
            + "chromascape-video-c.scope loaded inactive dead x\n"
            + "other.scope loaded active running x\n";

    assertThat(MouseVideoCleanupProof.parseActiveScopes(listing))
        .containsExactly("chromascape-video-a.scope", "chromascape-video-b.scope");
  }

  @Test
  void decodesEscapedMountPoints() {
    assertThat(MouseVideoCleanupProof.unescape("/data/my\\040video/jobs"))
        .isEqualTo("/data/my video/jobs");
  }
}

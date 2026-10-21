package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MouseVideoWorkerRegistryTest {

  @Test
  void staleCompletionCannotReleaseAReplacementExecution() {
    MouseVideoWorkerRegistry registry = new MouseVideoWorkerRegistry();
    UUID jobId = UUID.randomUUID();
    MouseVideoCancellation first = registry.register(jobId, 2L);
    MouseVideoCancellation replacement = registry.register(jobId, 3L);

    assertThat(first.isCancelled()).isTrue();
    assertThat(replacement.isCancelled()).isFalse();

    registry.complete(jobId, 2L);

    assertThat(registry.isOwned(jobId)).isTrue();
    assertThat(registry.awaitCleanup(jobId, 50L)).isFalse();
  }

  @Test
  void exactCompletionReleasesTheExecution() {
    MouseVideoWorkerRegistry registry = new MouseVideoWorkerRegistry();
    UUID jobId = UUID.randomUUID();
    registry.register(jobId, 4L);

    registry.complete(jobId, 4L);

    assertThat(registry.isOwned(jobId)).isFalse();
    assertThat(registry.awaitCleanup(jobId, 50L)).isTrue();
  }
}

package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuilderGoalLocalLaunchCoordinatorServiceTest {

  private final BuilderGoalLocalLaunchCoordinatorService coordinator =
      new BuilderGoalLocalLaunchCoordinatorService();

  @Test
  void tryAcquire_succeedsForUniqueResource() {
    BuilderGoalLocalLaunchCoordinatorService.AcquireResult result =
        coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-1");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.handle()).isNotNull();
    assertThat(result.conflict()).isNull();
    assertThat(result.handle().machineUuid()).isEqualTo("machine-1");
    assertThat(result.handle().executorPort()).isEqualTo(8098);
    assertThat(result.handle().accountKey()).isEqualTo("hash-1");
    assertThat(result.handle().ownerId()).isEqualTo("op-1");
  }

  @Test
  void tryAcquire_conflictsForSameAccountWhileHeld() {
    coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-1");

    BuilderGoalLocalLaunchCoordinatorService.AcquireResult second =
        coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-2");

    assertThat(second.isSuccess()).isFalse();
    assertThat(second.conflict()).isNotNull();
    assertThat(second.conflict().ownerId()).isEqualTo("op-1");
  }

  @Test
  void tryAcquire_conflictsForDifferentAccountWhileHeld() {
    coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-1");

    BuilderGoalLocalLaunchCoordinatorService.AcquireResult second =
        coordinator.tryAcquire("machine-1", 8098, "hash-2", "op-2");

    assertThat(second.isSuccess()).isFalse();
    assertThat(second.conflict().ownerId()).isEqualTo("op-1");
  }

  @Test
  void conflictDescribesWorkflowTypeWithoutRawAccountKey() {
    coordinator.tryAcquire("machine-1", 8098, "hash-1", "recovery:iter-1");

    BuilderGoalLocalLaunchCoordinatorService.AcquireResult second =
        coordinator.tryAcquire("machine-1", 8098, "hash-2", "op-2");

    assertThat(second.conflict().workflowType()).isEqualTo("recovery");
    assertThat(second.conflict().ownerId()).isEqualTo("recovery:iter-1");
  }

  @Test
  void release_removesOnlyExactTokenHolder() {
    BuilderGoalLocalLaunchCoordinatorService.AcquireResult first =
        coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-1");
    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle handle = first.handle();

    coordinator.release(handle);

    assertThat(coordinator.holdsLease(handle)).isFalse();
    // The resource is free again.
    assertThat(coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-2").isSuccess()).isTrue();
  }

  @Test
  void release_staleTokenIsIgnored() {
    coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-1");
    // Simulate a stale worker's handle from a previous lease with a different token.
    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle staleHandle =
        new BuilderGoalLocalLaunchCoordinatorService.LeaseHandle(
            "machine-1", 8098, "hash-1", "op-1", java.util.UUID.randomUUID());

    coordinator.release(staleHandle);

    // The real holder's lease is unaffected.
    BuilderGoalLocalLaunchCoordinatorService.AcquireResult probe =
        coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-2");
    assertThat(probe.isSuccess()).isFalse();
    assertThat(probe.conflict().ownerId()).isEqualTo("op-1");
  }

  @Test
  void replacementLeaseCannotBeReleasedByOldWorker() {
    BuilderGoalLocalLaunchCoordinatorService.AcquireResult first =
        coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-1");
    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle oldHandle = first.handle();
    coordinator.release(oldHandle);

    BuilderGoalLocalLaunchCoordinatorService.AcquireResult replacement =
        coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-2");
    assertThat(replacement.isSuccess()).isTrue();

    // The old worker's stale handle cannot release the replacement lease.
    coordinator.release(oldHandle);
    assertThat(coordinator.holdsLease(replacement.handle())).isTrue();

    // The replacement holder can release its own lease.
    coordinator.release(replacement.handle());
    assertThat(coordinator.holdsLease(replacement.handle())).isFalse();
  }

  @Test
  void resourceKeySeparatesMachineAndPort() {
    coordinator.tryAcquire("machine-1", 8098, "hash-1", "op-1");

    // A different port on the same machine is a distinct resource.
    assertThat(coordinator.tryAcquire("machine-1", 8099, "hash-1", "op-2").isSuccess()).isTrue();
    // A different machine on the same port is a distinct resource.
    assertThat(coordinator.tryAcquire("machine-2", 8098, "hash-1", "op-3").isSuccess()).isTrue();
  }
}

package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MouseVideoLeaseHeartbeatTest {

  @Mock MouseVideoJobService jobService;

  private MouseVideoProperties properties;
  private MouseVideoLeaseHeartbeat heartbeat;

  @BeforeEach
  void setUp() {
    properties = new MouseVideoProperties();
    properties.setLeaseHeartbeatSeconds(1L);
    properties.setLeaseExpirySeconds(30L);
    heartbeat = new MouseVideoLeaseHeartbeat(jobService, properties);
  }

  @AfterEach
  void tearDown() {
    heartbeat.shutdown();
  }

  @Test
  void renewsLeaseIndependentlyAcrossAStageLongerThanOneInterval() {
    UUID jobId = UUID.randomUUID();
    MouseVideoCancellation cancellation = new MouseVideoCancellation();
    try (MouseVideoLeaseHeartbeat.Handle handle =
        heartbeat.start(jobId, 7L, "worker-1", cancellation)) {
      verify(jobService, timeout(4_000L).atLeast(2))
          .heartbeat(eq(jobId), eq(7L), eq("worker-1"), eq(30L));
      assertThat(handle.leaseLost()).isFalse();
    }
  }

  @Test
  void unexpectedOwnershipFailureStopsTheWorkerAsLeaseLoss() {
    UUID jobId = UUID.randomUUID();
    MouseVideoCancellation cancellation = new MouseVideoCancellation();
    MouseVideoJob running =
        new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "r", "d");
    running.setStatus(MouseVideoJob.STATUS_RUNNING);
    when(jobService.find(jobId)).thenReturn(Optional.of(running));
    when(jobService.heartbeat(eq(jobId), anyLong(), anyString(), anyLong()))
        .thenThrow(new IllegalStateException("lease changed"));

    try (MouseVideoLeaseHeartbeat.Handle handle =
        heartbeat.start(jobId, 7L, "worker-1", cancellation)) {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (!handle.leaseLost() && System.nanoTime() < deadline) {
        try {
          Thread.sleep(20L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      assertThat(handle.leaseLost()).isTrue();
      assertThat(cancellation.isCancelled()).isTrue();
    }
  }

  @Test
  void reconciliationRequiredStopsTheWorkerInsteadOfAssumingSettlement() {
    UUID jobId = UUID.randomUUID();
    MouseVideoCancellation cancellation = new MouseVideoCancellation();
    MouseVideoJob reconciling =
        new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "r", "d");
    reconciling.setStatus(MouseVideoJob.STATUS_NEEDS_RECONCILIATION);
    when(jobService.find(jobId)).thenReturn(Optional.of(reconciling));
    when(jobService.heartbeat(eq(jobId), anyLong(), anyString(), anyLong()))
        .thenThrow(new IllegalStateException("fenced by expiry poller"));

    try (MouseVideoLeaseHeartbeat.Handle handle =
        heartbeat.start(jobId, 7L, "worker-1", cancellation)) {
      await(() -> handle.leaseLost() && cancellation.isCancelled());
      assertThat(handle.leaseLost()).isTrue();
      assertThat(cancellation.isCancelled()).isTrue();
    }
  }

  @Test
  void ownTerminalSettlementIsNotMistakenForLeaseLoss() {
    UUID jobId = UUID.randomUUID();
    MouseVideoCancellation cancellation = new MouseVideoCancellation();
    MouseVideoJob succeeded =
        new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "r", "d");
    succeeded.setStatus(MouseVideoJob.STATUS_SUCCEEDED);
    when(jobService.find(jobId)).thenReturn(Optional.of(succeeded));
    when(jobService.heartbeat(eq(jobId), anyLong(), anyString(), anyLong()))
        .thenThrow(new IllegalStateException("finalization won the race"));

    try (MouseVideoLeaseHeartbeat.Handle handle =
        heartbeat.start(jobId, 1L, "worker-1", cancellation)) {
      waitForRenewals(jobService, jobId);
      assertThat(handle.leaseLost()).isFalse();
      assertThat(cancellation.isCancelled()).isFalse();
    }
  }

  @Test
  void replacementTerminalOwnershipStopsTheOldWorker() {
    UUID jobId = UUID.randomUUID();
    MouseVideoCancellation cancellation = new MouseVideoCancellation();
    MouseVideoJob succeeded =
        new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "r", "d");
    succeeded.setStatus(MouseVideoJob.STATUS_SUCCEEDED);
    when(jobService.find(jobId)).thenReturn(Optional.of(succeeded));
    when(jobService.heartbeat(eq(jobId), anyLong(), anyString(), anyLong()))
        .thenThrow(new IllegalStateException("generation changed"));

    try (MouseVideoLeaseHeartbeat.Handle handle =
        heartbeat.start(jobId, 7L, "worker-1", cancellation)) {
      await(() -> handle.leaseLost() && cancellation.isCancelled());
      assertThat(handle.leaseLost()).isTrue();
      assertThat(cancellation.isCancelled()).isTrue();
    }
  }

  @Test
  void userCancellationDoesNotBecomeLeaseLoss() {
    UUID jobId = UUID.randomUUID();
    MouseVideoCancellation cancellation = new MouseVideoCancellation();
    MouseVideoJob cancelling =
        new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "r", "d");
    cancelling.setStatus(MouseVideoJob.STATUS_CANCELLING);
    when(jobService.find(jobId)).thenReturn(Optional.of(cancelling));
    when(jobService.heartbeat(eq(jobId), anyLong(), anyString(), anyLong()))
        .thenThrow(new IllegalStateException("generation changed"));

    try (MouseVideoLeaseHeartbeat.Handle handle =
        heartbeat.start(jobId, 7L, "worker-1", cancellation)) {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (!cancellation.isCancelled() && System.nanoTime() < deadline) {
        try {
          Thread.sleep(20L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      assertThat(cancellation.isCancelled()).isTrue();
      assertThat(handle.leaseLost()).isFalse();
    }
  }

  private static void waitForRenewals(MouseVideoJobService service, UUID jobId) {
    verify(service, timeout(4_000L).atLeast(2))
        .heartbeat(eq(jobId), anyLong(), anyString(), anyLong());
  }

  private static void await(java.util.function.BooleanSupplier condition) {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(20L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}

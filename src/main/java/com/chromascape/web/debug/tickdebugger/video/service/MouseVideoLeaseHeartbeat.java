package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Independent lease renewal for one running video job. The heartbeat runs on its own bounded
 * scheduler so renewal never depends on tool output, a blocking read or the single worker executor.
 * If ownership cannot be re-established the token is cancelled immediately so the worker stops and
 * refuses publication; user-requested cancellation is not mistaken for lease loss.
 */
@Component
public class MouseVideoLeaseHeartbeat {

  private final MouseVideoJobService jobService;
  private final MouseVideoProperties properties;
  private final ScheduledExecutorService scheduler;

  public MouseVideoLeaseHeartbeat(
      MouseVideoJobService jobService, MouseVideoProperties properties) {
    this.jobService = jobService;
    this.properties = properties;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "mouse-video-lease-heartbeat");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** Renewal handle for one execution; closing stops future renewals at settlement. */
  public interface Handle extends AutoCloseable {
    boolean leaseLost();

    @Override
    void close();
  }

  /** Starts renewal for the exact job generation and worker identity. */
  public Handle start(
      UUID jobId, long generation, String leaseOwner, MouseVideoCancellation cancellation) {
    AtomicBoolean leaseLost = new AtomicBoolean();
    long intervalSeconds = Math.max(1L, properties.getLeaseHeartbeatSeconds());
    ScheduledFuture<?> future =
        scheduler.scheduleWithFixedDelay(
            () -> renew(jobId, generation, leaseOwner, cancellation, leaseLost),
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS);
    return new ScheduledHandle(future, leaseLost);
  }

  private void renew(
      UUID jobId,
      long generation,
      String leaseOwner,
      MouseVideoCancellation cancellation,
      AtomicBoolean leaseLost) {
    if (leaseLost.get() || cancellation.isCancelled()) {
      return;
    }
    try {
      jobService.heartbeat(jobId, generation, leaseOwner, properties.getLeaseExpirySeconds());
    } catch (RuntimeException failure) {
      try {
        handleRenewalFailure(jobId, generation, leaseOwner, cancellation, leaseLost);
      } catch (RuntimeException diagnosticFailure) {
        // Never let the scheduler die silently: an unconfirmable lease must stop the worker.
        stopAsLeaseLoss(cancellation, leaseLost);
      }
    }
  }

  private void handleRenewalFailure(
      UUID jobId,
      long generation,
      String leaseOwner,
      MouseVideoCancellation cancellation,
      AtomicBoolean leaseLost) {
    Optional<MouseVideoJob> current = jobService.find(jobId);
    if (current.isEmpty()) {
      stopAsLeaseLoss(cancellation, leaseLost);
      return;
    }
    MouseVideoJob job = current.get();
    String status = job.getStatus();

    // A user cancellation is honoured as cancellation, not as lease loss.
    if (job.isCancelling() || MouseVideoJob.STATUS_CANCELLED.equals(status)) {
      cancellation.requestCancel();
      return;
    }
    // Reconciliation/expiry means another actor fenced this execution. It must stop even though
    // the row is "settled"; never infer local cleanup from a foreign settlement.
    if (MouseVideoJob.STATUS_NEEDS_RECONCILIATION.equals(status)) {
      stopAsLeaseLoss(cancellation, leaseLost);
      return;
    }
    if (job.isTerminal()) {
      // Only this execution's own generation may treat a terminal row as successful settlement.
      if (job.getGeneration() != generation) {
        stopAsLeaseLoss(cancellation, leaseLost);
      }
      return;
    }
    // RUNNING (or an unexpected live state) must still belong to this exact execution.
    if (job.getGeneration() != generation || !leaseOwner.equals(job.getLeaseOwner())) {
      stopAsLeaseLoss(cancellation, leaseLost);
      return;
    }
    // Same ownership: a transient database/optimistic failure is retryable on the next tick, but
    // an expired database lease is genuine ownership loss.
    if (!jobService.leaseValid(jobId)) {
      stopAsLeaseLoss(cancellation, leaseLost);
    }
  }

  private static void stopAsLeaseLoss(
      MouseVideoCancellation cancellation, AtomicBoolean leaseLost) {
    leaseLost.set(true);
    cancellation.requestCancel();
  }

  @PreDestroy
  public void shutdown() {
    scheduler.shutdownNow();
  }

  private record ScheduledHandle(ScheduledFuture<?> future, AtomicBoolean lost) implements Handle {

    @Override
    public boolean leaseLost() {
      return lost.get();
    }

    @Override
    public void close() {
      future.cancel(false);
    }
  }
}

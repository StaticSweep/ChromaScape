package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.run.ReplayFence;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Independent periodic lease renewal for one claimed attempt.
 *
 * <p>Renewal runs on a shared scheduler sized to worker concurrency, so a kernel request, snapshot,
 * trace generation, comparison, or artifact publication that blocks longer than the lease duration
 * can no longer let recovery expire an attempt that is legitimately still running.
 *
 * <p>Poisoning rules:
 *
 * <ul>
 *   <li>A renewal that updates zero rows poisons the handle permanently — the database predicate is
 *       the authoritative stale-fence signal.
 *   <li>Database exceptions are retried only while monotonic elapsed time since the last successful
 *       claim/renewal ({@link System#nanoTime()}) stays below the lease duration. Once elapsed time
 *       reaches the lease duration the handle is poisoned conservatively and the triggering
 *       exception is preserved as the diagnostic cause.
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "replay.worker", name = "enabled", havingValue = "true")
public class ReplayLeaseHeartbeat implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(ReplayLeaseHeartbeat.class);

  private final ReplayLeaseRenewalService renewalService;
  private final ScheduledExecutorService scheduler;
  private final int leaseDurationSeconds;
  private final long intervalMillis;

  public ReplayLeaseHeartbeat(
      ReplayLeaseRenewalService renewalService, ReplayProperties properties) {
    this(
        renewalService,
        properties.getWorker().getConcurrency(),
        properties.getWorker().getLeaseDurationSeconds(),
        properties.getWorker().getHeartbeatSeconds() * 1000L);
  }

  /** Test/secondary constructor with explicit sizing and interval. */
  ReplayLeaseHeartbeat(
      ReplayLeaseRenewalService renewalService,
      int concurrency,
      int leaseDurationSeconds,
      long intervalMillis) {
    this.renewalService = renewalService;
    this.leaseDurationSeconds = leaseDurationSeconds;
    this.intervalMillis = Math.max(1, intervalMillis);
    this.scheduler =
        Executors.newScheduledThreadPool(
            Math.max(1, concurrency),
            r -> {
              Thread t = new Thread(r, "replay-lease-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * Starts the heartbeat immediately after a successful claim. The claim itself is the first
   * successful renewal: the monotonic window starts now.
   */
  public Handle start(ReplayFence fence) {
    Handle handle = new Handle(fence);
    handle.schedule();
    log.debug(
        "Heartbeat started for run {} attempt {} every {} ms",
        fence.runId(),
        fence.attemptId(),
        intervalMillis);
    return handle;
  }

  @PreDestroy
  @Override
  public void destroy() {
    scheduler.shutdownNow();
  }

  /** Per-attempt heartbeat handle; close after terminalization or confirmed fence loss only. */
  public final class Handle implements AutoCloseable {

    private final ReplayFence fence;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private volatile long lastSuccessfulRenewalNanos;
    private volatile RuntimeException failureCause;
    private volatile ScheduledFuture<?> future;

    private Handle(ReplayFence fence) {
      this.fence = fence;
      this.lastSuccessfulRenewalNanos = System.nanoTime();
    }

    void schedule() {
      future =
          scheduler.scheduleWithFixedDelay(
              this::renew, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void renew() {
      try {
        ReplayLeaseRenewalService.RenewalResult result = renewalService.renew(fence);
        if (result.renewed()) {
          lastSuccessfulRenewalNanos = System.nanoTime();
          return;
        }
        if (healthy.compareAndSet(true, false)) {
          // Authoritative stale-fence signal: keep the failure on the handle so the worker thread
          // observes it deterministically.
          failureCause =
              new ReplayException(
                  ReplayErrorCode.ATTEMPT_STALE_FENCE,
                  org.springframework.http.HttpStatus.CONFLICT,
                  false,
                  "Lease renewal matched zero rows",
                  "The lease expired or was recovered while executing",
                  "attemptNo=" + fence.attemptNo(),
                  null);
          log.warn(
              "Run {} attempt {} lost its lease (renewal matched zero rows)",
              fence.runId(),
              fence.attemptId());
        }
      } catch (RuntimeException e) {
        long elapsedNanos = System.nanoTime() - lastSuccessfulRenewalNanos;
        if (elapsedNanos >= TimeUnit.SECONDS.toNanos(leaseDurationSeconds)) {
          if (healthy.compareAndSet(true, false)) {
            failureCause = e;
            log.warn(
                "Run {} attempt {} heartbeat poisoned after repeated database failures",
                fence.runId(),
                fence.attemptId(),
                e);
          }
        } else {
          log.debug(
              "Heartbeat renewal error for attempt {}: {}", fence.attemptId(), e.getMessage());
        }
      }
    }

    /**
     * Fast liveness check. Throws ATTEMPT_STALE_FENCE once the fence was lost or database failures
     * consumed the whole lease window; the preserved exception rides along as the cause.
     */
    public void assertHealthy() {
      if (!healthy.get()) {
        throw new ReplayException(
            ReplayErrorCode.ATTEMPT_STALE_FENCE,
            org.springframework.http.HttpStatus.CONFLICT,
            false,
            "This attempt no longer holds the lease",
            "The lease expired or was recovered while executing",
            "attemptNo=" + fence.attemptNo(),
            failureCause);
      }
    }

    public boolean isHealthy() {
      return healthy.get();
    }

    public ReplayFence fence() {
      return fence;
    }

    /** Stops periodic renewal; semantic alias for {@link #close()}. */
    public void stop() {
      close();
    }

    /** Stops periodic renewal. Idempotent; safe to call from finally. */
    @Override
    public void close() {
      ScheduledFuture<?> f = future;
      if (f != null) {
        f.cancel(false);
      }
    }
  }
}

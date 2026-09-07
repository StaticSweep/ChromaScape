package com.chromascape.web.gameplay.orchestration.v2;

import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StatusResult;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Marks mid-flight v2 gate rows as {@code NEEDS_RECONCILIATION} once after application readiness
 * (and Flyway/JPA initialization), then retries remote reconciliation for those rows on a bounded
 * schedule.
 *
 * <p>Recovery never redispatches reserve or start; it only makes in-flight work uncertain so an
 * operator can reconcile it, and terminal rows are never walked backward.
 *
 * <p>Retries are bounded on purpose. A request that cannot be reconciled after {@link
 * #MAX_RECONCILIATION_ATTEMPTS} attempts is abandoned by the background loop and left in {@code
 * NEEDS_RECONCILIATION}, which is the operator-visible state: an unbounded retry loop would hammer
 * Micro6 forever and hide a genuinely stuck request behind an endless stream of identical failures.
 * Backoff grows between ticks so a Micro6 outage is not amplified. When the live feature is
 * disabled there is nothing legitimate to reconcile remotely, so the loop never starts.
 */
@Component
public class GameplayV2RecoveryCoordinator implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(GameplayV2RecoveryCoordinator.class);

  /** Attempts per request before the background loop abandons it to the operator. */
  static final int MAX_RECONCILIATION_ATTEMPTS = 3;

  /** First retry delay; each tick multiplies it up to {@link #MAX_BACKOFF_SECONDS}. */
  static final long BASE_BACKOFF_SECONDS = 30L;

  /** Upper bound on the retry delay, so a long outage settles into a slow poll. */
  static final long MAX_BACKOFF_SECONDS = 300L;

  private final GameplayV2LiveGateService liveGateService;
  private final boolean liveEnabled;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
  private volatile long backoffSeconds = BASE_BACKOFF_SECONDS;

  public GameplayV2RecoveryCoordinator(
      GameplayV2LiveGateService liveGateService,
      @Value("${gameplay.v2.live-enabled:false}") boolean liveEnabled) {
    this.liveGateService = liveGateService;
    this.liveEnabled = liveEnabled;
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "gameplay-v2-recovery");
              thread.setDaemon(true);
              return thread;
            });
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    try {
      List<V2StatusResult> recovered = liveGateService.recoverAfterRestart();
      if (!recovered.isEmpty()) {
        log.info(
            "[ChromaScape] v2 gate recovery: {} in-flight rows marked NEEDS_RECONCILIATION",
            recovered.size());
      }
    } catch (RuntimeException exception) {
      log.error("[ChromaScape] v2 gate recovery failed: {}", exception.getMessage(), exception);
    }
    if (!liveEnabled) {
      // Reconciliation talks to Micro6. With live delivery disabled there is no remote work to
      // reconcile against, so scheduling the loop would only produce noise.
      log.info("[ChromaScape] v2 reconciliation loop not started: live gameplay is disabled");
      return;
    }
    schedule(BASE_BACKOFF_SECONDS);
  }

  private void schedule(long delaySeconds) {
    if (stopped.get()) {
      return;
    }
    try {
      executor.schedule(this::tick, delaySeconds, TimeUnit.SECONDS);
    } catch (RuntimeException rejected) {
      // The executor is shutting down; nothing to reschedule.
      log.debug("[ChromaScape] v2 reconciliation not rescheduled: {}", rejected.getMessage());
    }
  }

  /**
   * One reconciliation pass. Each candidate is attempted at most {@link
   * #MAX_RECONCILIATION_ATTEMPTS} times; a request that reaches its limit is dropped from the loop
   * and stays visible to the operator in {@code NEEDS_RECONCILIATION}.
   */
  void tick() {
    if (stopped.get()) {
      return;
    }
    boolean progressed = false;
    try {
      for (V2StatusResult row : liveGateService.statusReconciliationCandidates()) {
        String requestId = row.requestId();
        if (requestId == null) {
          continue;
        }
        int attempt = attempts.merge(requestId, 1, Integer::sum);
        if (attempt > MAX_RECONCILIATION_ATTEMPTS) {
          continue;
        }
        try {
          V2StatusResult reconciled = liveGateService.reconcile(requestId);
          if (reconciled.state() != null && reconciled.state().provenTerminal()) {
            attempts.remove(requestId);
            progressed = true;
          } else if (attempt == MAX_RECONCILIATION_ATTEMPTS) {
            log.warn(
                "[ChromaScape] v2 reconciliation abandoned after {} attempts; {} stays "
                    + "NEEDS_RECONCILIATION for the operator",
                MAX_RECONCILIATION_ATTEMPTS,
                requestId);
          }
        } catch (RuntimeException exception) {
          log.warn(
              "[ChromaScape] v2 reconciliation attempt {}/{} failed for {}: {}",
              attempt,
              MAX_RECONCILIATION_ATTEMPTS,
              requestId,
              exception.getMessage());
        }
      }
    } catch (RuntimeException exception) {
      log.warn("[ChromaScape] v2 reconciliation tick failed: {}", exception.getMessage());
    }
    backoffSeconds =
        progressed ? BASE_BACKOFF_SECONDS : Math.min(MAX_BACKOFF_SECONDS, backoffSeconds * 2);
    schedule(backoffSeconds);
  }

  /** Attempts recorded so far for a request. Zero once the request reconciled terminally. */
  int attemptsFor(String requestId) {
    return attempts.getOrDefault(requestId, 0);
  }

  /** Stops the reconciliation loop and releases its thread. Idempotent. */
  @Override
  @PreDestroy
  public void close() {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
        log.warn("[ChromaScape] v2 recovery executor did not terminate within 5s");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}

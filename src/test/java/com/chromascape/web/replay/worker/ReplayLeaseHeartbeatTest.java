package com.chromascape.web.replay.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.run.ReplayFence;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Independent periodic lease renewal through the transactional {@link ReplayLeaseRenewalService}: a
 * slow worker thread must never let the lease lapse, an authoritative zero-row renewal must poison
 * the handle, and repeated database failures must poison it once the monotonic elapsed time since
 * the last successful claim/renewal reaches the lease duration — with the triggering exception
 * preserved as the diagnostic cause.
 */
@ExtendWith(MockitoExtension.class)
class ReplayLeaseHeartbeatTest {

  @Mock private ReplayLeaseRenewalService renewalService;

  private ReplayLeaseHeartbeat heartbeat;

  private final UUID runId = UUID.randomUUID();
  private final UUID attemptId = UUID.randomUUID();
  private final ReplayFence fence = new ReplayFence(runId, attemptId, 1, 7, "worker-a");

  @BeforeEach
  void setUp() {
    // 40 ms interval keeps the test fast while exercising the scheduler.
    heartbeat = new ReplayLeaseHeartbeat(renewalService, 2, 30, 40);
    lenient()
        .when(renewalService.renew(fence))
        .thenReturn(
            new ReplayLeaseRenewalService.RenewalResult(
                true, Instant.now(), Instant.now().plusSeconds(30)));
  }

  @AfterEach
  void tearDown() {
    heartbeat.destroy();
  }

  @Test
  void workerEnabledContextCreatesHeartbeatWithConfiguredDependencies() {
    new ApplicationContextRunner()
        .withPropertyValues("replay.worker.enabled=true")
        .withBean(ReplayProperties.class)
        .withBean(ReplayLeaseRenewalService.class, () -> renewalService)
        .withUserConfiguration(ReplayLeaseHeartbeat.class)
        .run(
            context -> {
              assertNotNull(context.getBean(ReplayLeaseHeartbeat.class));
            });
  }

  @Test
  void renewsPeriodicallyWhileWorkerThreadIsBlocked() {
    try (ReplayLeaseHeartbeat.Handle handle = heartbeat.start(fence)) {
      // Simulate a blocking kernel request far longer than the interval.
      sleep(200);
      verify(renewalService, atLeast(3)).renew(fence);
      assertTrue(handle.isHealthy());
      assertDoesNotThrow(handle::assertHealthy);
    }
  }

  @Test
  void zeroRowRenewalPoisonsHandleAndAssertHealthyThrows() {
    when(renewalService.renew(fence)).thenReturn(ReplayLeaseRenewalService.RenewalResult.lost());

    try (ReplayLeaseHeartbeat.Handle handle = heartbeat.start(fence)) {
      Awaitility.await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(
              () -> assertFalse(handle.isHealthy(), "zero-row renewal must mark the fence lost"));

      ReplayException e = assertThrows(ReplayException.class, handle::assertHealthy);
      assertEquals(ReplayErrorCode.ATTEMPT_STALE_FENCE, e.getErrorCode());
    }
  }

  @Test
  void stopHaltsFurtherRenewals() {
    AtomicInteger renewals = new AtomicInteger();
    when(renewalService.renew(any()))
        .thenAnswer(
            inv -> {
              renewals.incrementAndGet();
              return new ReplayLeaseRenewalService.RenewalResult(
                  true, Instant.now(), Instant.now().plusSeconds(30));
            });

    ReplayLeaseHeartbeat.Handle handle = heartbeat.start(fenceWithRandomIds());
    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> renewals.get() >= 2);
    handle.stop();

    int afterStop = renewals.get();
    sleep(150);
    assertTrue(
        renewals.get() <= afterStop + 1,
        "renewals must stop shortly after stop(): before="
            + afterStop
            + ", after="
            + renewals.get());
  }

  @Test
  void transientRenewalErrorDoesNotPoisonHandleWithinLeaseWindow() {
    AtomicInteger failures = new AtomicInteger();
    when(renewalService.renew(fence))
        .thenAnswer(
            inv -> {
              if (failures.incrementAndGet() == 1) {
                throw new IllegalStateException("transient connection loss");
              }
              return new ReplayLeaseRenewalService.RenewalResult(
                  true, Instant.now(), Instant.now().plusSeconds(30));
            });

    try (ReplayLeaseHeartbeat.Handle handle = heartbeat.start(fence)) {
      Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> failures.get() >= 3);
      assertTrue(handle.isHealthy());
      assertDoesNotThrow(handle::assertHealthy);
    }
  }

  /**
   * Persistent database failures must eventually poison the handle: once monotonic elapsed time
   * since the last successful claim/renewal reaches the lease duration, the handle is marked stale
   * conservatively and the LAST exception rides along as the failure cause.
   */
  @Test
  void persistentDatabaseFailuresPoisonHandleOnceLeaseWindowElapses() {
    ReplayLeaseHeartbeat shortLease =
        new ReplayLeaseHeartbeat(renewalService, 2, 1, 40); // 1 s lease, 40 ms ticks
    IllegalStateException dbDown = new IllegalStateException("database unreachable");
    when(renewalService.renew(fence)).thenThrow(dbDown);

    try (ReplayLeaseHeartbeat.Handle handle = shortLease.start(fence)) {
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> assertFalse(handle.isHealthy(), "elapsed lease window must poison the handle"));

      ReplayException e = assertThrows(ReplayException.class, handle::assertHealthy);
      assertEquals(ReplayErrorCode.ATTEMPT_STALE_FENCE, e.getErrorCode());
      assertNotNull(e.getCause(), "the preserved database exception must be the cause");
      assertEquals(dbDown, e.getCause());
    } finally {
      shortLease.destroy();
    }
  }

  private ReplayFence fenceWithRandomIds() {
    return new ReplayFence(UUID.randomUUID(), UUID.randomUUID(), 1, 9, "w-stop");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

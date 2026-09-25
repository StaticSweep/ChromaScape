package com.chromascape.web.replay.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Lifecycle coverage for the worker-owned scheduler executor. */
class ReplayWorkerLifecycleTest {

  @Test
  void closeAndPreDestroyAreIdempotentAndLeaveNoExecutorThreads() throws Exception {
    ReplayWorker worker = workerWithEmptyPoll();

    worker.close();
    worker.close();

    assertTrue(worker.isShutdownClean());
    assertNotNull(
        ReplayWorker.class.getMethod("close").getAnnotation(PreDestroy.class),
        "Spring must invoke the same idempotent close operation at context shutdown");
  }

  @Test
  void closeWaitsForAnInFlightPollBeforeShuttingDown() throws Exception {
    ReplayRunRepository runs = mock(ReplayRunRepository.class);
    CountDownLatch pollEntered = new CountDownLatch(1);
    CountDownLatch releasePoll = new CountDownLatch(1);
    when(runs.findClaimableRunIds(anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              pollEntered.countDown();
              assertTrue(releasePoll.await(5, TimeUnit.SECONDS));
              return List.of();
            });

    ReplayProperties properties = new ReplayProperties();
    properties.setEnabled(true);
    properties.getWorker().setEnabled(true);
    properties.getXrsps().setShutdownTimeoutMs(2000);
    ReplayWorker worker = worker(runs, properties);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread polling =
        new Thread(
            () -> {
              try {
                worker.poll();
              } catch (Throwable error) {
                failure.set(error);
              }
            },
            "replay-worker-lifecycle-poll");
    Thread closing = new Thread(worker::close, "replay-worker-lifecycle-close");

    try {
      polling.start();
      assertTrue(pollEntered.await(5, TimeUnit.SECONDS));
      closing.start();
      closing.join(100);
      assertTrue(closing.isAlive(), "close must wait for a poll holding the lifecycle monitor");
    } finally {
      releasePoll.countDown();
      polling.join(5000);
      closing.join(5000);
      if (closing.isAlive()) {
        worker.close();
      }
    }

    assertFalse(polling.isAlive());
    assertFalse(closing.isAlive());
    assertTrue(failure.get() == null, () -> "poll failed: " + failure.get());
    assertTrue(worker.isShutdownClean());
  }

  @Test
  void closeWaitsForAnInFlightLeaseRecoveryBeforeShuttingDown() throws Exception {
    ReplayRunRepository runs = mock(ReplayRunRepository.class);
    ReplayAttemptRepository attempts = mock(ReplayAttemptRepository.class);
    ReplayExpiredAttemptRecoveryService recovery = mock(ReplayExpiredAttemptRecoveryService.class);
    CountDownLatch recoveryEntered = new CountDownLatch(1);
    CountDownLatch releaseRecovery = new CountDownLatch(1);
    ReplayAttempt expired = mock(ReplayAttempt.class);
    UUID attemptId = UUID.randomUUID();
    when(expired.getId()).thenReturn(attemptId);
    when(attempts.findExpiredActiveAttempts(anyInt())).thenReturn(List.of(expired));
    when(recovery.recover(attemptId))
        .thenAnswer(
            invocation -> {
              recoveryEntered.countDown();
              assertTrue(releaseRecovery.await(5, TimeUnit.SECONDS));
              return Optional.empty();
            });

    ReplayProperties properties = new ReplayProperties();
    properties.setEnabled(true);
    properties.getWorker().setEnabled(true);
    properties.getXrsps().setShutdownTimeoutMs(2000);
    ReplayWorker worker = worker(runs, attempts, recovery, properties);
    Thread recovering =
        new Thread(worker::recoverExpiredLeases, "replay-worker-lifecycle-recovery");
    Thread closing = new Thread(worker::close, "replay-worker-lifecycle-close-recovery");

    try {
      recovering.start();
      assertTrue(recoveryEntered.await(5, TimeUnit.SECONDS));
      closing.start();
      closing.join(100);
      assertTrue(
          closing.isAlive(), "close must wait for a recovery pass holding the lifecycle monitor");
    } finally {
      releaseRecovery.countDown();
      recovering.join(5000);
      closing.join(5000);
      if (closing.isAlive()) {
        worker.close();
      }
    }

    assertFalse(recovering.isAlive());
    assertFalse(closing.isAlive());
    assertTrue(worker.isShutdownClean());
  }

  private static ReplayWorker workerWithEmptyPoll() {
    ReplayRunRepository runs = mock(ReplayRunRepository.class);
    when(runs.findClaimableRunIds(anyString(), anyInt())).thenReturn(List.of());
    ReplayProperties properties = new ReplayProperties();
    properties.setEnabled(true);
    properties.getWorker().setEnabled(true);
    return worker(runs, properties);
  }

  private static ReplayWorker worker(ReplayRunRepository runs, ReplayProperties properties) {
    return worker(
        runs,
        mock(ReplayAttemptRepository.class),
        mock(ReplayExpiredAttemptRecoveryService.class),
        properties);
  }

  private static ReplayWorker worker(
      ReplayRunRepository runs,
      ReplayAttemptRepository attempts,
      ReplayExpiredAttemptRecoveryService recovery,
      ReplayProperties properties) {
    return new ReplayWorker(
        runs,
        attempts,
        mock(ReplayRunService.class),
        mock(ScenarioManifestRepository.class),
        mock(ReplayCheckpointRepository.class),
        mock(ArtifactRefRepository.class),
        mock(ReplayMismatchRepository.class),
        mock(ReplayWorkerPersistenceService.class),
        mock(ReplayLeaseHeartbeat.class),
        recovery,
        mock(ReplayArtifactService.class),
        mock(MismatchEngine.class),
        mock(ReplayReportService.class),
        new CanonicalProjectionService(),
        properties,
        new ObjectMapper());
  }
}

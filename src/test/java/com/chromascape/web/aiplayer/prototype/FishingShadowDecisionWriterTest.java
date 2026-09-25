package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Tests the nonblocking shadow decision writer: ordering, lifecycle, shutdown, and metrics. */
class FishingShadowDecisionWriterTest {

  @TempDir Path tempDir;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
  }

  private FishingShadowDecisionWriter newWriter() {
    FishingShadowDecisionWriter writer = new FishingShadowDecisionWriter(objectMapper);
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    return writer;
  }

  private FishingShadowDecision decision(String sessionId, long tick) {
    return decision(sessionId, "run_1", tick);
  }

  private FishingShadowDecision decision(String sessionId, String runId, long tick) {
    return new FishingShadowDecision(
        2,
        "AI_PLAYER_SHADOW_DECISION",
        "PREDICTED",
        "fishing_ab12cd34",
        sessionId,
        runId,
        tick,
        "2026-08-11T05:30:00Z",
        SemanticAction.interactNpc("net", "fishing spot"),
        null,
        null,
        4.0,
        8201L,
        450);
  }

  private Path shadowFile(String sessionId, String runId) {
    return tempDir
        .resolve("fishing")
        .resolve("shadow")
        .resolve(sessionId)
        .resolve(runId + ".jsonl");
  }

  private Path shadowFile(String sessionId) {
    return shadowFile(sessionId, "run_1");
  }

  private void awaitLifecycle(FishingShadowDecisionWriter writer, String expected)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (expected.equals(writer.statusSnapshot().lifecycle())) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "Timed out waiting for lifecycle "
            + expected
            + "; was "
            + writer.statusSnapshot().lifecycle());
  }

  private ObjectMapper uninterruptibleBlockingMapper(
      CountDownLatch writeStarted, CountDownLatch releaseWrite) {
    return new ObjectMapper() {
      @Override
      public String writeValueAsString(Object value) throws JsonProcessingException {
        writeStarted.countDown();
        boolean released = false;
        while (!released) {
          try {
            releaseWrite.await();
            released = true;
          } catch (InterruptedException e) {
            Thread.interrupted();
          }
        }
        return super.writeValueAsString(value);
      }
    };
  }

  @Test
  @DisplayName("the persistence queue preserves decision order")
  void persistencePreservesOrder() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    for (long tick = 1; tick <= 3; tick++) {
      assertThat(writer.enqueue(decision("debug_1", tick))).isTrue();
    }
    writer.flush();

    List<String> lines = Files.readAllLines(shadowFile("debug_1"));
    assertThat(lines).hasSize(3);
    for (int i = 0; i < lines.size(); i++) {
      FishingShadowDecision parsed =
          objectMapper.readValue(lines.get(i), FishingShadowDecision.class);
      assertThat(parsed.tick()).isEqualTo(i + 1);
      assertThat(parsed.sessionId()).isEqualTo("debug_1");
    }
    writer.stop();
  }

  @Test
  @DisplayName("a persistence failure does not block or stop inference")
  void persistenceFailureDoesNotBlock() throws Exception {
    Path blocker = tempDir.resolve("fishing");
    Files.writeString(blocker, "blocker");

    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    writer.flush();
    assertThat(writer.getError()).isNotNull();

    // Enqueue continues to accept new decisions without blocking.
    assertThat(writer.enqueue(decision("debug_1", 2))).isTrue();
    assertThat(writer.enqueue(decision("debug_1", 3))).isTrue();
    writer.stop();
  }

  @Test
  @DisplayName("queue saturation produces a visible queue-full error and drops only")
  void queueSaturationProducesVisibleError() throws Exception {
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    FishingShadowDecisionWriter writer =
        new FishingShadowDecisionWriter(uninterruptibleBlockingMapper(writeStarted, releaseWrite));
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    writer.start();
    // Block the writer on its first decision so the replaced bounded queue is not being drained.
    assertThat(writer.enqueue(decision("debug_1", 0))).isTrue();
    assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

    ArrayBlockingQueue<FishingShadowDecision> fullQueue = new ArrayBlockingQueue<>(2048);
    for (int i = 0; i < 2048; i++) {
      fullQueue.offer(decision("debug_full", i));
    }
    ReflectionTestUtils.setField(writer, "queue", fullQueue);
    long enqueuedBefore = writer.getEnqueuedCount();

    assertThat(writer.enqueue(decision("debug_full", 2048))).isFalse();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_QUEUE_FULL");
    assertThat(writer.getDroppedCount()).isEqualTo(1);
    assertThat(writer.getEnqueuedCount()).isEqualTo(enqueuedBefore);

    releaseWrite.countDown();
    ReflectionTestUtils.setField(writer, "queue", new ArrayBlockingQueue<>(2048));
    writer.stop();
  }

  @Test
  @DisplayName("the writer rejects enqueue before start with SHADOW_PERSISTENCE_STOPPED")
  void enqueueRejectedBeforeStart() {
    FishingShadowDecisionWriter writer = newWriter();
    assertThat(writer.enqueue(decision("debug_1", 1))).isFalse();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_STOPPED");
    assertThat(writer.queueSize()).isZero();
  }

  @Test
  @DisplayName("the writer rejects enqueue after stop with SHADOW_PERSISTENCE_STOPPED")
  void enqueueRejectedAfterStop() {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    writer.stop();
    assertThat(writer.enqueue(decision("debug_1", 1))).isFalse();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_STOPPED");
    // Decisions enqueued while running are still written, but nothing new is accepted.
    assertThat(writer.getDroppedCount()).isZero();
  }

  @Test
  @DisplayName("the writer rejects enqueue once draining has begun")
  void enqueueRejectedWhileDraining() throws Exception {
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    FishingShadowDecisionWriter writer =
        new FishingShadowDecisionWriter(uninterruptibleBlockingMapper(writeStarted, releaseWrite));
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    ReflectionTestUtils.setField(writer, "drainTimeoutMillis", 60_000L);
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

    Thread stopper = new Thread(writer::stop, "stopper");
    stopper.start();
    awaitLifecycle(writer, "DRAINING");

    assertThat(writer.enqueue(decision("debug_1", 2))).isFalse();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_STOPPED");

    releaseWrite.countDown();
    stopper.join(5000);
    assertThat(writer.statusSnapshot().lifecycle()).isEqualTo("STOPPED");
  }

  @Test
  @DisplayName("a drain that exceeds the timeout interrupts the writer and reports DRAIN_TIMEOUT")
  void drainTimeoutInterruptsWriter() throws Exception {
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    ObjectMapper slowMapper =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) throws JsonProcessingException {
            writeStarted.countDown();
            try {
              releaseWrite.await();
            } catch (InterruptedException e) {
              // The drain-timeout interrupt fires while we are blocked. Stay blocked until the test
              // releases us so the writer cannot clear the DRAIN_TIMEOUT error before the
              // assertion.
              try {
                releaseWrite.await();
              } catch (InterruptedException e2) {
                Thread.currentThread().interrupt();
              }
            }
            return super.writeValueAsString(value);
          }
        };

    FishingShadowDecisionWriter writer = new FishingShadowDecisionWriter(slowMapper);
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    ReflectionTestUtils.setField(writer, "drainTimeoutMillis", 300L);
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

    writer.stop();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_DRAIN_TIMEOUT");

    releaseWrite.countDown();
    Thread writerThread = (Thread) ReflectionTestUtils.getField(writer, "writerThread");
    writerThread.join(5000);
    assertThat(writerThread.isAlive()).isFalse();
    assertThat(writer.statusSnapshot().lifecycle()).isEqualTo("STOPPED");
    // stop() is idempotent and safe after the writer exited.
    writer.stop();
  }

  @Test
  @DisplayName("a blocked writer remains DRAINING after both joins and STOPPED only on exit")
  void blockedWriterRemainsDrainingThenStopsOnExit() throws Exception {
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    FishingShadowDecisionWriter writer =
        new FishingShadowDecisionWriter(uninterruptibleBlockingMapper(writeStarted, releaseWrite));
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    ReflectionTestUtils.setField(writer, "drainTimeoutMillis", 300L);
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

    writer.stop();
    Thread writerThread = (Thread) ReflectionTestUtils.getField(writer, "writerThread");
    // The uninterruptible writer is still alive after both joins: status must never report STOPPED
    // while the thread is alive.
    assertThat(writerThread.isAlive()).isTrue();
    assertThat(writer.statusSnapshot().lifecycle()).isEqualTo("DRAINING");
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_DRAIN_TIMEOUT");

    releaseWrite.countDown();
    writerThread.join(5000);
    assertThat(writerThread.isAlive()).isFalse();
    assertThat(writer.statusSnapshot().lifecycle()).isEqualTo("STOPPED");
  }

  @Test
  @DisplayName("the writer exposes queue size, written count, and dropped count")
  void countersExposeQueueWrittenAndDropped() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writer.enqueue(decision("debug_1", 2))).isTrue();
    writer.flush();

    assertThat(writer.getWrittenCount()).isEqualTo(2);
    assertThat(writer.queueSize()).isZero();
    assertThat(writer.getDroppedCount()).isZero();

    ArrayBlockingQueue<FishingShadowDecision> fullQueue = new ArrayBlockingQueue<>(2048);
    for (int i = 0; i < 2048; i++) {
      fullQueue.offer(decision("debug_full", i));
    }
    ReflectionTestUtils.setField(writer, "queue", fullQueue);
    assertThat(writer.enqueue(decision("debug_full", 2048))).isFalse();
    assertThat(writer.getDroppedCount()).isEqualTo(1);
    ReflectionTestUtils.setField(writer, "queue", new ArrayBlockingQueue<>(2048));
    writer.stop();
  }

  @Test
  @DisplayName(
      "the writer refuses unsafe paths at the persistence boundary and counts them as failed")
  void writerRefusesUnsafePaths() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    assertThat(writer.enqueue(decision("../evil", 1))).isTrue();
    writer.flush();

    assertThat(Files.exists(shadowFile("../evil"))).isFalse();
    assertThat(Files.exists(tempDir.resolve("fishing").resolve("evil.jsonl"))).isFalse();
    assertThat(Files.exists(tempDir.resolve("evil.jsonl"))).isFalse();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_INVALID_PATH");
    // An unsafe session id increments completed and failed, never written.
    assertThat(writer.getFailedCount()).isEqualTo(1);
    assertThat(writer.getWrittenCount()).isZero();
    assertThat(writer.getCompletedCount()).isEqualTo(1);
    writer.stop();
  }

  @Test
  @DisplayName("an unsafe shadow run id is counted as failed, never written")
  void unsafeRunIdCountsAsFailed() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", "../evil", 1))).isTrue();
    writer.flush();

    assertThat(Files.exists(shadowFile("debug_1", "../evil"))).isFalse();
    assertThat(
            Files.exists(
                tempDir
                    .resolve("fishing")
                    .resolve("shadow")
                    .resolve("debug_1")
                    .resolve("evil.jsonl")))
        .isFalse();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_INVALID_PATH");
    assertThat(writer.getFailedCount()).isEqualTo(1);
    assertThat(writer.getWrittenCount()).isZero();
    assertThat(writer.getCompletedCount()).isEqualTo(1);
    writer.stop();
  }

  @Test
  @DisplayName(
      "accepted queued items become abandoned on a forced exit, and DRAIN_TIMEOUT survives")
  void forcedExitAbandonsQueuedItems() throws Exception {
    // A queue whose poll blocks until released, so the writer can be parked in poll while items
    // remain queued. On interrupt it propagates so the writer exits as a forced exit.
    CountDownLatch releasePoll = new CountDownLatch(1);
    BlockingQueue<FishingShadowDecision> blockingPollQueue =
        new ArrayBlockingQueue<>(2048) {
          @Override
          public FishingShadowDecision poll(long timeout, TimeUnit unit)
              throws InterruptedException {
            boolean released = false;
            while (!released) {
              try {
                releasePoll.await();
                released = true;
              } catch (InterruptedException e) {
                throw e;
              }
            }
            return super.poll(timeout, unit);
          }
        };

    FishingShadowDecisionWriter writer = newWriter();
    ReflectionTestUtils.setField(writer, "drainTimeoutMillis", 200L);
    writer.start();
    // Park the writer: the next poll uses the blocking queue.
    ReflectionTestUtils.setField(writer, "queue", blockingPollQueue);
    for (long tick = 1; tick <= 5; tick++) {
      assertThat(writer.enqueue(decision("debug_1", tick))).isTrue();
    }

    // stop() records DRAIN_TIMEOUT immediately when the initial join expires, interrupts the
    // parked writer, and the interrupt ends the thread (forced exit).
    writer.stop();

    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_DRAIN_TIMEOUT");
    FishingShadowDecisionWriter.WriterStatusSnapshot snap = writer.statusSnapshot();
    assertThat(snap.lifecycle()).isEqualTo("STOPPED");
    // Every accepted decision is accounted for: all queued items were abandoned (failed).
    assertThat(snap.completed()).isEqualTo(5);
    assertThat(snap.written()).isZero();
    assertThat(snap.failed()).isEqualTo(5);
    assertThat(snap.abandoned()).isEqualTo(5);
    assertThat(snap.queued()).isZero();
    assertThat(snap.inFlight()).isZero();
    assertThat(snap.enqueued()).isEqualTo(snap.queued() + snap.inFlight() + snap.completed());
    assertThat(snap.completed()).isEqualTo(snap.written() + snap.failed());
    assertThat(snap.abandoned()).isLessThanOrEqualTo(snap.failed());
    writer.stop();
  }

  @Test
  @DisplayName("a runtime serialization failure is counted and the loop continues")
  void runtimeSerializationFailureContinuesLoop() throws Exception {
    ObjectMapper throwingMapper =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) throws JsonProcessingException {
            FishingShadowDecision decision = (FishingShadowDecision) value;
            if (decision.tick() == 1) {
              throw new IllegalStateException("serialization boom");
            }
            return super.writeValueAsString(value);
          }
        };
    FishingShadowDecisionWriter writer = new FishingShadowDecisionWriter(throwingMapper);
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writer.enqueue(decision("debug_1", 2))).isTrue();
    writer.flush();

    FishingShadowDecisionWriter.WriterStatusSnapshot snap = writer.statusSnapshot();
    assertThat(snap.failed()).isEqualTo(1);
    assertThat(snap.written()).isEqualTo(1);
    assertThat(snap.completed()).isEqualTo(2);
    // The recoverable write-failure error is cleared by the later successful write.
    assertThat(snap.error()).isNull();
    assertThat(Files.readAllLines(shadowFile("debug_1"))).hasSize(1);
    writer.stop();
  }

  @Test
  @DisplayName("unexpected writer-loop termination produces WRITER_FAILED and abandons the queue")
  void unexpectedTerminationProducesWriterFailed() throws Exception {
    // The writer is parked on the first serialization until all three items are enqueued, then an
    // Error escapes the per-item containment to terminate the loop unexpectedly.
    CountDownLatch blockFirst = new CountDownLatch(1);
    ObjectMapper errorMapper =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) throws JsonProcessingException {
            FishingShadowDecision decision = (FishingShadowDecision) value;
            if (decision.tick() == 1) {
              try {
                blockFirst.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              throw new AssertionError("fatal serialization error");
            }
            return super.writeValueAsString(value);
          }
        };
    FishingShadowDecisionWriter writer = new FishingShadowDecisionWriter(errorMapper);
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writer.enqueue(decision("debug_1", 2))).isTrue();
    assertThat(writer.enqueue(decision("debug_1", 3))).isTrue();
    // Release the parked writer so the fatal error terminates the loop while items remain queued.
    blockFirst.countDown();
    writer.flush();

    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_WRITER_FAILED");
    FishingShadowDecisionWriter.WriterStatusSnapshot snap = writer.statusSnapshot();
    assertThat(snap.lifecycle()).isEqualTo("STOPPED");
    // The in-flight item and both still-queued items were completed as failed/abandoned.
    assertThat(snap.completed()).isEqualTo(3);
    assertThat(snap.written()).isZero();
    assertThat(snap.failed()).isEqualTo(3);
    assertThat(snap.abandoned()).isEqualTo(3);
    assertThat(snap.queued()).isZero();
    assertThat(snap.inFlight()).isZero();
    assertThat(snap.enqueued()).isEqualTo(snap.queued() + snap.inFlight() + snap.completed());
    writer.stop();
  }

  @Test
  @DisplayName("the writer drains during normal shutdown")
  void writerDrainsDuringShutdown() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    for (long tick = 1; tick <= 5; tick++) {
      assertThat(writer.enqueue(decision("debug_1", tick))).isTrue();
    }

    writer.stop();

    List<String> lines = Files.readAllLines(shadowFile("debug_1"));
    assertThat(lines).hasSize(5);
    for (int i = 0; i < lines.size(); i++) {
      FishingShadowDecision parsed =
          objectMapper.readValue(lines.get(i), FishingShadowDecision.class);
      assertThat(parsed.tick()).isEqualTo(i + 1);
    }
    assertThat(writer.statusSnapshot().lifecycle()).isEqualTo("STOPPED");
  }

  @Test
  @DisplayName("null decisions are rejected without side effects")
  void nullDecisionRejected() {
    FishingShadowDecisionWriter writer = newWriter();
    assertThat(writer.enqueue(null)).isFalse();
    assertThat(writer.getError()).isNull();
  }

  @Test
  @DisplayName("a successful write clears a recoverable write failure")
  void errorClearedAfterSuccessfulWrite() throws Exception {
    Path blocker = tempDir.resolve("fishing");
    Files.writeString(blocker, "blocker");

    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    writer.enqueue(decision("debug_1", 1));
    writer.flush();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_WRITE_FAILED");

    Files.delete(blocker);
    writer.enqueue(decision("debug_1", 2));
    writer.flush();
    assertThat(writer.getError()).isNull();
    writer.stop();
  }

  @Test
  @DisplayName("a successful append increments written and completed")
  void successfulAppendIncrementsWrittenAndCompleted() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writer.enqueue(decision("debug_1", 2))).isTrue();
    writer.flush();

    FishingShadowDecisionWriter.WriterStatusSnapshot snap = writer.statusSnapshot();
    assertThat(snap.written()).isEqualTo(2);
    assertThat(snap.completed()).isEqualTo(2);
    assertThat(snap.enqueued()).isEqualTo(2);
    assertThat(snap.failed()).isZero();
    assertThat(snap.inFlight()).isZero();
    assertThat(snap.queued()).isZero();
    writer.stop();
  }

  @Test
  @DisplayName("a failed append increments failed and completed, never written")
  void failedAppendIncrementsFailedAndCompleted() throws Exception {
    Path blocker = tempDir.resolve("fishing");
    Files.writeString(blocker, "blocker");

    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    writer.flush();

    FishingShadowDecisionWriter.WriterStatusSnapshot snap = writer.statusSnapshot();
    assertThat(snap.failed()).isEqualTo(1);
    assertThat(snap.completed()).isEqualTo(1);
    assertThat(snap.written()).isZero();
    assertThat(snap.error()).isEqualTo("SHADOW_PERSISTENCE_WRITE_FAILED");
    writer.stop();
  }

  @Test
  @DisplayName("an unsafe persistence path increments failed and completed, never written")
  void unsafePathIncrementsFailedNotWritten() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    assertThat(writer.enqueue(decision("../evil", 1))).isTrue();
    writer.flush();

    FishingShadowDecisionWriter.WriterStatusSnapshot snap = writer.statusSnapshot();
    assertThat(snap.failed()).isEqualTo(1);
    assertThat(snap.completed()).isEqualTo(1);
    assertThat(snap.written()).isZero();
    assertThat(snap.enqueued()).isEqualTo(1);
    writer.stop();
  }

  @Test
  @DisplayName("a queue-full item increments dropped, not enqueued")
  void queueFullIncrementsDroppedNotEnqueued() throws Exception {
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    FishingShadowDecisionWriter writer =
        new FishingShadowDecisionWriter(uninterruptibleBlockingMapper(writeStarted, releaseWrite));
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 0))).isTrue();
    assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

    ArrayBlockingQueue<FishingShadowDecision> fullQueue = new ArrayBlockingQueue<>(2048);
    for (int i = 0; i < 2048; i++) {
      fullQueue.offer(decision("debug_full", i));
    }
    ReflectionTestUtils.setField(writer, "queue", fullQueue);
    long enqueuedBefore = writer.getEnqueuedCount();

    assertThat(writer.enqueue(decision("debug_full", 2048))).isFalse();
    assertThat(writer.getEnqueuedCount()).isEqualTo(enqueuedBefore);
    assertThat(writer.getDroppedCount()).isEqualTo(1);

    releaseWrite.countDown();
    ReflectionTestUtils.setField(writer, "queue", new ArrayBlockingQueue<>(2048));
    writer.stop();
  }

  @Test
  @DisplayName(
      "enqueue racing with stop is either accepted before DRAINING and written, or rejected")
  void enqueueRacingWithStopIsAcceptedOrRejected() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    // This test asserts a clean drain, not the separately covered two-second timeout path. On a
    // loaded Windows filesystem 1,200 synchronized JSONL appends can legitimately exceed the
    // production timeout, so give this concurrency contract enough time to observe every accepted
    // write instead of turning host I/O speed into a race outcome.
    ReflectionTestUtils.setField(writer, "drainTimeoutMillis", 30_000L);
    writer.start();
    int threads = 8;
    int perThread = 150;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch firstAccepted = new CountDownLatch(1);
    AtomicLong accepted = new AtomicLong();
    AtomicLong rejected = new AtomicLong();
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      String session = "race_" + t;
      futures.add(
          executor.submit(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                for (long i = 0; i < perThread; i++) {
                  if (writer.enqueue(decision(session, i))) {
                    accepted.incrementAndGet();
                    firstAccepted.countDown();
                  } else {
                    rejected.incrementAndGet();
                  }
                }
                return null;
              }));
    }
    start.countDown();
    // Scheduling latency is not the behavior under test. Establish at least one admitted write
    // before racing shutdown with the remaining producers.
    assertThat(firstAccepted.await(5, TimeUnit.SECONDS)).isTrue();
    writer.stop();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertThat(accepted.get()).isPositive();
    assertThat(accepted.get() + rejected.get()).isEqualTo((long) threads * perThread);
    // Every accepted decision was drained and written; nothing remains queued after shutdown.
    assertThat(writer.getWrittenCount()).isEqualTo(accepted.get());
    assertThat(writer.queueSize()).isZero();
  }

  @Test
  @DisplayName("no accepted item remains queued after a successful shutdown")
  void noAcceptedItemRemainsQueuedAfterShutdown() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    for (long tick = 1; tick <= 500; tick++) {
      assertThat(writer.enqueue(decision("debug_1", tick))).isTrue();
    }

    writer.stop();

    assertThat(writer.getEnqueuedCount()).isEqualTo(500);
    assertThat(writer.getWrittenCount()).isEqualTo(500);
    assertThat(writer.queueSize()).isZero();
    assertThat(writer.getError()).isNull();
    assertThat(Files.readAllLines(shadowFile("debug_1"))).hasSize(500);
  }

  @Test
  @DisplayName("an interrupted shutdown join performs a real second join and restores the flag")
  void interruptionDuringShutdownIsVisible() throws Exception {
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    ObjectMapper slowMapper =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) throws JsonProcessingException {
            writeStarted.countDown();
            try {
              releaseWrite.await();
            } catch (InterruptedException e) {
              // Honor the shutdown interrupt: the write completes and the writer thread exits so
              // the bounded second join observes the exit.
            }
            return super.writeValueAsString(value);
          }
        };

    FishingShadowDecisionWriter writer = new FishingShadowDecisionWriter(slowMapper);
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    ReflectionTestUtils.setField(writer, "drainTimeoutMillis", 60_000L);
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

    AtomicBoolean interruptFlagRestored = new AtomicBoolean();
    CountDownLatch stopperDone = new CountDownLatch(1);
    Thread stopper =
        new Thread(
            () -> {
              writer.stop();
              interruptFlagRestored.set(Thread.currentThread().isInterrupted());
              stopperDone.countDown();
            },
            "stopper");
    stopper.start();
    Thread.sleep(100);
    stopper.interrupt();
    assertThat(stopperDone.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(interruptFlagRestored.get()).isTrue();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_SHUTDOWN_INTERRUPTED");
    // The real bounded second join waited for the writer to exit.
    Thread writerThread = (Thread) ReflectionTestUtils.getField(writer, "writerThread");
    assertThat(writerThread.isAlive()).isFalse();
    assertThat(writer.statusSnapshot().lifecycle()).isEqualTo("STOPPED");
    writer.stop();
  }

  @Test
  @DisplayName("concurrent stop calls do not overlap shutdown processing")
  void concurrentStopCallsDoNotOverlapShutdownProcessing() throws Exception {
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    FishingShadowDecisionWriter writer =
        new FishingShadowDecisionWriter(uninterruptibleBlockingMapper(writeStarted, releaseWrite));
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    ReflectionTestUtils.setField(writer, "drainTimeoutMillis", 300L);
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

    long startNanos = System.nanoTime();
    CountDownLatch done = new CountDownLatch(2);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread a =
        new Thread(
            () -> {
              try {
                writer.stop();
              } catch (Throwable t) {
                failure.set(t);
              } finally {
                done.countDown();
              }
            },
            "stop-a");
    Thread b =
        new Thread(
            () -> {
              try {
                writer.stop();
              } catch (Throwable t) {
                failure.set(t);
              } finally {
                done.countDown();
              }
            },
            "stop-b");
    a.start();
    b.start();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

    // Serialized shutdown: two sequential drain attempts (300ms join + 500ms second join each)
    // must exceed a single overlapping attempt by a wide margin.
    assertThat(elapsedMs).isGreaterThanOrEqualTo(1200);
    assertThat(failure.get()).isNull();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_DRAIN_TIMEOUT");
    assertThat(writer.statusSnapshot().lifecycle()).isEqualTo("DRAINING");

    releaseWrite.countDown();
    Thread writerThread = (Thread) ReflectionTestUtils.getField(writer, "writerThread");
    writerThread.join(5000);
    assertThat(writer.statusSnapshot().lifecycle()).isEqualTo("STOPPED");
  }

  @Test
  @DisplayName("a terminal drain-timeout error survives a later successful write completion")
  void terminalErrorSurvivesLaterWriteCompletion() throws Exception {
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    FishingShadowDecisionWriter writer =
        new FishingShadowDecisionWriter(uninterruptibleBlockingMapper(writeStarted, releaseWrite));
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    ReflectionTestUtils.setField(writer, "drainTimeoutMillis", 300L);
    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

    writer.stop();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_DRAIN_TIMEOUT");

    // The write later completes successfully; the terminal shutdown diagnostic must survive.
    releaseWrite.countDown();
    Thread writerThread = (Thread) ReflectionTestUtils.getField(writer, "writerThread");
    writerThread.join(5000);
    assertThat(writerThread.isAlive()).isFalse();
    assertThat(writer.statusSnapshot().lifecycle()).isEqualTo("STOPPED");
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_DRAIN_TIMEOUT");
  }

  @Test
  @DisplayName("snapshot invariants hold during repeated concurrent writes")
  void snapshotInvariantsDuringConcurrentWrites() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    int threads = 4;
    int perThread = 100;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean invariantHolds = new AtomicBoolean(true);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      String session = "inv_" + t;
      futures.add(
          executor.submit(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                for (long i = 0; i < perThread; i++) {
                  writer.enqueue(decision(session, i));
                  FishingShadowDecisionWriter.WriterStatusSnapshot snap = writer.statusSnapshot();
                  if (snap.enqueued() != snap.queued() + snap.inFlight() + snap.completed()) {
                    invariantHolds.set(false);
                  }
                  if (snap.completed() != snap.written() + snap.failed()) {
                    invariantHolds.set(false);
                  }
                }
                return null;
              }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();
    writer.flush();

    FishingShadowDecisionWriter.WriterStatusSnapshot snap = writer.statusSnapshot();
    assertThat(invariantHolds.get()).isTrue();
    assertThat(snap.enqueued()).isEqualTo(snap.queued() + snap.inFlight() + snap.completed());
    assertThat(snap.completed()).isEqualTo(snap.written() + snap.failed());
    writer.stop();
  }

  @Test
  @DisplayName("flush finishes when all accepted writes have either succeeded or failed")
  void flushFinishesWhenAllAcceptedWritesSucceedOrFail() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    for (long tick = 1; tick <= 3; tick++) {
      assertThat(writer.enqueue(decision("debug_1", tick))).isTrue();
    }
    assertThat(writer.enqueue(decision("../evil", 99))).isTrue();

    writer.flush();

    assertThat(writer.getCompletedCount()).isEqualTo(writer.getEnqueuedCount());
    assertThat(writer.getWrittenCount()).isEqualTo(3);
    assertThat(writer.getFailedCount()).isEqualTo(1);
    assertThat(writer.queueSize()).isZero();
    writer.stop();
  }

  @Test
  @DisplayName("history reads remain safe during repeated concurrent appends")
  void historyReadsSafeDuringConcurrentAppends() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    int appenders = 4;
    ExecutorService executor = Executors.newFixedThreadPool(appenders + 1);
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean corrupt = new AtomicBoolean(false);
    Future<?> reader =
        executor.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              for (int i = 0; i < 200; i++) {
                ReentrantReadWriteLock.ReadLock readLock = writer.readLock();
                readLock.lock();
                try {
                  Path file = shadowFile("debug_rw");
                  if (Files.exists(file)) {
                    String content = Files.readString(file);
                    for (String line : content.split("\n", -1)) {
                      if (!line.isBlank()) {
                        try {
                          objectMapper.readValue(line, FishingShadowDecision.class);
                        } catch (Exception e) {
                          corrupt.set(true);
                        }
                      }
                    }
                  }
                } catch (Exception e) {
                  corrupt.set(true);
                } finally {
                  readLock.unlock();
                }
              }
              return null;
            });
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < appenders; t++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                for (long i = 0; i < 200; i++) {
                  writer.enqueue(decision("debug_rw", i));
                }
                return null;
              }));
    }
    start.countDown();
    reader.get(30, TimeUnit.SECONDS);
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();
    writer.flush();
    writer.stop();

    assertThat(corrupt.get()).isFalse();
  }

  @Test
  @DisplayName("the writer status snapshot exposes lifecycle, queue, and counters atomically")
  void statusSnapshotExposesConsistentFields() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    FishingShadowDecisionWriter.WriterStatusSnapshot before = writer.statusSnapshot();
    assertThat(before.lifecycle()).isEqualTo("NEW");
    assertThat(before.enqueued()).isZero();
    assertThat(before.queued()).isZero();
    assertThat(before.inFlight()).isZero();
    assertThat(before.completed()).isZero();
    assertThat(before.written()).isZero();
    assertThat(before.failed()).isZero();
    assertThat(before.dropped()).isZero();
    assertThat(before.queueSize()).isZero();
    assertThat(before.error()).isNull();

    writer.start();
    assertThat(writer.enqueue(decision("debug_1", 1))).isTrue();
    assertThat(writer.enqueue(decision("debug_1", 2))).isTrue();
    writer.flush();
    FishingShadowDecisionWriter.WriterStatusSnapshot after = writer.statusSnapshot();
    assertThat(after.lifecycle()).isEqualTo("RUNNING");
    assertThat(after.enqueued()).isEqualTo(2);
    assertThat(after.completed()).isEqualTo(2);
    assertThat(after.written()).isEqualTo(2);
    assertThat(after.failed()).isZero();
    assertThat(after.dropped()).isZero();
    assertThat(after.queued()).isZero();
    assertThat(after.inFlight()).isZero();
    assertThat(after.queueSize()).isZero();
    writer.stop();
  }
}

package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Tests the nonblocking shadow decision writer: ordering, failure handling, and drain. */
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
    return new FishingShadowDecision(
        1,
        "AI_PLAYER_SHADOW_DECISION",
        "PREDICTED",
        "fishing_ab12cd34",
        sessionId,
        tick,
        "2026-08-11T05:30:00Z",
        SemanticAction.interactNpc("net", "fishing spot"),
        null,
        null,
        4.0,
        8201L,
        450);
  }

  private Path shadowFile(String sessionId) {
    return tempDir.resolve("fishing").resolve("shadow").resolve(sessionId + ".jsonl");
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
  @DisplayName("queue saturation produces a visible queue-full error while the writer is running")
  void queueSaturationProducesVisibleError() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    // Pre-fill the bounded queue directly (bypassing enqueue) so the saturation path is exercised
    // while the writer is still RUNNING; enqueueing after stop() is now rejected by the lifecycle.
    ArrayBlockingQueue<FishingShadowDecision> fullQueue = new ArrayBlockingQueue<>(2048);
    for (int i = 0; i < 2048; i++) {
      fullQueue.offer(decision("debug_full", i));
    }
    ReflectionTestUtils.setField(writer, "queue", fullQueue);

    assertThat(writer.enqueue(decision("debug_full", 2048))).isFalse();
    assertThat(writer.getError()).isEqualTo("SHADOW_PERSISTENCE_QUEUE_FULL");
    assertThat(writer.getDroppedCount()).isEqualTo(1);
    assertThat(writer.queueSize()).isEqualTo(2048);
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
    // stop() is idempotent and safe after the writer exited.
    writer.stop();
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
    writer.stop();
  }

  @Test
  @DisplayName("the writer refuses unsafe paths at the persistence boundary")
  void writerRefusesUnsafePaths() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    assertThat(writer.enqueue(decision("../evil", 1))).isTrue();
    writer.flush();

    assertThat(Files.exists(shadowFile("../evil"))).isFalse();
    assertThat(Files.exists(tempDir.resolve("fishing").resolve("evil.jsonl"))).isFalse();
    assertThat(Files.exists(tempDir.resolve("evil.jsonl"))).isFalse();
    assertThat(writer.getError()).isNull();
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
  }

  @Test
  @DisplayName("null decisions are rejected without side effects")
  void nullDecisionRejected() {
    FishingShadowDecisionWriter writer = newWriter();
    assertThat(writer.enqueue(null)).isFalse();
    assertThat(writer.getError()).isNull();
  }

  @Test
  @DisplayName("the error is cleared after a successful write")
  void errorClearedAfterSuccessfulWrite() throws Exception {
    Path blocker = tempDir.resolve("fishing");
    Files.writeString(blocker, "blocker");

    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    writer.enqueue(decision("debug_1", 1));
    writer.flush();
    assertThat(writer.getError()).isNotNull();

    Files.delete(blocker);
    writer.enqueue(decision("debug_1", 2));
    writer.flush();
    assertThat(writer.getError()).isNull();
    writer.stop();
  }

  @Test
  @DisplayName(
      "enqueue racing with stop is either accepted before DRAINING and written, or rejected")
  void enqueueRacingWithStopIsAcceptedOrRejected() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    writer.start();
    int threads = 8;
    int perThread = 150;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
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
                  } else {
                    rejected.incrementAndGet();
                  }
                }
                return null;
              }));
    }
    start.countDown();
    Thread.sleep(20);
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
  @DisplayName("an interrupted shutdown join is visible and never reports a clean drain")
  void interruptionDuringShutdownIsVisible() throws Exception {
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    ObjectMapper slowMapper =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) throws JsonProcessingException {
            writeStarted.countDown();
            boolean released = false;
            while (!released) {
              try {
                releaseWrite.await();
                released = true;
              } catch (InterruptedException e) {
                // The writer may be interrupted by stop(); ignore the interrupt and stay blocked so
                // the test can observe the interrupted-shutdown error before the writer finishes.
                Thread.interrupted();
              }
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

    releaseWrite.countDown();
    Thread writerThread = (Thread) ReflectionTestUtils.getField(writer, "writerThread");
    writerThread.join(5000);
    assertThat(writerThread.isAlive()).isFalse();
    writer.stop();
  }

  @Test
  @DisplayName("the writer status snapshot exposes lifecycle, queue, and counters atomically")
  void statusSnapshotExposesConsistentFields() throws Exception {
    FishingShadowDecisionWriter writer = newWriter();
    FishingShadowDecisionWriter.WriterStatusSnapshot before = writer.statusSnapshot();
    assertThat(before.lifecycle()).isEqualTo("NEW");
    assertThat(before.enqueued()).isZero();
    assertThat(before.written()).isZero();
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
    assertThat(after.written()).isEqualTo(2);
    assertThat(after.dropped()).isZero();
    assertThat(after.queueSize()).isZero();
    writer.stop();
  }
}

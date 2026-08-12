package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Nonblocking background writer for shadow decisions.
 *
 * <p>Shadow decisions are appended to {@code
 * .chromascape/datasets/fishing/shadow/{sessionId}.jsonl} on a single background writer thread fed
 * by a bounded queue. Tick ingestion never blocks on disk I/O: a full queue drops the newest
 * decision, reports {@code SHADOW_PERSISTENCE_QUEUE_FULL}, and logs the drop with rate limiting.
 *
 * <p>The writer re-validates the session ID at the persistence boundary and refuses path traversal,
 * so an unsafe ID can never reach filesystem resolution.
 *
 * <p>The writer follows a strict lifecycle: {@code NEW → RUNNING → DRAINING → STOPPED}. Work is
 * accepted only while {@code RUNNING}; {@code stop()} rejects new work before draining and reports
 * {@code SHADOW_PERSISTENCE_STOPPED} for late enqueues. If the drain exceeds the timeout the writer
 * thread is interrupted and {@code SHADOW_PERSISTENCE_DRAIN_TIMEOUT} is reported. When the caller
 * of {@code stop()} is interrupted while joining, {@code SHADOW_PERSISTENCE_SHUTDOWN_INTERRUPTED}
 * is reported instead and a clean drain is never claimed.
 *
 * <p>Every lifecycle transition and every enqueue admission is serialized under a single private
 * {@code lifecycleLock}, so an enqueue can never race past a shutdown: after the transition to
 * {@code DRAINING} no enqueue can succeed. The writer exposes an immutable {@link
 * WriterStatusSnapshot} so callers can read lifecycle, queue size, and all counters atomically.
 *
 * <p>Writes and history reads share a read/write lock owned by this writer, so a history request
 * can never observe a partially appended final line and fail the whole read.
 */
@Service
public class FishingShadowDecisionWriter {

  private static final Logger log = LoggerFactory.getLogger(FishingShadowDecisionWriter.class);
  private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
  private static final int QUEUE_CAPACITY = 2048;
  private static final long DRAIN_TIMEOUT_MILLIS = 2_000L;
  private static final long SECOND_JOIN_MILLIS = 500L;
  private static final long DROP_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

  /** Lifecycle states: work is accepted only while RUNNING. */
  private enum Lifecycle {
    NEW,
    RUNNING,
    DRAINING,
    STOPPED
  }

  /**
   * Immutable snapshot of the writer's externally observable persistence state. All writer status
   * fields must be derived from a single snapshot so a status consumer can never observe a
   * lifecycle/counter/queue combination from different points in time.
   */
  public record WriterStatusSnapshot(
      String lifecycle, int queueSize, long enqueued, long written, long dropped, String error) {}

  private final ObjectMapper objectMapper;
  private volatile BlockingQueue<FishingShadowDecision> queue =
      new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final AtomicReference<String> error = new AtomicReference<>();
  private final AtomicLong enqueued = new AtomicLong();
  private final AtomicLong written = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong lastDropLogNanos = new AtomicLong();
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
  private volatile long drainTimeoutMillis = DRAIN_TIMEOUT_MILLIS;

  /** Serializes every lifecycle transition and every enqueue admission. */
  private final Object lifecycleLock = new Object();

  @Value("${ai-player.dataset.output-dir:.chromascape/datasets}")
  private String outputDir;

  private volatile Lifecycle lifecycle = Lifecycle.NEW;
  private volatile Thread writerThread;

  public FishingShadowDecisionWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Starts the single background writer thread. */
  @PostConstruct
  public void start() {
    synchronized (lifecycleLock) {
      if (lifecycle != Lifecycle.NEW) {
        return;
      }
      lifecycle = Lifecycle.RUNNING;
      writerThread = new Thread(this::writeLoop, "fishing-shadow-decision-writer");
      writerThread.setDaemon(true);
      writerThread.start();
    }
  }

  /**
   * Stops the writer thread and drains the queue for up to the drain timeout.
   *
   * <p>Rejects new work immediately (the writer enters DRAINING), then waits for the background
   * thread to drain. When the drain times out the thread is interrupted and {@code
   * SHADOW_PERSISTENCE_DRAIN_TIMEOUT} is reported. When the calling thread is interrupted while
   * joining, its interrupt flag is restored, the writer is interrupted, and {@code
   * SHADOW_PERSISTENCE_SHUTDOWN_INTERRUPTED} is reported without ever claiming a clean drain.
   * Stopping an already-stopped writer is a no-op.
   */
  @PreDestroy
  public void stop() {
    Thread thread;
    long drainTimeout = drainTimeoutMillis;
    synchronized (lifecycleLock) {
      if (lifecycle == Lifecycle.STOPPED) {
        return;
      }
      if (lifecycle == Lifecycle.NEW) {
        lifecycle = Lifecycle.STOPPED;
        return;
      }
      lifecycle = Lifecycle.DRAINING;
      thread = writerThread;
    }

    boolean drained = true;
    boolean interruptedWhileJoining = false;
    if (thread != null && thread != Thread.currentThread()) {
      try {
        thread.join(drainTimeout);
        drained = !thread.isAlive();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        interruptedWhileJoining = true;
      }
    }

    if (interruptedWhileJoining) {
      thread.interrupt();
      error.set("SHADOW_PERSISTENCE_SHUTDOWN_INTERRUPTED");
      log.warn(
          "Shadow persistence stop interrupted while joining; interrupting writer for shutdown");
      secondJoin(thread);
    } else if (!drained) {
      thread.interrupt();
      secondJoin(thread);
      error.set("SHADOW_PERSISTENCE_DRAIN_TIMEOUT");
      log.warn("Shadow persistence drain timed out after {}ms; interrupting writer", drainTimeout);
    }

    synchronized (lifecycleLock) {
      lifecycle = Lifecycle.STOPPED;
    }
  }

  private void secondJoin(Thread thread) {
    try {
      thread.join(SECOND_JOIN_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Enqueues one decision for asynchronous persistence.
   *
   * <p>The lifecycle check and queue admission are atomic: once {@code stop()} transitions the
   * writer to {@code DRAINING}, no enqueue can succeed. Admission holds the lifecycle lock while
   * offering to the bounded queue, and {@code enqueued} is incremented only after a successful
   * offer.
   *
   * @param decision the decision to persist
   * @return true when enqueued; false when the writer is not running ({@code
   *     SHADOW_PERSISTENCE_STOPPED}) or the queue is full and the decision was dropped ({@code
   *     SHADOW_PERSISTENCE_QUEUE_FULL})
   */
  public boolean enqueue(FishingShadowDecision decision) {
    if (decision == null) {
      return false;
    }
    synchronized (lifecycleLock) {
      if (lifecycle != Lifecycle.RUNNING) {
        error.set("SHADOW_PERSISTENCE_STOPPED");
        return false;
      }
      if (queue.offer(decision)) {
        enqueued.incrementAndGet();
        return true;
      }
      recordDrop(decision);
      return false;
    }
  }

  /**
   * Returns an immutable snapshot of the writer lifecycle, queue, and counters captured under the
   * lifecycle lock. All external writer status must derive from this single snapshot.
   */
  public WriterStatusSnapshot statusSnapshot() {
    synchronized (lifecycleLock) {
      return new WriterStatusSnapshot(
          lifecycle.name(),
          queue.size(),
          enqueued.get(),
          written.get(),
          dropped.get(),
          error.get());
    }
  }

  /** Returns the latest persistence/queue error, or null when none is pending. */
  public String getError() {
    return error.get();
  }

  /** Clears the current persistence/queue error. */
  public void clearError() {
    error.set(null);
  }

  /** Returns the number of decisions currently queued. */
  public int queueSize() {
    return queue.size();
  }

  /** Returns the number of decisions successfully written to disk. */
  public long getWrittenCount() {
    return written.get();
  }

  /** Returns the number of decisions dropped because the queue was full. */
  public long getDroppedCount() {
    return dropped.get();
  }

  /** Returns the number of decisions accepted into the queue. */
  public long getEnqueuedCount() {
    return enqueued.get();
  }

  /** Returns the read lock guarding history reads against concurrent appends. */
  public ReentrantReadWriteLock.ReadLock readLock() {
    return rwLock.readLock();
  }

  /**
   * Blocks until every enqueued decision has been written (or the drain timeout elapses).
   *
   * <p>Intended for tests and graceful shutdown points; live tick ingestion never calls it.
   */
  public void flush() {
    long drainTimeout = drainTimeoutMillis;
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainTimeout);
    while (enqueued.get() != written.get()) {
      if (System.nanoTime() >= deadline) {
        break;
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  private void writeLoop() {
    while (true) {
      Lifecycle current = lifecycle;
      if (current == Lifecycle.STOPPED) {
        return;
      }
      if (current == Lifecycle.DRAINING && queue.isEmpty()) {
        return;
      }
      FishingShadowDecision decision;
      try {
        decision = queue.poll(100, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        // Interrupted by stop() after a drain timeout (or by shutdown): exit without
        // re-interrupting
        // or spinning so the queue is never drained in a tight loop.
        return;
      }
      if (decision == null) {
        continue;
      }
      writeOne(decision);
    }
  }

  private void writeOne(FishingShadowDecision decision) {
    if (decision == null) {
      return;
    }
    if (!isSafeSessionId(decision.sessionId())) {
      log.warn(
          "Shadow persistence rejected unsafe session id: {}",
          safeDescription(decision.sessionId()));
      written.incrementAndGet();
      return;
    }
    Path file = shadowFile(decision.sessionId());
    rwLock.writeLock().lock();
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          objectMapper.writeValueAsString(decision) + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      written.incrementAndGet();
      error.set(null);
    } catch (IOException e) {
      error.set(e.getMessage());
      written.incrementAndGet();
      log.error(
          "Failed to persist shadow decision for session {} tick {}: {}",
          decision.sessionId(),
          decision.tick(),
          e.getMessage());
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  private void recordDrop(FishingShadowDecision decision) {
    dropped.incrementAndGet();
    error.set("SHADOW_PERSISTENCE_QUEUE_FULL");
    long now = System.nanoTime();
    long last = lastDropLogNanos.get();
    if (now - last >= DROP_LOG_INTERVAL_NANOS && lastDropLogNanos.compareAndSet(last, now)) {
      log.warn(
          "Shadow persistence queue full; dropping decision for session {} tick {}",
          decision.sessionId(),
          decision.tick());
    }
  }

  private Path shadowFile(String sessionId) {
    return Paths.get(outputDir, "fishing", "shadow", sessionId + ".jsonl");
  }

  private boolean isSafeSessionId(String sessionId) {
    return sessionId != null && SAFE_SESSION_ID.matcher(sessionId).matches();
  }

  private String safeDescription(String sessionId) {
    if (sessionId == null) {
      return "(null)";
    }
    String sanitized = sessionId.replaceAll("[^A-Za-z0-9_-]", "?");
    return sanitized.substring(0, Math.min(sanitized.length(), 24));
  }
}

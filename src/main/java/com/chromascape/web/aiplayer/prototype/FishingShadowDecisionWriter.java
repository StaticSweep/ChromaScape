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
 * <p>Schema-v2 shadow decisions are appended to {@code
 * .chromascape/datasets/fishing/shadow/{sessionId}/{shadowRunId}.jsonl} on a single background
 * writer thread fed by a bounded queue. Tick ingestion never blocks on disk I/O: a full queue drops
 * the newest decision and reports {@code SHADOW_PERSISTENCE_QUEUE_FULL}. Legacy schema-v1 history
 * is never written by this writer; it remains readable at {@code {sessionId}.jsonl}.
 *
 * <p>The writer re-validates both the session id and the shadow run id at the persistence boundary
 * and refuses path traversal, so an unsafe identifier can never reach filesystem resolution. An
 * unsafe session or run id increments {@code completed} and {@code failed} (it is finished, but not
 * written), never {@code written}, and reports {@code SHADOW_PERSISTENCE_INVALID_PATH}.
 *
 * <p>The writer follows a strict lifecycle: {@code NEW → RUNNING → DRAINING → STOPPED}. Work is
 * accepted only while {@code RUNNING}; {@code stop()} rejects new work before draining and reports
 * {@code SHADOW_PERSISTENCE_STOPPED} for late enqueues. {@code STOPPED} is only ever reached when
 * the writer thread has actually exited (or the writer was never started); the writer thread itself
 * performs the {@code DRAINING → STOPPED} transition in its exit path. Status therefore never
 * reports {@code STOPPED} while the writer thread is alive.
 *
 * <p>Every lifecycle transition and every enqueue admission is serialized under a single private
 * {@code lifecycleLock}, so an enqueue can never race past a shutdown: after the transition to
 * {@code DRAINING} no enqueue can succeed. A dedicated {@code shutdownLock} serializes concurrent
 * {@code stop()} calls so they can never perform overlapping joins or overwrite terminal shutdown
 * diagnostics. Terminal errors ({@code SHADOW_PERSISTENCE_DRAIN_TIMEOUT}, {@code
 * SHADOW_PERSISTENCE_SHUTDOWN_INTERRUPTED}, {@code SHADOW_PERSISTENCE_WRITER_FAILED}) are stored
 * separately from recoverable operational errors and are never cleared by a successful write.
 *
 * <p>Counters and errors are protected by a single {@code metricsLock}. {@code queued} and {@code
 * inFlight} are stored explicitly and transitioned atomically, so status always represents one
 * internally consistent point in time with {@code enqueued = queued + inFlight + completed} and
 * {@code completed = written + failed}. Every shutdown exit accounts for all accepted work: on a
 * forced or unexpected exit the remaining queue is abandoned (each item completes as failed and
 * abandoned) so {@code STOPPED} never leaves unaccounted accepted decisions.
 *
 * <p>Writes and history reads share a read/write lock owned by this writer, so a history request
 * can never observe a partially appended final line and fail the whole read.
 */
@Service
public class FishingShadowDecisionWriter {

  private static final Logger log = LoggerFactory.getLogger(FishingShadowDecisionWriter.class);
  private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
  private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
  private static final int QUEUE_CAPACITY = 2048;
  private static final long DRAIN_TIMEOUT_MILLIS = 2_000L;
  private static final long SECOND_JOIN_MILLIS = 500L;
  private static final long DROP_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

  private static final String TERMINAL_DRAIN_TIMEOUT = "SHADOW_PERSISTENCE_DRAIN_TIMEOUT";
  private static final String TERMINAL_SHUTDOWN_INTERRUPTED =
      "SHADOW_PERSISTENCE_SHUTDOWN_INTERRUPTED";
  private static final String TERMINAL_WRITER_FAILED = "SHADOW_PERSISTENCE_WRITER_FAILED";
  private static final String OPERATIONAL_QUEUE_FULL = "SHADOW_PERSISTENCE_QUEUE_FULL";
  private static final String OPERATIONAL_STOPPED = "SHADOW_PERSISTENCE_STOPPED";
  private static final String OPERATIONAL_WRITE_FAILED = "SHADOW_PERSISTENCE_WRITE_FAILED";
  private static final String OPERATIONAL_INVALID_PATH = "SHADOW_PERSISTENCE_INVALID_PATH";

  /** Lifecycle states: work is accepted only while RUNNING. */
  private enum Lifecycle {
    NEW,
    RUNNING,
    DRAINING,
    STOPPED
  }

  /**
   * Immutable snapshot of the writer's externally observable persistence state. All writer status
   * fields are derived from a single internally consistent metrics point so a status consumer can
   * never observe a lifecycle/counter/queue combination from different points in time.
   *
   * <p>Counter invariants during normal operation: {@code enqueued = queued + inFlight + completed}
   * and {@code completed = written + failed}. {@code abandoned} counts accepted work that the
   * writer terminated without attempting or completing; {@code abandoned <= failed}.
   */
  public record WriterStatusSnapshot(
      String lifecycle,
      int queueSize,
      long enqueued,
      long queued,
      long inFlight,
      long completed,
      long written,
      long failed,
      long abandoned,
      long dropped,
      String error) {}

  private final ObjectMapper objectMapper;
  private volatile BlockingQueue<FishingShadowDecision> queue =
      new ArrayBlockingQueue<>(QUEUE_CAPACITY);

  /** Terminal shutdown diagnostics; once set they are never cleared by writes. */
  private final AtomicReference<String> terminalError = new AtomicReference<>();

  /** Recoverable operational errors (queue full, stopped, write failed, invalid path). */
  private final AtomicReference<String> operationalError = new AtomicReference<>();

  private long enqueued;
  private long queued;
  private long inFlight;
  private long completed;
  private long written;
  private long failed;
  private long abandoned;
  private long dropped;

  /** Serializes every metric/error mutation and every snapshot read. */
  private final Object metricsLock = new Object();

  private final AtomicLong lastDropLogNanos = new AtomicLong();
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
  private volatile long drainTimeoutMillis = DRAIN_TIMEOUT_MILLIS;

  /** Serializes every lifecycle transition and every enqueue admission. */
  private final Object lifecycleLock = new Object();

  /** Serializes shutdown processing so concurrent {@link #stop()} calls never overlap joins. */
  private final Object shutdownLock = new Object();

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
   * thread to drain. When the drain times out, {@code SHADOW_PERSISTENCE_DRAIN_TIMEOUT} is recorded
   * immediately and retained even if the subsequent interrupt makes the writer exit; when the
   * calling thread is interrupted while joining, its interrupt flag is restored, the writer is
   * interrupted, and {@code SHADOW_PERSISTENCE_SHUTDOWN_INTERRUPTED} is reported without ever
   * claiming a clean drain. {@code STOPPED} is reported only after the writer thread actually exits
   * and has accounted for every accepted decision (written, failed, or abandoned). Stopping an
   * already-stopped writer is a no-op and never erases terminal diagnostics.
   *
   * <p>Concurrent {@code stop()} calls are serialized under {@code shutdownLock}; only the first
   * caller joins the writer while every caller still performs a bounded post-join check, and the
   * first terminal diagnostic wins.
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
      if (lifecycle != Lifecycle.DRAINING) {
        lifecycle = Lifecycle.DRAINING;
      }
      thread = writerThread;
    }

    synchronized (shutdownLock) {
      boolean drained = true;
      boolean interruptedWhileJoining = false;
      if (thread != null && thread != Thread.currentThread()) {
        try {
          thread.join(drainTimeout);
          drained = !thread.isAlive();
        } catch (InterruptedException e) {
          interruptedWhileJoining = true;
        }
      }

      if (interruptedWhileJoining) {
        // Record that the caller was interrupted, temporarily clear its flag so the bounded second
        // join below is a real join, then restore the flag before returning.
        Thread.interrupted();
        thread.interrupt();
        joinBounded(thread);
        terminalError.compareAndSet(null, TERMINAL_SHUTDOWN_INTERRUPTED);
        Thread.currentThread().interrupt();
        log.warn(
            "Shadow persistence stop interrupted while joining; interrupting writer for shutdown");
      } else if (!drained) {
        // Record the drain timeout immediately and retain it even if the interrupt below makes the
        // writer exit during the bounded second join.
        terminalError.compareAndSet(null, TERMINAL_DRAIN_TIMEOUT);
        thread.interrupt();
        boolean exited = joinBounded(thread);
        if (!exited) {
          // The writer thread is still alive: retain DRAINING and keep the recorded timeout. The
          // writer thread itself transitions DRAINING -> STOPPED when it actually exits.
          log.warn(
              "Shadow persistence drain timed out after {}ms; interrupting writer", drainTimeout);
        }
      }
    }
  }

  /** Bounded join; returns whether the writer thread exited during it. */
  private boolean joinBounded(Thread thread) {
    try {
      thread.join(SECOND_JOIN_MILLIS);
    } catch (InterruptedException e) {
      // The caller restores its own interrupt flag after the bounded join completes.
    }
    return !thread.isAlive();
  }

  /**
   * Enqueues one decision for asynchronous persistence.
   *
   * <p>The lifecycle check and queue admission are atomic: once {@code stop()} transitions the
   * writer to {@code DRAINING}, no enqueue can succeed. Admission holds the lifecycle lock while
   * offering to the bounded queue, and {@code enqueued}/{@code queued} are incremented only after a
   * successful offer. A queue-full item increments only {@code dropped}, never {@code enqueued}.
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
        setOperationalError(OPERATIONAL_STOPPED);
        return false;
      }
      if (queue.offer(decision)) {
        synchronized (metricsLock) {
          enqueued++;
          queued++;
        }
        return true;
      }
      recordDrop(decision);
      return false;
    }
  }

  /**
   * Returns an immutable snapshot of the writer lifecycle, queue, and counters. Lifecycle and
   * counters are captured under {@code lifecycleLock} then {@code metricsLock} (the documented lock
   * order), so the snapshot represents one internally consistent point in time.
   */
  public WriterStatusSnapshot statusSnapshot() {
    String currentLifecycle;
    long enq;
    long que;
    long inflight;
    long comp;
    long writ;
    long fail;
    long aband;
    long drop;
    String operational;
    synchronized (lifecycleLock) {
      currentLifecycle = lifecycle.name();
      synchronized (metricsLock) {
        enq = enqueued;
        que = queued;
        inflight = inFlight;
        comp = completed;
        writ = written;
        fail = failed;
        aband = abandoned;
        drop = dropped;
        operational = operationalError.get();
      }
    }
    String terminal = terminalError.get();
    return new WriterStatusSnapshot(
        currentLifecycle,
        (int) que,
        enq,
        que,
        inflight,
        comp,
        writ,
        fail,
        aband,
        drop,
        terminal != null ? terminal : operational);
  }

  /** Returns the latest persistence/queue error (terminal first), or null when none is pending. */
  public String getError() {
    String terminal = terminalError.get();
    if (terminal != null) {
      return terminal;
    }
    synchronized (metricsLock) {
      return operationalError.get();
    }
  }

  /** Clears both terminal and operational errors (manual admin reset). */
  public void clearError() {
    synchronized (metricsLock) {
      operationalError.set(null);
    }
    terminalError.set(null);
  }

  /** Returns the number of decisions currently waiting in the queue. */
  public int queueSize() {
    synchronized (metricsLock) {
      return (int) Math.max(0, queued);
    }
  }

  /** Returns the number of decisions successfully written to disk. */
  public long getWrittenCount() {
    synchronized (metricsLock) {
      return written;
    }
  }

  /** Returns the number of decisions dropped because the queue was full. */
  public long getDroppedCount() {
    synchronized (metricsLock) {
      return dropped;
    }
  }

  /** Returns the number of decisions accepted into the queue. */
  public long getEnqueuedCount() {
    synchronized (metricsLock) {
      return enqueued;
    }
  }

  /** Returns the number of decisions finished (written or failed or abandoned). */
  public long getCompletedCount() {
    synchronized (metricsLock) {
      return completed;
    }
  }

  /** Returns the number of decisions whose persistence attempt failed. */
  public long getFailedCount() {
    synchronized (metricsLock) {
      return failed;
    }
  }

  /** Returns the number of accepted decisions abandoned on writer shutdown. */
  public long getAbandonedCount() {
    synchronized (metricsLock) {
      return abandoned;
    }
  }

  /** Returns the number of decisions removed from the queue but not yet finished. */
  public long getInFlightCount() {
    synchronized (metricsLock) {
      return inFlight;
    }
  }

  /** Returns the read lock guarding history reads against concurrent appends. */
  public ReentrantReadWriteLock.ReadLock readLock() {
    return rwLock.readLock();
  }

  /**
   * Blocks until every accepted decision has finished (written or failed or abandoned) or the drain
   * timeout elapses.
   *
   * <p>It intentionally does not wait for {@code written == enqueued}, because a failed write is
   * still completed work. Intended for tests and graceful shutdown points; live tick ingestion
   * never calls it.
   */
  public void flush() {
    long drainTimeout = drainTimeoutMillis;
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainTimeout);
    while (queuedOrInFlight() > 0) {
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

  private long queuedOrInFlight() {
    synchronized (metricsLock) {
      return queued + inFlight;
    }
  }

  private void setOperationalError(String code) {
    synchronized (metricsLock) {
      operationalError.set(code);
    }
  }

  private void writeLoop() {
    boolean unexpectedTermination = true;
    try {
      while (true) {
        Lifecycle current = lifecycle;
        if (current == Lifecycle.STOPPED) {
          unexpectedTermination = false;
          return;
        }
        if (current == Lifecycle.DRAINING && queue.isEmpty()) {
          unexpectedTermination = false;
          return;
        }
        FishingShadowDecision decision;
        try {
          decision = queue.poll(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          // Interrupted by stop() after a drain timeout (or by shutdown): exit without spinning.
          // Remaining accepted work is abandoned in the exit path. This is a forced exit, not an
          // unexpected termination.
          unexpectedTermination = false;
          return;
        }
        if (decision == null) {
          continue;
        }
        beginProcessing();
        if (!isSafeSessionId(decision.sessionId()) || !isSafeRunId(decision.shadowRunId())) {
          log.warn(
              "Shadow persistence rejected invalid path: session={}, run={}",
              safeDescription(decision.sessionId()),
              safeDescription(decision.shadowRunId()));
          recordFailure(OPERATIONAL_INVALID_PATH);
          continue;
        }
        try {
          append(decision);
          recordWriteSuccess();
        } catch (IOException | RuntimeException e) {
          recordFailure(OPERATIONAL_WRITE_FAILED);
          log.error(
              "Failed to persist shadow decision for session {} run {} tick {}: {}",
              decision.sessionId(),
              decision.shadowRunId(),
              decision.tick(),
              e.getMessage());
        }
      }
    } finally {
      if (unexpectedTermination) {
        // The loop exited without being stopped or requested to stop: a fatal error escaped the
        // per-item containment. Report WRITER_FAILED, abandon every still-accepted decision, and
        // stop.
        terminalError.compareAndSet(null, TERMINAL_WRITER_FAILED);
        log.error(
            "Shadow persistence writer terminated unexpectedly; abandoning all accepted work");
      }
      abandonRemainingQueue();
      // The writer thread is the only actor that transitions DRAINING -> STOPPED, so status never
      // reports STOPPED while this thread is alive.
      synchronized (lifecycleLock) {
        if (lifecycle != Lifecycle.STOPPED) {
          lifecycle = Lifecycle.STOPPED;
        }
      }
    }
  }

  /** Atomic queue transition: one item moves from queued to in-flight. */
  private void beginProcessing() {
    synchronized (metricsLock) {
      queued--;
      inFlight++;
    }
  }

  /** Atomic completion: one in-flight item finishes as written. */
  private void recordWriteSuccess() {
    synchronized (metricsLock) {
      inFlight--;
      completed++;
      written++;
      // A successful write clears only recoverable operational errors; it never clears a terminal
      // shutdown error.
      operationalError.set(null);
    }
  }

  /** Atomic completion: one in-flight item finishes as failed. */
  private void recordFailure(String code) {
    synchronized (metricsLock) {
      inFlight--;
      completed++;
      failed++;
      operationalError.set(code);
    }
  }

  /**
   * Accounts for all accepted work that the writer terminated without attempting or completing.
   * Each remaining queued item (and any item that was in flight when the writer died) finishes as a
   * failed, abandoned completion so {@code STOPPED} never leaves unaccounted accepted work.
   */
  private void abandonRemainingQueue() {
    long abandonedCount = 0;
    FishingShadowDecision item;
    while ((item = queue.poll()) != null) {
      abandonedCount++;
    }
    synchronized (metricsLock) {
      if (abandonedCount > 0) {
        completed += abandonedCount;
        failed += abandonedCount;
        abandoned += abandonedCount;
        queued -= abandonedCount;
        if (queued < 0) {
          queued = 0;
        }
      }
      // If the writer died mid-write, the item in flight was never finished; complete it as an
      // abandoned failure so enqueued == queued + inFlight + completed still holds at STOPPED.
      if (inFlight > 0) {
        completed += inFlight;
        failed += inFlight;
        abandoned += inFlight;
        inFlight = 0;
      }
    }
  }

  /** Appends one decision to its per-run JSONL file. */
  private void append(FishingShadowDecision decision) throws IOException {
    Path file = shadowFile(decision.sessionId(), decision.shadowRunId());
    rwLock.writeLock().lock();
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          objectMapper.writeValueAsString(decision) + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  private void recordDrop(FishingShadowDecision decision) {
    synchronized (metricsLock) {
      dropped++;
      operationalError.set(OPERATIONAL_QUEUE_FULL);
    }
    long now = System.nanoTime();
    long last = lastDropLogNanos.get();
    if (now - last >= DROP_LOG_INTERVAL_NANOS && lastDropLogNanos.compareAndSet(last, now)) {
      log.warn(
          "Shadow persistence queue full; dropping decision for session {} tick {}",
          decision.sessionId(),
          decision.tick());
    }
  }

  private Path shadowFile(String sessionId, String shadowRunId) {
    return Paths.get(outputDir, "fishing", "shadow", sessionId, shadowRunId + ".jsonl");
  }

  private boolean isSafeSessionId(String sessionId) {
    return sessionId != null && SAFE_SESSION_ID.matcher(sessionId).matches();
  }

  private boolean isSafeRunId(String shadowRunId) {
    return shadowRunId != null && SAFE_RUN_ID.matcher(shadowRunId).matches();
  }

  private String safeDescription(String value) {
    if (value == null) {
      return "(null)";
    }
    String sanitized = value.replaceAll("[^A-Za-z0-9_-]", "?");
    return sanitized.substring(0, Math.min(sanitized.length(), 24));
  }
}

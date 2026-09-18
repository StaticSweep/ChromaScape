package com.chromascape.utils.core.input.remoteinput;

import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One bounded, serialized native-call lane.
 *
 * <p>A timed-out native call is never reused: the lane poisons itself, the queued task is
 * cancelled, and every later call fails with {@link InputSessionDiagnostic#SESSION_POISONED}. The
 * owning session keeps its attachment lease when poisoned because the interrupted native operation
 * may still be executing inside the target.
 */
public final class NativeCallExecutor implements AutoCloseable {

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "RemoteInput-native-call");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicBoolean poisoned = new AtomicBoolean();
  private final ProcessIdentity identity;
  private final long generation;

  /**
   * Creates a lane bound to one identity and selection generation.
   *
   * @param identity the bound identity
   * @param generation the observed selection generation
   */
  public NativeCallExecutor(ProcessIdentity identity, long generation) {
    this.identity = identity;
    this.generation = generation;
  }

  /**
   * Runs {@code operation} on the lane, bounded by {@code timeout}.
   *
   * @param operation the native operation
   * @param timeout the deadline
   * @param timeoutDiagnostic the diagnostic raised on timeout
   * @param <T> the operation result type
   * @return the operation result
   * @throws InputSessionException on timeout, interruption, or native failure
   */
  public <T> T call(
      Callable<T> operation, Duration timeout, InputSessionDiagnostic timeoutDiagnostic) {
    if (poisoned.get()) {
      throw new InputSessionException(
          InputSessionDiagnostic.SESSION_POISONED,
          identity,
          generation,
          "Native call lane is poisoned");
    }
    Future<T> future = executor.submit(operation);
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      poisoned.set(true);
      future.cancel(true);
      executor.shutdownNow();
      throw new InputSessionException(
          timeoutDiagnostic, identity, generation, "Native operation exceeded its deadline", e);
    } catch (InterruptedException e) {
      poisoned.set(true);
      future.cancel(true);
      executor.shutdownNow();
      Thread.currentThread().interrupt();
      throw new InputSessionException(
          InputSessionDiagnostic.NATIVE_CALL_FAILED,
          identity,
          generation,
          "Native operation interrupted",
          e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new InputSessionException(
          InputSessionDiagnostic.NATIVE_CALL_FAILED,
          identity,
          generation,
          "Native operation failed",
          cause);
    }
  }

  /** Returns whether the lane timed out and can no longer be used. */
  public boolean isPoisoned() {
    return poisoned.get();
  }

  @Override
  public void close() {
    poisoned.set(true);
    executor.shutdownNow();
  }
}

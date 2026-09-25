package com.chromascape.web.debug.buildergoal.service;

/**
 * Cooperative cancellation contract that Builder Goal launch workers poll during long-running
 * Micro6 operations.
 *
 * <p>Implementations are safe to read from any thread; the cancelling thread sets the flag and may
 * interrupt the worker, while the worker thread checks the flag (and its own interrupt status) at
 * safe points. Implementations may also fold additional conditions (such as local launch lease
 * loss) into {@link #isCancelled()}.
 */
@FunctionalInterface
public interface BuilderGoalLaunchCancellation {

  BuilderGoalLaunchCancellation NONE = () -> false;

  boolean isCancelled();

  /**
   * Throws {@link BuilderGoalLaunchCancelledException} when the worker has been cancelled or its
   * thread has been interrupted. The interrupt status is preserved so nested interruptible calls
   * observe the interruption after the exception propagates.
   */
  default void throwIfCancelled() {
    if (isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new BuilderGoalLaunchCancelledException();
    }
  }
}

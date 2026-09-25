package com.chromascape.web.debug.buildergoal.service;

/**
 * Unchecked exception thrown when a Builder Goal launch worker detects it has been cancelled
 * (operator stop, watchdog transition, or lease loss).
 *
 * <p>Distinguishable from ordinary preparation or launch failure so callers can avoid converting a
 * cooperative cancellation into a misleading terminal failure message. The message is fixed and
 * credential-free.
 */
public class BuilderGoalLaunchCancelledException extends RuntimeException {

  public static final String MESSAGE = "Builder Goal launch was cancelled";

  public BuilderGoalLaunchCancelledException() {
    super(MESSAGE);
  }

  /**
   * Creates a cancellation exception from an interrupted wait. Restores the thread interrupt status
   * so nested interruptible calls observe the interruption after the exception propagates.
   */
  public static BuilderGoalLaunchCancelledException fromInterrupted(InterruptedException cause) {
    Thread.currentThread().interrupt();
    return new BuilderGoalLaunchCancelledException(cause);
  }

  private BuilderGoalLaunchCancelledException(Throwable cause) {
    super(MESSAGE, cause);
  }
}

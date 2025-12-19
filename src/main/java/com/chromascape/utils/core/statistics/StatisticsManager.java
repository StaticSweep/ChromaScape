package com.chromascape.utils.core.statistics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleton manager for tracking bot statistics such as runtime, cycles, inputs, and objects
 * detected.
 *
 * <p>Uses thread-safe atomic variables to allow concurrent updates from different parts of the bot
 * (e.g. input thread, vision thread, main loop) without blocking.
 */
public class StatisticsManager {

  private static final AtomicLong startTime = new AtomicLong(0);
  private static final AtomicInteger cycles = new AtomicInteger(0);
  private static final AtomicInteger inputs = new AtomicInteger(0);
  private static final AtomicInteger objectsDetected = new AtomicInteger(0);

  private StatisticsManager() {}

  /** Resets all statistics to zero and sets the start time to the current system time. */
  public static void reset() {
    startTime.set(System.currentTimeMillis());
    cycles.set(0);
    inputs.set(0);
    objectsDetected.set(0);
  }

  /** Increments the cycle count by one. */
  public static void incrementCycles() {
    cycles.incrementAndGet();
  }

  /** Increments the total input count by one. */
  public static void incrementInputs() {
    inputs.incrementAndGet();
  }

  /** Increments the total objects detected count by one. */
  public static void incrementObjectsDetected() {
    objectsDetected.incrementAndGet();
  }

  // Getters

  public static long getStartTime() {
    return startTime.get();
  }

  public static int getCycles() {
    return cycles.get();
  }

  public static int getInputs() {
    return inputs.get();
  }

  public static int getObjectsDetected() {
    return objectsDetected.get();
  }

  /**
   * Calculates the elapsed time in milliseconds.
   *
   * @return runtime in ms, or 0 if not started.
   */
  public static long getElapsedTime() {
    long start = startTime.get();
    return start == 0 ? 0 : System.currentTimeMillis() - start;
  }
}

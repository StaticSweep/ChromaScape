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
  private static final AtomicLong endTime = new AtomicLong(0);
  private static volatile boolean running = false;

  private static final AtomicInteger cycles = new AtomicInteger(0);
  private static final AtomicInteger inputs = new AtomicInteger(0);
  private static final AtomicInteger objectsDetected = new AtomicInteger(0);

  private StatisticsManager() {}

  /**
   * Resets all statistics to zero and sets the start time to the current system time.
   *
   * <p>Also resets the {@code endTime} and sets {@code running} to true.
   */
  public static void reset() {
    startTime.set(System.currentTimeMillis());
    endTime.set(0);
    running = true;
    cycles.set(0);
    inputs.set(0);
    objectsDetected.set(0);
  }

  /**
   * Stops the statistics tracking, freezing the elapsed time.
   *
   * <p>Sets {@code running} to false and records the current time as {@code endTime}. This ensures
   * {@link #getElapsedTime()} returns a static duration after stopping.
   */
  public static void stop() {
    running = false;
    endTime.set(System.currentTimeMillis());
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
   * <p>If the bot is running, returns {@code now - startTime}. If the bot is stopped, returns
   * {@code endTime - startTime}.
   *
   * @return runtime in ms, or 0 if not started.
   */
  public static long getElapsedTime() {
    long start = startTime.get();
    if (start == 0) {
      return 0;
    }
    if (running) {
      return System.currentTimeMillis() - start;
    } else {
      long end = endTime.get();
      // If end is somehow invalid or 0 (shouldn't happen if stop called), return 0 or
      // current diff
      return end > start ? end - start : 0;
    }
  }
}

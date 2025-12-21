package com.chromascape.utils.core.input.mouse;

import static java.util.concurrent.locks.LockSupport.parkNanos;

import java.awt.Point;
import java.util.Random;
import java.util.function.Consumer;

/**
 * A robust implementation of the WindMouse algorithm for human-like mouse movement.
 *
 * <p>This class simulates mouse movement using a physics-based model that accounts for:
 *
 * <ul>
 *   <li><b>Gravity:</b> A directional force pulling the cursor towards the target.
 *   <li><b>Wind:</b> A chaotic force adding randomness to the path to mimic human imperfection.
 *   <li><b>Momentum:</b> Velocity preservation with friction/braking near the target.
 * </ul>
 *
 * <p>The implementation has been tuned for standard simulation (approx. 60Hz internal logic) to
 * ensure compatibility with standard client ticking rates while maintaining the statistical
 * properties of the original algorithm.
 *
 * <p>Original Algorithm by <a href=
 * "https://ben.land/post/2021/04/25/windmouse-human-mouse-movement/">BenLand100</a>. Tweaked
 * "WindMouse2" implementation by <a href=
 * "https://dreambot.org/forums/index.php?/topic/21147-windmouse-custom-mouse-movement-algorithm/">holic</a>.
 * Adapted for ChromaScape by StaticSweep.
 */
public class WindMouse {

  private final Random random = new Random();

  /**
   * Moves the mouse from a starting point to a destination using the WindMouse physics model.
   *
   * <p>This method selects a speed profile and delegates to the internal physics engine.
   *
   * @param start The current coordinates of the mouse cursor.
   * @param target The destination coordinates.
   * @param speedProfile A string constant determining movement characteristics ("slow", "medium",
   *     "fast"). Defaults to "medium" if the profile is unrecognized.
   * @param onMove A {@link Consumer} that accepts a {@link Point} for every step of the path. This
   *     is typically used to trigger the actual hardware or robot input.
   */
  public void move(Point start, Point target, String speedProfile, Consumer<Point> onMove) {
    // defaults (Tuned for ~60Hz update rate)
    // Speed: Controls loop frequency (higher = lower sleep times).
    // Gravity/Wind: Forces applied per loop iteration.
    double mouseSpeed = 30;
    double mouseGravity = 4.5;
    double mouseWind = 1.5;

    // Adjust parameters based on profile
    switch (speedProfile.toLowerCase()) {
      case "slow" -> {
        mouseSpeed = 20;
        mouseGravity = 5.0;
        mouseWind = 1.0;
      }
      case "fast" -> {
        mouseSpeed = 50;
        mouseGravity = 6.0;
        mouseWind = 2.0;
      }
      case "medium", "default" -> {
        // Keeps defaults
      }
    }

    windMouse2(start, target, mouseGravity, mouseWind, mouseSpeed, onMove);
  }

  /**
   * Orchestrates the movement logic, optionally generating an intermediate waypoint for
   * long-distance moves.
   *
   * <p>If the distance is greater than 250 pixels, there is a 50% chance an intermediate point will
   * be generated to simulate a human "correction arc" or two-stage movement.
   *
   * @param start The starting point.
   * @param target The final destination.
   * @param gravity The gravitational pull towards the target.
   * @param wind The magnitude of random perturbations.
   * @param speed The timing speed factor.
   * @param onMove The callback for cursor updates.
   */
  private void windMouse2(
      Point start,
      Point target,
      double gravity,
      double wind,
      double speed,
      Consumer<Point> onMove) {

    // Random intermediate point for long distances (simulates human "arc" or
    // "correction")
    Point intermediate =
        (distance(target, start) > 250 && random.nextInt(2) == 1)
            ? randomPoint(target, start)
            : null;

    if (intermediate != null) {
      windMouseImpl(
          start.x,
          start.y,
          intermediate.x,
          intermediate.y,
          gravity,
          wind,
          speed,
          random.nextInt(10, 25),
          onMove);
      // Small pause between "stages" of movement to look natural
      sleepPrecise(random.nextInt(1, 150));
      start = intermediate; // Continue from intermediate
    }

    // Move to final target
    windMouseImpl(
        start.x, start.y, target.x, target.y, gravity, wind, speed, random.nextInt(10, 25), onMove);
  }

  /**
   * The core WindMouse physics simulation loop.
   *
   * <p>This method runs a loop that continuously calculates velocity vectors based on gravity
   * (distance to target) and wind (random noise). It applies these vectors to the current position
   * and triggers the {@code onMove} callback.
   *
   * @param xs Current X position.
   * @param ys Current Y position.
   * @param xe Target X position.
   * @param ye Target Y position.
   * @param gravity The strength of the pull towards the target.
   * @param wind The strength of the random wind forces.
   * @param speed Controls the sleep duration between steps.
   * @param targetArea The radius (in pixels) around the target where "braking" logic begins.
   * @param onMove The callback to execute when the integer coordinates change.
   */
  private void windMouseImpl(
      double xs,
      double ys,
      double xe,
      double ye,
      double gravity,
      double wind,
      double speed,
      double targetArea,
      Consumer<Point> onMove) {

    double dist, veloX = 0, veloY = 0, windX = 0, windY = 0;

    // Pre-calculated square roots for vector normalization
    double sqrt2 = Math.sqrt(2);
    double sqrt3 = Math.sqrt(3);
    double sqrt5 = Math.sqrt(5);

    int tDist = (int) distance(new Point((int) xs, (int) ys), new Point((int) xe, (int) ye));
    long t = System.currentTimeMillis() + 10000; // 10-second timeout safety

    // Loop until we are within 3 pixels.
    // Stopping at 3px prevents the "micro-orbiting" physics glitch where gravity
    // causes the cursor to overshoot and circle the target pixel indefinitely.
    while ((dist = Math.hypot((xs - xe), (ys - ye))) >= 3) {
      if (System.currentTimeMillis() > t) break;

      // Cap wind force so it doesn't exceed the remaining distance
      wind = Math.min(wind, dist);

      // Adaptive step size based on total distance
      long d = (Math.round((Math.round(((double) (tDist))) * 0.3)) / 7);
      if (d > 20) d = 20;
      if (d < 5) d = 5;

      // Occasional random slowdown
      if (random.nextInt(6) == 0) {
        d = 2;
      }

      // Max step calculation.
      // Multiplier set to 1.5 (Standard WindMouse).
      // Since we run at 60Hz (half the 120Hz rate), we need to cover twice the
      // distance per frame to preserve the visual speed.
      double maxStep = (Math.min(d, Math.round(dist))) * 1.5;

      if (dist >= targetArea) {
        // Apply normal wind
        int windRange = (int) (Math.round(wind) * 2) + 1;
        windX = (windX / sqrt3) + ((random.nextInt(windRange) - wind) / sqrt5);
        windY = (windY / sqrt3) + ((random.nextInt(windRange) - wind) / sqrt5);
      } else {
        // Short range (Entering Target Area):
        // Dampen wind (divide by sqrt2)
        windX = (windX / sqrt2);
        windY = (windY / sqrt2);

        // (Friction)
        // Multiplier 0.64 allows velocity to decay naturally over the standard frame count.
        // (Previously 0.80 at 120Hz; 0.80^2 approx 0.64 for 60Hz).
        veloX *= 0.64;
        veloY *= 0.64;
      }

      // Apply forces to velocity
      veloX += windX + gravity * (xe - xs) / dist;
      veloY += windY + gravity * (ye - ys) / dist;

      // Cap velocity at maxStep
      if (Math.hypot(veloX, veloY) > maxStep) {
        maxStep = ((maxStep / 2) < 1) ? 2 : maxStep;
        double randomDist = (maxStep / 2) + random.nextInt((int) (Math.round(maxStep) / 2));
        double veloMag = Math.sqrt(((veloX * veloX) + (veloY * veloY)));
        veloX = (veloX / veloMag) * randomDist;
        veloY = (veloY / veloMag) * randomDist;
      }

      // Update position
      int lastX = ((int) (Math.round(xs)));
      int lastY = ((int) (Math.round(ys)));
      xs += veloX;
      ys += veloY;

      // Only fire callback if the integer pixel coordinate actually changed
      if ((lastX != Math.round(xs)) || (lastY != Math.round(ys))) {
        Point newP = new Point((int) Math.round(xs), (int) Math.round(ys));
        if (onMove != null) onMove.accept(newP);
      }

      // Sleep calculation
      // Logic yields ~10-20ms sleeps, targeting standard 60Hz.
      // (Multiplier increased from 6 to 12 to double sleep times).
      int w = random.nextInt((int) (Math.round(100.0 / speed))) * 12;
      if (w < 10) {
        w = 10;
      }

      w = (int) Math.round(w * 0.9);
      sleepPrecise(w);
    }

    // Instantly bridge the last <3 pixels to ensure pixel-perfect accuracy.
    if ((Math.round(xe) != Math.round(xs)) || (Math.round(ye) != Math.round(ys))) {
      Point finalP = new Point((int) Math.round(xe), (int) Math.round(ye));
      if (onMove != null) onMove.accept(finalP);
    }
  }

  /**
   * Executes a high-precision sleep using a hybrid approach.
   *
   * <p>Standard {@link Thread#sleep(long)} is too coarse (~15ms resolution on Windows) for smooth
   * mouse movement. Pure busy-waiting burns 100% CPU.
   *
   * <p>This method parks the thread (yields CPU) for the majority of the duration, then switches to
   * a busy-wait spin loop for the final millisecond to ensure sub-millisecond precision.
   *
   * @param millis The duration to sleep in milliseconds.
   */
  private void sleepPrecise(long millis) {
    long end = System.nanoTime() + millis * 1_000_000L;
    long timeLeft = end - System.nanoTime();

    // If we have more than 2ms, we can afford to park the thread to save CPU.
    // We wake up 1ms early to spin for the final precision.
    while (timeLeft > 2_000_000L) {
      parkNanos(timeLeft - 1_000_000L);
      timeLeft = end - System.nanoTime();
    }

    // Busy-wait for the final <1ms
    while (System.nanoTime() < end) {
      try {
        // Java 9+ hint to CPU to optimize power consumption during spin-waits
        java.lang.Thread.onSpinWait();
      } catch (NoSuchMethodError e) {
        // Fallback for older JDKs (implicitly just busy-waits)
      }
    }
  }

  /**
   * Calculates distance between 2 points.
   *
   * @param p1 The first point.
   * @param p2 The second point.
   * @return pixel perfect distance.
   */
  private double distance(Point p1, Point p2) {
    return Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));
  }

  /**
   * Get a random point between 2 points.
   *
   * @param p1 The first point.
   * @param p2 The second point.
   * @return The random point.
   */
  private Point randomPoint(Point p1, Point p2) {
    int randomX = (int) randomPointBetween(p1.x, p2.x);
    int randomY = (int) randomPointBetween(p1.y, p2.y);
    return new Point(randomX, randomY);
  }

  /**
   * Generates a pseudo-random floating-point value between two bounds.
   *
   * <p>This utility is used to calculate randomized intermediate coordinates (waypoints) when
   * generating the mouse path. It handles both positive and negative directions automatically.
   *
   * @param corner1 The first boundary (e.g., the starting coordinate).
   * @param corner2 The second boundary (e.g., the target coordinate).
   * @return A random float value falling between {@code corner1} and {@code corner2}. If both
   *     bounds are equal, returns that value immediately to avoid processing.
   */
  private float randomPointBetween(float corner1, float corner2) {
    if (corner1 == corner2) {
      return corner1;
    }
    float delta = corner2 - corner1;
    float offset = random.nextFloat() * delta;
    return corner1 + offset;
  }
}

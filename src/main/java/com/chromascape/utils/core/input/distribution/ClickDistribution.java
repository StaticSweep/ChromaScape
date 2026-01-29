package com.chromascape.utils.core.input.distribution;

import java.awt.Point;
import java.awt.Rectangle;
import java.security.SecureRandom;
import org.apache.commons.math3.distribution.MultivariateNormalDistribution;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;

/**
 * Utility class for generating biased, Gaussian-distributed click points within a rectangular UI
 * region.
 *
 * <p>Instead of uniformly sampling click coordinates, this utility uses a {@link
 * MultivariateNormalDistribution} centered within the given rectangle. This approach simulates
 * human-like behavior by favoring points near the center while still allowing edge hits.
 */
public class ClickDistribution {

  /** Shared random generator with a secure, non-deterministic seed. */
  private static final RandomGenerator rng = new MersenneTwister(new SecureRandom().nextLong());

  /**
   * Generates a pseudo-random {@link Point} within the specified {@link Rectangle}, following a 2D
   * normal (Gaussian) distribution biased toward the center using internal heuristics.
   *
   * <p>The standard deviation is dynamically adjusted based on the rectangle's size to prevent
   * excessive out-of-bounds sampling on small targets.
   *
   * @param rect the rectangular region to sample from
   * @return a valid Point within {@code rect} with center-biased Gaussian randomness
   */
  public static Point generateRandomPoint(Rectangle rect) {
    if (isTooSmall(rect)) {
      return getCenter(rect);
    }

    // Calculate sigma based on internal heuristic
    double stdDevX = rect.width / deviation(rect.getWidth());
    double stdDevY = rect.height / deviation(rect.getHeight());

    return samplePoint(rect, stdDevX, stdDevY);
  }

  /**
   * Generates a pseudo-random {@link Point} within the specified {@link Rectangle}, following a 2D
   * normal (Gaussian) distribution with a custom tightness factor.
   *
   * <p>The {@code tightness} parameter controls the spread of the distribution. It acts as the
   * divisor for the rectangle's dimensions when calculating standard deviation.
   *
   * <ul>
   *   <li><b>High Tightness (> 15.0):</b> Very focused in the center. Useful for Ground items.
   *   <li><b>Low Tightness (< 3.0):</b> Broad spread. High probability of points near edges.
   * </ul>
   *
   * @param rect the rectangular region to sample from
   * @param tightness the factor by which to divide the dimension to get sigma. Must be positive.
   * @return a valid Point within {@code rect}
   * @throws IllegalArgumentException if tightness is less than or equal to zero
   */
  public static Point generateRandomPoint(Rectangle rect, double tightness) {
    if (tightness <= 0) {
      throw new IllegalArgumentException("Tightness factor must be greater than 0");
    }

    if (isTooSmall(rect)) {
      return getCenter(rect);
    }

    double stdDevX = rect.width / tightness;
    double stdDevY = rect.height / tightness;

    return samplePoint(rect, stdDevX, stdDevY);
  }

  /** Internal helper to execute the sampling logic given specific standard deviations. */
  private static Point samplePoint(Rectangle rect, double stdDevX, double stdDevY) {
    MultivariateNormalDistribution mnd = getMultivariateNormalDistribution(rect, stdDevX, stdDevY);

    Point randomPoint;
    do {
      double[] sample = mnd.sample();
      randomPoint = new Point((int) Math.round(sample[0]), (int) Math.round(sample[1]));
    } while (!rect.contains(randomPoint)); // Resample until within bounds

    return randomPoint;
  }

  /**
   * Constructs a {@link MultivariateNormalDistribution} centered within the given rectangle using
   * explicit standard deviations.
   *
   * @param rect the rectangle to derive center from
   * @param stdDevX the standard deviation for the X axis
   * @param stdDevY the standard deviation for the Y axis
   * @return a 2D normal distribution configured with the provided spread
   */
  private static MultivariateNormalDistribution getMultivariateNormalDistribution(
      Rectangle rect, double stdDevX, double stdDevY) {

    double meanX = rect.getX() + rect.getWidth() / 2.0;
    double meanY = rect.getY() + rect.getHeight() / 2.0;
    double[] mean = {meanX, meanY};

    double[][] covariance = {
      {stdDevX * stdDevX, 0}, // No correlation between X and Y
      {0, stdDevY * stdDevY}
    };

    return new MultivariateNormalDistribution(ClickDistribution.rng, mean, covariance);
  }

  /**
   * Heuristic used to adjust the spread of the Gaussian distribution based on rectangle size.
   *
   * <p>This prevents excessive sampling outside of bounds by reducing standard deviation for small
   * targets.
   *
   * @param length the width or height (in pixels) of a side of the rectangle
   * @return a divisor used to calculate standard deviation
   */
  private static double deviation(double length) {
    if (length >= 50) {
      return 4.0;
    } else if (length >= 25) {
      return 7.0;
    } else if (length >= 15) {
      return 8.0;
    }
    return 9.0;
  }

  /**
   * Helper method to justify whether a rectangle is too small to conduct sampling.
   *
   * @param rect Rectangle to test.
   * @return {@code true} if too small, else {@code false}.
   */
  private static boolean isTooSmall(Rectangle rect) {
    return rect.width < 5 || rect.height < 5;
  }

  /**
   * Helper method to return the center of a given Rectangle.
   *
   * @param rect The rectangle to return the center of.
   * @return The {@link Point} center of the given Rectangle.
   */
  private static Point getCenter(Rectangle rect) {
    return new Point((int) rect.getCenterX(), (int) rect.getCenterY());
  }
}

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
 * MultivariateNormalDistribution} centered within the given rectangle. This approach simulates more
 * human-like behavior by favoring points near the center while still allowing edge hits.
 *
 * <p>The standard deviation of the distribution is dynamically adjusted based on rectangle size to
 * avoid excessive clipping and out-of-bound samples in small targets.
 */
public class ClickDistribution {

  // Shared random generator with a secure, non-deterministic seed
  private static final RandomGenerator rng = new MersenneTwister(new SecureRandom().nextLong());

  /**
   * Generates a pseudo-random {@link Point} within the specified {@link Rectangle}, following a 2D
   * normal (Gaussian) distribution biased toward the center of the rectangle.
   *
   * <p>If the rectangle is smaller than 5x5 pixels, the center point is returned instead.
   * Otherwise, the method repeatedly samples until it finds a point that falls within bounds.
   *
   * @param rect the rectangular region to sample from
   * @return a valid Point within {@code rect} with center-biased Gaussian randomness
   */
  public static Point generateRandomPoint(Rectangle rect) {
    if (rect.width < 5 || rect.height < 5) {
      // Return center point if the area is too small for Gaussian sampling
      return new Point((int) rect.getCenterX(), (int) rect.getCenterY());
    }

    MultivariateNormalDistribution mnd = getMultivariateNormalDistribution(rect);

    Point randomPoint;
    do {
      double[] sample = mnd.sample();
      randomPoint = new Point((int) Math.round(sample[0]), (int) Math.round(sample[1]));
    } while (!rect.contains(randomPoint)); // Resample until within bounds

    return randomPoint;
  }

  /**
   * Constructs a {@link MultivariateNormalDistribution} centered within the given rectangle, with
   * standard deviations dynamically derived from rectangle dimensions.
   *
   * @param rect the rectangle to derive center and spread from
   * @return a 2D normal distribution representing click likelihood within {@code rect}
   */
  private static MultivariateNormalDistribution getMultivariateNormalDistribution(Rectangle rect) {
    double meanX = rect.getX() + rect.getWidth() / 2.0;
    double meanY = rect.getY() + rect.getHeight() / 2.0;
    double[] mean = {meanX, meanY};

    double stdDevX = rect.width / deviation(rect.getWidth());
    double stdDevY = rect.height / deviation(rect.getHeight());

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
}

package com.chromascape.utils.core.input.distribution;

import org.apache.commons.math3.distribution.MultivariateNormalDistribution;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;

import java.awt.Point;
import java.awt.Rectangle;
import java.security.SecureRandom;

public class ClickDistribution {

    private static final RandomGenerator rng = new MersenneTwister(new SecureRandom().nextLong());

    public static Point generateRandomPoint(Rectangle rect) {

        if (rect.width < 5 || rect.height < 5) {
            // Just return the center point if too small to sample meaningfully
            return new Point((int)rect.getCenterX(), (int)rect.getCenterY());
        }

        MultivariateNormalDistribution mnd = getMultivariateNormalDistribution(rect);

        Point randomPoint;
        do {
            double[] sample = mnd.sample();
            randomPoint = new Point((int) Math.round(sample[0]), (int) Math.round(sample[1]));
        } while (!rect.contains(randomPoint));

        return randomPoint;
    }

    private static MultivariateNormalDistribution getMultivariateNormalDistribution(Rectangle rect) {
        double meanX = rect.getX() + rect.getWidth() / 2.0;
        double meanY = rect.getY() + rect.getHeight() / 2.0;
        double[] mean = {meanX, meanY};

        double stdDevX = rect.width / deviation(rect.getWidth());
        double stdDevY = rect.height / deviation(rect.getHeight());

        double[][] covariance = {
                {stdDevX * stdDevX, 0},
                {0, stdDevY * stdDevY}
        };

        return new MultivariateNormalDistribution(ClickDistribution.rng, mean, covariance);
    }

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

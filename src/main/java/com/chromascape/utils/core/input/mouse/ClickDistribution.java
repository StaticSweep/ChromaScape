package com.chromascape.utils.core.input.mouse;

import org.apache.commons.math3.distribution.MultivariateNormalDistribution;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;

import java.awt.Point;
import java.awt.Rectangle;
import java.security.SecureRandom;

public class ClickDistribution {

    private final RandomGenerator rng;

    public ClickDistribution() {
        this.rng = new MersenneTwister(new SecureRandom().nextLong());
    }

    public Point generateRandomPoint(Rectangle rect) {
        MultivariateNormalDistribution mnd = getMultivariateNormalDistribution(rect, rng);

        Point randomPoint;
        do {
            double[] sample = mnd.sample();
            randomPoint = new Point((int) Math.round(sample[0]), (int) Math.round(sample[1]));
        } while (!rect.contains(randomPoint));

        return randomPoint;
    }

    private MultivariateNormalDistribution getMultivariateNormalDistribution(Rectangle rect, RandomGenerator rng) {
        double meanX = rect.getX() + rect.getWidth() / 2.0;
        double meanY = rect.getY() + rect.getHeight() / 2.0;
        double[] mean = {meanX, meanY};

        double stdDevX = rect.width / deviation(rect.getWidth());
        double stdDevY = rect.height / deviation(rect.getHeight());

        double[][] covariance = {
                {stdDevX * stdDevX, 0},
                {0, stdDevY * stdDevY}
        };

        return new MultivariateNormalDistribution(rng, mean, covariance);
    }

    private double deviation(double length) {
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

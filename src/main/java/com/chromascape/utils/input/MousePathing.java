package com.chromascape.utils.input;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

public class MousePathing {

    Random rand = new Random();

    private static final DoubleUnaryOperator easeOutQuintic = t -> 1 - Math.pow(1 - t, 5);

    private final int screenWidth;

    private final int screenHeight;

    public MousePathing(int screenWidth, int screenHeight) {

        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    private Point clampToScreen(final Point p) {
        int x = Math.max(0, Math.min(p.x, screenWidth - 1));
        int y = Math.max(0, Math.min(p.y, screenHeight - 1));
        return new Point(x, y);
    }

    public java.util.List<Point> generateCubicBezierPath(final Point p0, final Point p3, final String speed) {

        int distance = calculateDistance(p0, p3);
        int steps = calculateSteps(distance, speed);

        // Calculate the vector from the start point (p0) to the end point (p3)
        double dx = p3.x - p0.x;
        double dy = p3.y - p0.y;

        // Compute the length (magnitude) of the vector
        double len = Math.sqrt(dx * dx + dy * dy);

        // Compute a unit vector perpendicular to the direction vector (dx, dy)
        // This will be used to offset control points away from the straight line
        double ux = -dy / len;
        double uy = dx / len;

        // Choose a random arc offset magnitude to determine how far the curve bends
        int offset = rand.nextInt(5, 15);

        // The direction that the mouse will arc (random)
        int direction = rand.nextBoolean() ? 1 : -1;  // Randomly +1 or -1

        // Calculate the final perpendicular vector to be applied to control points
        double nx = direction * offset * ux;
        double ny = direction * offset * uy;

        // Pick two random normalized positions along the line (t values between 0 and 1)
        // These determine where along the path the control points are placed
        double t1 = rand.nextDouble(0.2, 0.3);
        double t2 = rand.nextDouble(0.6, 0.7);

        // Calculating where p1 will be on a straight line
        double p1x = p0.x + t1 * dx;
        double p1y = p0.y + t1 * dy;

        // Calculating where p2 will be on a straight line
        double p2x = p0.x + t2 * dx;
        double p2y = p0.y + t2 * dy;

        // Calculating the offset inputs based on distance
        int origin = calculateOffset(calculateDistance(p0, p3));

        // Offsetting the points so they're not on the line
        double offset1 = rand.nextInt(origin + 5, origin + 8) * (rand.nextBoolean() ? 1 : -1);
        double offset2 = rand.nextInt(origin, origin + 5) * (rand.nextBoolean() ? 1 : -1);

        // Apply the perpendicular offset to create final control points, and clamp to screen bounds
        Point p1 = clampToScreen(new Point((int)(p1x + offset1 * nx), (int)(p1y + offset1 * ny)));
        Point p2 = clampToScreen(new Point((int)(p2x + offset2 * nx), (int)(p2y + offset2 * ny)));

        // Initialise the list of points
        List<Point> path = new ArrayList<>();

        for (int i = 0; i < steps; i++) {
            double tRaw = i / (double) (steps - 1);

            // Apply easing to t to simulate more natural speed variation (starts fast, slows down)
            double t = easeOutQuintic.applyAsDouble(tRaw);

            // Calculate the cubic Bézier point at parameter t
            double u = 1 - t;
            double bCx = Math.pow(u, 3) * p0.x
                    + 3 * Math.pow(u, 2) * t * p1.x
                    + 3 * u * Math.pow(t, 2) * p2.x
                    + Math.pow(t, 3) * p3.x;
            double bCy = Math.pow(u, 3) * p0.y
                    + 3 * Math.pow(u, 2) * t * p1.y
                    + 3 * u * Math.pow(t, 2) * p2.y
                    + Math.pow(t, 3) * p3.y;

            // Round to integer pixel coordinates and add to the path
            path.add(new Point((int) Math.round(bCx), (int) Math.round(bCy)));
        }

        return path;
    }

    private int calculateOffset(final int distance) {
        if(distance >= 600){
            return 12;
        } else if (distance >= 300) {
            return 5;
        } else {
            return 0;
        }
    }

    private int calculateSteps(final int distance, final String speed) {
        // tuning factor
        int scale = switch (speed) {
            case "slow" -> 1;
            case "medium" -> 2;
            case "fast" -> 4;
            default -> throw new IllegalStateException("Unexpected value: " + speed);
        };

        // divide distance by scale to get step count
        int steps = distance / scale;

        // at least 1 step, to avoid zero
        return Math.max(1, steps);
    }

    private int calculateDistance(final Point p0, final Point p2) {
        double vx = p2.x - p0.x;
        double vy = p2.y - p0.y;

        double distance = Math.sqrt(vx * vx + vy * vy);
        return Math.toIntExact(Math.round(distance));
    }

}

package com.chromascape.utils.input;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleBinaryOperator;

public class MousePathing {

    private final Random random = new Random();

    private final DoubleBinaryOperator easeOut = (t, exponent) -> 1 - Math.pow(1 - t, exponent);

    private final int screenWidth;

    private final int screenHeight;

    /**
     * Uses the start and end points of a required mouse movement.
     * To calculate a human like mouse path.
     * With variable arcs, speed, and easing.
     * The end result of this class is a list of points which should be iterated through to produce a path.
     *
     * @param screenWidth Width of the screen.
     * @param screenHeight Height of the screen.
     */
    public MousePathing(int screenWidth, int screenHeight) {

        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    /**
     * If instantiated as a point it will always stay within the window bounds.
     *
     * @param p The point to clamp.
     * @return The clamped position.
     */
    private Point clampToScreen(final Point p) {
        int x = Math.max(0, Math.min(p.x, screenWidth - 1));
        int y = Math.max(0, Math.min(p.y, screenHeight - 1));
        return new Point(x, y);
    }

    /**
     * Generates cubic Bézier mouse paths with dynamic easing and curvature distortion.
     * Useful for simulating human-like cursor movement.
     *
     * @param p0 The start point, should be the current mouse position.
     * @param p3 The end point, the destination.
     * @param speed How fast you want the mouse to move to the destination.
     * @return Returns a list of points (the path) which the mouse should follow.
     */
    public java.util.List<Point> generateCubicBezierPath(final Point p0, final Point p3, final String speed) {

        int distance = calculateDistance(p0, p3);
        int steps = calculateSteps(distance, speed);

        // The direction that the mouse will arc (random)
        int direction = random.nextBoolean() ? 1 : -1;  // Randomly +1 or -1

        // Calculating the offset inputs based on distance
        int[] originBound = calculateOffset(calculateDistance(p0, p3));

        // Offsetting the points so they're not on the line
        // The first offset (for the first curve) is increased to make the mouse path more varied overall
        double p1offset = random.nextInt(originBound[0], originBound[1] + 50) * (random.nextBoolean() ? 1 : -1);
        double p2offset = random.nextInt(originBound[0], originBound[1]) * (random.nextBoolean() ? 1 : -1);

        // Apply the perpendicular offset to create final control points, and clamp to screen bounds
        Point p1 = calculatePointAlongPath(p0, p3, 0.2, 0.3, p1offset, direction);
        Point p2 = calculatePointAlongPath(p0, p3, 0.6, 0.7, p2offset, direction);

        // Initialise the list of points
        List<Point> path = new ArrayList<>();

        for (int i = 0; i < steps; i++) {
            double tRaw = i / (double) (steps - 1);

            // Apply easing to t to simulate more natural speed variation (starts fast, slows down)
            double t = easeOut.applyAsDouble(tRaw, calculateEasing(distance));;

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
            Point next = new Point((int) Math.round(bCx), (int) Math.round(bCy));
            if (path.isEmpty() || !path.get(path.size() - 1).equals(next)) {
                path.add(next);
            }
        }

        return path;
    }

    /**
     * Creates a point between two different points with an offset perpendicular to the direction vector.
     * locOrigin and locBound refer to how far along the path the point should appear e.g, 0.5 is in the centre-
     * Of the two points.
     *
     * @param p0 The start point, should be the current mouse position.
     * @param p3 The end point, the destination.
     * @param locOrigin T value between 0 and 1, the lower bound at which the point could be
     * @param locBound T value between 0 and 1, the upper bound at which the point could be.
     * @param pointOffset How far away from the straight line the point should be.
     * @param direction Which direction the point should be (-1 or 1).
     * @return The point.
     */
    public Point calculatePointAlongPath(
            final Point p0,
            final Point p3,
            final double locOrigin,
            final double locBound,
            final double pointOffset,
            final int direction) {

        // Calculate the vector from the start point (p0) to the end point (p3)
        double dx = p3.x - p0.x;
        double dy = p3.y - p0.y;

        // Compute the length (magnitude) of the vector
        double len = Math.sqrt(dx * dx + dy * dy);

        // Compute a unit vector perpendicular to the direction vector (dx, dy)
        // This will be used to offset control points away from the straight line
        double ux = -dy / len;
        double uy = dx / len;

        // Calculate the final perpendicular vector to be applied to control points
        double nx = direction * ux;
        double ny = direction * uy;

        // Picks a random normalized position along the line (t value between 0 and 1)
        // This determines where along the path the control points are placed
        double t1 = random.nextDouble(locOrigin, locBound);

        // Calculating where p1 will be on a straight line
        double p1x = p0.x + t1 * dx;
        double p1y = p0.y + t1 * dy;

        // Apply the perpendicular offset to create final control points, and clamp to screen bounds
        return clampToScreen(new Point((int)(p1x + pointOffset * nx), (int)(p1y + pointOffset * ny)));
    }

    /**
     * Used to calculate the curve offsets to make the mouse curve more consistently.
     * At different distances.
     *
     * @param distance The normalised distance between start and end points.
     * @return The curve factor origin (left) and bound (right).
     */
    private int[] calculateOffset(final int distance) {
        if (distance >= 600) {
            return new int[]{160, 220};
        } else if (distance >= 300) {
            return new int[]{50, 80};
        } else if (distance >= 200) {
            return new int[]{10, 30};
        } else {
            return new int[]{0, 10};
        }
    }

    /**
     * Calculates the steps needed to reach the destination.
     * The amount of steps is the amount of points along the mouse's path.
     * More steps = less speed.
     *
     * @param distance The normalised distance between start and end points.
     * @param speed The speed you want the mouse to travel "slow", "medium" "fast".
     * @return The number of steps
     */
    private int calculateSteps(final int distance, final String speed) {
        // tuning factor
        double scale = switch (speed) {
            case "slow" -> 0.5;
            case "medium" -> 1.5;
            case "fast" -> 2.0;
            case "fastest" -> 2.5;
            default -> throw new IllegalStateException("Unexpected value: " + speed);
        };

        // divide distance by scale to get step count
        int steps = Math.toIntExact(Math.round(distance / scale));

        // at least 1 step, to avoid zero
        return Math.max(1, steps);
    }

    /**
     * Calculates the normalised distance between two points on screen.
     *
     * @param p0 The start point, should be the current mouse position.
     * @param p3 The end point, the destination.
     * @return The pixel distance between the points.
     */
    private int calculateDistance(final Point p0, final Point p3) {
        double vx = p3.x - p0.x;
        double vy = p3.y - p0.y;

        double distance = Math.sqrt(vx * vx + vy * vy);
        return Math.toIntExact(Math.round(distance));
    }

    /**
     * Used to calculate the easing factor based on how far the mouse will travel.
     * Higher easing factor = harder stop.
     * Lower easing factor = slower stop.
     *
     * @param distance The normalised distance between start and end points.
     * @return The easing factor.
     */
    private double calculateEasing(final int distance) {
        if (distance >= 1200) {
            return 16;
        } else if (distance >= 1000) {
            return 12;
        } else if (distance >= 800) {
            return 10;
        } else if (distance >= 600) {
            return 8;
        } else if (distance >= 400) {
            return 6;
        } else if (distance >= 200) {
            return 5;
        } else {
            return 4;
        }
    }
}
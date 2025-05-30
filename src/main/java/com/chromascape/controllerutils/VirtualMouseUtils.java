package com.chromascape.controllerutils;

import javax.swing.SwingUtilities;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

public class VirtualMouseUtils {

    private Point currentPosition = new Point(0,0);

    private final WindowsInputNative nativeMouse;

    MouseOverlay overlay;

    Random rand = new Random();

    private static final DoubleUnaryOperator easeOutQuad = t -> 1 - Math.pow(1 - t, 3);

    public VirtualMouseUtils() {
        nativeMouse = new WindowsInputNative(55724);
        overlay = new MouseOverlay();
    }

    public void moveTo(Point target, String speed) throws InterruptedException {
        List<Point> path = generateBezierPath(currentPosition, target, calculateSteps(currentPosition, target, speed));
        System.out.println(calculateSteps(currentPosition, target, speed));
        for (Point p : path) {
//            nativeMouse.moveMouse(p.x, p.y);
            currentPosition = p;
            SwingUtilities.invokeLater(() -> overlay.setMousePoint(p));
            Thread.sleep(1); // simulate 1000hz polling rate
        }
    }

    public void leftClick() throws InterruptedException {
        nativeMouse.clickLeft(currentPosition.x, currentPosition.y);
    }

    public void rightClick() throws InterruptedException {
        nativeMouse.clickRight(currentPosition.x, currentPosition.y);
    }

    private List<Point> generateBezierPath(Point p0, Point p2, int steps) {
        // Calculating the mid-point using the start and end points
        double mx = (double) (p2.x + p0.x) / 2;
        double my = (double) (p2.y + p0.y) / 2;

        // Calculating the vector using the start and end points
        double vx = p2.x - p0.x;
        double vy = p2.y - p0.y;

        // Calculating the length using the vector
        double len = Math.sqrt(Math.pow(-vy, 2) + Math.pow(vx, 2));

        // Calculating the normalised length using the length and vector
        double nx = -vy / (double) len;
        double ny = vx / (double) len;

        // The offset for control
        int offset = rand.nextInt(400, 600);

        // The direction that the mouse will arc
        int direction = rand.nextBoolean() ? 1 : -1;  // Randomly +1 or -1

        // Calculating control using the proposed control location (mid-point for now)
        double cx = mx + direction * offset * nx;
        double cy = my + direction * offset * ny;

        // Creating a list of points to add to later
        List<Point> path = new ArrayList<>();

        for (int i = 0; i < steps; i++) {
            double tRaw = i / (double) (steps - 1);
            // Apply easing function to t for more natural spacing (like speed easing)
            double t = easeOutQuad.applyAsDouble(tRaw);

            // Quadratic Bezier formula
            double bx = (1 - t) * (1 - t) * p0.x + 2 * (1 - t) * t * cx + t * t * p2.x;
            double by = (1 - t) * (1 - t) * p0.y + 2 * (1 - t) * t * cy + t * t * p2.y;

            path.add(new Point((int) Math.round(bx), (int) Math.round(by)));
        }

        return path;
    }

    private int calculateSteps(Point p0, Point p2, String speed) {
        double vx = p2.x - p0.x;
        double vy = p2.y - p0.y;

        double distance = Math.sqrt(vx * vx + vy * vy);
        // tuning factor
        int scale = switch (speed) {
            case "slow" -> 2;
            case "medium" -> 6;
            case "fast" -> 8;
            case "faster" -> 12;
            default -> throw new IllegalStateException("Unexpected value: " + speed);
        };

        // divide distance by scale to get step count
        int steps = (int) Math.round(distance / scale);

        // at least 1 step, to avoid zero
        return Math.max(1, steps);
    }

}

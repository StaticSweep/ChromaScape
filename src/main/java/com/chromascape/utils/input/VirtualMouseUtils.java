package com.chromascape.utils.input;

import javax.swing.SwingUtilities;
import java.awt.Point;
import java.util.List;
import java.util.Random;

public class VirtualMouseUtils {

    private Point currentPosition = new Point(0,0);

    private final MouseOverlay overlay;

    private final WindowsInputNative nativeMouse;

    private final Random random = new Random();

    private final MousePathing mousePathing;

    /**
     * The orchestrator for all inputs mouse related.
     * provides human like mouse movement, clicking and a little overlay so you can see where it is.
     *
     * @param nativeMouse The operating system dependant utility to send low level mouse inputs.
     *                    This is how we can still use the system cursor separately while this mouse is active.
     * @param width Window width.
     * @param height Window height.
     */
    public VirtualMouseUtils(final WindowsInputNative nativeMouse, final int width, final int height) {
        this.nativeMouse = nativeMouse;
        overlay = new MouseOverlay();
        overlay.setSize(width, height);
        mousePathing = new MousePathing(width, height);
    }

    /**
     * Moves the mouse to the target location using a cubic Bézier curve to simulate a human.
     *
     * @param target The target point.
     * @param speed The speed at which to travel (slow, medium, fast, fastest).
     * @throws InterruptedException (If the mouse is interrupted it will throw this).
     */
    public void moveTo(final Point target, final String speed) throws InterruptedException {
        if (currentPosition.equals(target)) return;
        List<Point> path = mousePathing.generateCubicBezierPath(currentPosition, target, speed);
        for (Point p : path) {
//            nativeMouse.moveMouse(p.x, p.y);
            currentPosition = p;
            SwingUtilities.invokeLater(() -> overlay.setMousePoint(p));
            Thread.sleep(1); // Ensures that the mouse doesn't teleport and for a consistent polling rate
        }
    }

    /**
     * Pauses before reaching the target, along the vector and recorrects afterward.
     * Very useful and human-like if moving to a faraway point very quickly.
     *
     * @param target The target point.
     * @param speed The speed at which to travel (slow, medium, fast, fastest).
     * @throws InterruptedException (If the mouse is interrupted it will throw this).
     */
    public void moveToPause(final Point target, final String speed) throws InterruptedException {
        if (currentPosition.equals(target)) return;
        int direction = random.nextBoolean() ? 1 : -1;  // Randomly +1 or -1
        Point pausePoint = mousePathing.calculatePointAlongPath(
                currentPosition,
                target,
                0.85,
                0.95,
                random.nextInt(50, 70),
                direction);
        moveTo(pausePoint, speed);
        Thread.sleep(random.nextInt(10, 20));
        moveTo(target, "medium");
    }

    /**
     * Overshoots the target and recorrects afterward.
     * Very useful and human-like if moving to a faraway point very quickly.
     *
     * @param target The target point.
     * @param speed The speed at which to travel (slow, medium, fast, fastest).
     * @throws InterruptedException (If the mouse is interrupted it will throw this).
     */
    public void moveToAndOvershoot(final Point target, final String speed) throws InterruptedException {
        if (currentPosition.equals(target)) return;
        int direction = random.nextBoolean() ? 1 : -1;  // Randomly +1 or -1
        Point pausePoint = mousePathing.calculatePointAlongPath(
                currentPosition,
                target,
                1.08,
                1.15,
                random.nextInt(50, 70),
                direction);
        moveTo(pausePoint, speed);
        moveTo(target, "medium");
    }

    /**
     * Clicks the left mouse button and jitters.
     */
    public void leftClick() {
        nativeMouse.clickLeft(currentPosition.x, currentPosition.y);
        nativeMouse.moveMouse(currentPosition.x, currentPosition.y);
        microJitter();
    }
    /**
     * Clicks the right mouse button and jitters.
     */

    public void rightClick() {
        nativeMouse.clickRight(currentPosition.x, currentPosition.y);
        nativeMouse.moveMouse(currentPosition.x, currentPosition.y);
        microJitter();
    }

    /**
     * Performs a small jitter or shake, seen often when clicking.
     */
    private void microJitter() {
        if (random.nextBoolean()) {
            currentPosition.translate(random.nextInt(-1, 2), random.nextInt(-1, 4));
            SwingUtilities.invokeLater(() -> overlay.setMousePoint(currentPosition));
            nativeMouse.moveMouse(currentPosition.x, currentPosition.y);
        }
    }
}

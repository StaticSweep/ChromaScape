package com.chromascape.utils.core.input.mouse;

import com.chromascape.utils.core.input.remoteinput.KInput;

import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Random;

public class VirtualMouseUtils {

    private Point currentPosition = new Point(0,0);

    private final MouseOverlay overlay;

    private final KInput kInput;

    private final Random random = new Random();

    private final MousePathing mousePathing;

    /**
     * The orchestrator for all inputs mouse related.
     * provides human like mouse movement, clicking and a little overlay so you can see where it is.
     *
     * @param kInput The operating system dependant utility to send low level mouse inputs.
     *                    This is how we can still use the system cursor separately while this mouse is active.
     * @param bounds Rectangle, containing the screen's bounds.
     */
    public VirtualMouseUtils(final KInput kInput, Rectangle bounds) {
        this.kInput = kInput;
        overlay = new MouseOverlay();
        overlay.setSize(bounds.width, bounds.height);
        overlay.setLocation(bounds.x, bounds.y);
        mousePathing = new MousePathing(bounds);
    }

    /**
     * Moves the mouse to the target location using a cubic Bézier curve to simulate a human.
     *
     * @param target The target point.
     * @param speed The speed at which to travel (slow, medium, fast, fastest).
     * @throws InterruptedException (If the mouse is interrupted).
     */
    public void moveTo(final Point target, final String speed) throws InterruptedException {
        if (currentPosition.equals(target)) return;
        List<Point> path = mousePathing.generateCubicBezierPath(currentPosition, target, speed);
        for (Point p : path) {
//            kInput.moveMouse(p.x, p.y);
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
                1.04,
                1.1,
                random.nextInt(50, 70),
                direction);
        moveTo(pausePoint, speed);
        moveTo(target, "medium");
    }

    /**
     * Clicks the left mouse button.
     */
    public void leftClick() {
        kInput.clickLeft(currentPosition.x, currentPosition.y);
        kInput.moveMouse(currentPosition.x, currentPosition.y);
        microJitter();
    }

    /**
     * Clicks the right mouse button.
     */
    public void rightClick() {
        kInput.clickRight(currentPosition.x, currentPosition.y);
        kInput.moveMouse(currentPosition.x, currentPosition.y);
        microJitter();
    }

    /**
     * Performs a middle mouse button event.
     *
     * @param eventType - 501 to press/ 502 to release.
     */
    public void middleClick(int eventType) {
        kInput.middleInput(currentPosition.x, currentPosition.y, eventType);
    }

    /**
     * Performs a small jitter or shake, seen often when clicking.
     */
    private void microJitter() {
        if (random.nextBoolean()) {
            currentPosition.translate(random.nextInt(-1, 2), random.nextInt(-1, 4));
            SwingUtilities.invokeLater(() -> overlay.setMousePoint(currentPosition));
            kInput.moveMouse(currentPosition.x, currentPosition.y);
        }
    }
}

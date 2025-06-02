package com.chromascape.utils.input;

import javax.swing.SwingUtilities;
import java.awt.Point;
import java.util.List;
import java.util.Random;

public class VirtualMouseUtils {

    private Point currentPosition = new Point(0,0);

    private final MouseOverlay overlay;

    private final WindowsInputNative nativeMouse;

    private final Random rand = new Random();

    private final MousePathing mousePathing;

    public VirtualMouseUtils(final WindowsInputNative nativeMouse, final int width, final int height) {
        this.nativeMouse = nativeMouse;
        overlay = new MouseOverlay();
        overlay.setSize(width, height);
        mousePathing = new MousePathing(width, height);
    }

    public void moveTo(final Point target, final String speed) throws InterruptedException {
        if (currentPosition.equals(target)) return;
        List<Point> path = mousePathing.generateCubicBezierPath(currentPosition, target, speed);
        for (Point p : path) {
            nativeMouse.moveMouse(p.x, p.y);
            currentPosition = p;
            SwingUtilities.invokeLater(() -> overlay.setMousePoint(p));
            Thread.sleep(1); // Ensures that the mouse doesn't teleport and for a consistent polling rate
        }
    }

    public void leftClick() {
        nativeMouse.clickLeft(currentPosition.x, currentPosition.y);
        nativeMouse.moveMouse(currentPosition.x, currentPosition.y);
        microJitter();
    }

    public void rightClick() {
        nativeMouse.clickRight(currentPosition.x, currentPosition.y);
        nativeMouse.moveMouse(currentPosition.x, currentPosition.y);
        microJitter();
    }

    private void microJitter() {
        if (rand.nextBoolean()) {
            currentPosition.translate(rand.nextInt(-1, 2), rand.nextInt(-1, 4));
            SwingUtilities.invokeLater(() -> overlay.setMousePoint(currentPosition));
            nativeMouse.moveMouse(currentPosition.x, currentPosition.y);
        }
    }
}

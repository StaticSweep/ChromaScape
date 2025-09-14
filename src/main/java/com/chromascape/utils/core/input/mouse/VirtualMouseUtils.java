package com.chromascape.utils.core.input.mouse;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.input.remoteinput.Kinput;
import com.chromascape.utils.core.screen.window.ScreenManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Random;
import javax.swing.SwingUtilities;

/**
 * The orchestrator for human-like virtual mouse behavior.
 *
 * <p>Provides movement, clicks, overshoots, pause-corrections, and overlay visuals without
 * hijacking the physical system cursor. Designed for use with remote input libraries like {@link
 * Kinput}, enabling low-level mouse event simulation.
 *
 * <p>Intended for scenarios requiring natural mouse movement patterns, such as automation that
 * mimics human interaction.
 */
public class VirtualMouseUtils {

  /** The current virtual mouse position. */
  private Point currentPosition;

  /** Semi-transparent visual overlay to show virtual mouse position. */
  private final MouseOverlay overlay;

  /** Interface for injecting low-level mouse events independently of system mouse. */
  private final Kinput kinput;

  private final Random random;

  private final MousePathing mousePathing;

  /**
   * The orchestrator for all inputs mouse related. provides human like mouse movement, clicking and
   * a little overlay so you can see where it is.
   *
   * @param kinput The operating system dependant utility to send low level mouse inputs. This is
   *     how we can still use the system cursor separately while this mouse is active.
   * @param bounds Rectangle, containing the screen's bounds.
   */
  public VirtualMouseUtils(final Kinput kinput, final Rectangle bounds) {
    this.kinput = kinput;
    overlay = new MouseOverlay();
    overlay.setSize(bounds.width, bounds.height);

    random = new Random();
    mousePathing = new MousePathing(bounds);

    // Randomize starting position within the client window
    int startX = bounds.x + random.nextInt(bounds.width);
    int startY = bounds.y + random.nextInt(bounds.height);
    currentPosition = new Point(startX, startY);

    // Place overlay at the same randomized position
    SwingUtilities.invokeLater(() -> overlay.setMousePoint(currentPosition));
    overlay.setLocation(bounds.x, bounds.y); // keep overlay frame aligned with window
  }

  /**
   * Moves the virtual mouse to the given location using a smooth cubic Bézier path. Intended to
   * replicate natural human motion.
   *
   * @param target The destination point on screen.
   * @param speed Speed profile: "slow", "medium", "fast", or "fastest".
   * @throws InterruptedException If movement is externally interrupted.
   */
  public void moveTo(final Point target, final String speed) throws InterruptedException {
    BaseScript.checkInterrupted();
    if (currentPosition.equals(target)) {
      return;
    }
    List<Point> path = mousePathing.generateCubicBezierPath(currentPosition, target, speed);
    for (Point p : path) {
      Point clientPoint = ScreenManager.toClientCoords(p);
      kinput.moveMouse(clientPoint.x, clientPoint.y);
      currentPosition = p;
      SwingUtilities.invokeLater(() -> overlay.setMousePoint(p));
      Thread.sleep(1); // Ensures that the mouse doesn't teleport and for a consistent polling rate
      // (approx 500hz polling rate)
    }
  }

  /**
   * Moves toward the target but pauses slightly before reaching it, then corrects to the final
   * destination. Simulates hesitation or tracking behavior. Very useful to mimic human behaviour if
   * moving to a faraway point with a lot of speed.
   *
   * @param target The destination point on screen.
   * @param speed Initial speed toward the pause point.
   * @throws InterruptedException If interrupted during movement.
   */
  public void moveToPause(final Point target, final String speed) throws InterruptedException {
    if (currentPosition.equals(target)) {
      return;
    }
    int direction = random.nextBoolean() ? 1 : -1; // Randomly +1 or -1
    Point pausePoint =
        mousePathing.calculatePointAlongPath(
            currentPosition, target, 0.85, 0.95, random.nextInt(50, 70), direction);
    moveTo(pausePoint, speed);
    Thread.sleep(random.nextInt(10, 20));
    moveTo(target, "medium");
  }

  /**
   * Moves slightly beyond the target before snapping back to it. Useful for fast, faraway targets
   * where human overshooting is common.
   *
   * @param target The destination point on screen.
   * @param speed Speed profile for the overshoot path.
   * @throws InterruptedException If interrupted during movement.
   */
  public void moveToAndOvershoot(final Point target, final String speed)
      throws InterruptedException {
    if (currentPosition.equals(target)) {
      return;
    }
    int direction = random.nextBoolean() ? 1 : -1; // Randomly +1 or -1
    Point pausePoint =
        mousePathing.calculatePointAlongPath(
            currentPosition, target, 1.04, 1.1, random.nextInt(50, 70), direction);
    moveTo(pausePoint, speed);
    moveTo(target, "medium");
  }

  /** Simulates a left-click at the current virtual mouse location. */
  public void leftClick() {
    BaseScript.checkInterrupted();
    Point clientPoint = ScreenManager.toClientCoords(currentPosition);
    kinput.clickLeft(clientPoint.x, clientPoint.y);
    kinput.moveMouse(clientPoint.x, clientPoint.y);
  }

  /** Simulates a right-click at the current virtual mouse location. */
  public void rightClick() {
    BaseScript.checkInterrupted();
    Point clientPoint = ScreenManager.toClientCoords(currentPosition);
    kinput.clickRight(clientPoint.x, clientPoint.y);
    kinput.moveMouse(clientPoint.x, clientPoint.y);
  }

  /**
   * Simulates a middle mouse button input.
   *
   * @param eventType The event code (501 for press, 502 for release).
   */
  public void middleClick(int eventType) {
    BaseScript.checkInterrupted();
    Point clientPoint = ScreenManager.toClientCoords(currentPosition);
    kinput.middleInput(clientPoint.x, clientPoint.y, eventType);
  }

  /**
   * Applies a micro-jitter (1–3 pixels) to the virtual cursor, intended to simulate small human
   * hand movements when clicking.
   */
  public void microJitter() {
    if (random.nextBoolean()) {
      currentPosition.translate(random.nextInt(-1, 2), random.nextInt(-1, 4));
      SwingUtilities.invokeLater(() -> overlay.setMousePoint(currentPosition));
      Point clientPoint = ScreenManager.toClientCoords(currentPosition);
      kinput.moveMouse(clientPoint.x, clientPoint.y);
    }
  }

  /**
   * Returns the Mouse overlay object. Intended to be used by the Controller to destroy the overlay.
   *
   * @return The MouseOverlay object.
   */
  public MouseOverlay getMouseOverlay() {
    return overlay;
  }
}

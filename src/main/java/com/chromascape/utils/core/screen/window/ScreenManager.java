package com.chromascape.utils.core.screen.window;

import com.chromascape.utils.core.screen.capture.CapturedFrame;
import com.chromascape.utils.core.screen.capture.TargetFrameRuntime;
import com.chromascape.utils.core.screen.capture.TargetFrameSource;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Platform-neutral screen facade preserving ChromaScape's existing screen-space contracts.
 *
 * <p>Windows delegates to the existing GDI capture path. Linux reads the rendered BGRA buffer from
 * the active, selection-fenced RemoteInput session, so covered or minimized windows do not alter
 * the captured content. Every successful result is detached and caller-owned.
 */
public class ScreenManager {
  private static WindowSelectionService windowSelectionService;

  /** Retained for source compatibility; all operations remain static. */
  public ScreenManager() {}

  /** Installs the selection service used for screen-space origin and Windows target resolution. */
  public static void setWindowSelectionService(WindowSelectionService service) {
    windowSelectionService = service;
  }

  /** Captures the entire target into a new {@link BufferedImage#TYPE_3BYTE_BGR} image. */
  public static BufferedImage captureWindow() {
    if (ProcessManagerFactory.currentPlatform() == ProcessManagerFactory.Platform.WINDOWS) {
      return WindowsGdiScreenCapture.captureWindow(windowSelectionService);
    }
    return captureFrame().image();
  }

  /**
   * Captures one rendered Linux frame with content-based freshness metadata.
   *
   * @throws IllegalStateException when no lifecycle-owned rendered-buffer source is active
   */
  public static CapturedFrame captureFrame() {
    return TargetFrameRuntime.requireActive().captureFrame();
  }

  /**
   * Captures a screen-space zone, preserving the historical coordinate contract and returning a
   * detached BGR image. A partially overlapping zone is clamped; a disjoint or empty zone fails.
   */
  public static BufferedImage captureZone(Rectangle zone) {
    if (zone == null || zone.width <= 0 || zone.height <= 0) {
      throw new IllegalArgumentException("Capture zone must have positive dimensions");
    }
    BufferedImage fullWindow = captureWindow();
    Rectangle clientZone = toClientBounds(new Rectangle(zone));
    Rectangle intersection =
        clientZone.intersection(new Rectangle(0, 0, fullWindow.getWidth(), fullWindow.getHeight()));
    if (intersection.isEmpty()) {
      throw new IllegalArgumentException("Capture zone is entirely outside the game window");
    }
    BufferedImage cropped =
        new BufferedImage(intersection.width, intersection.height, BufferedImage.TYPE_3BYTE_BGR);
    Graphics2D graphics = cropped.createGraphics();
    try {
      graphics.drawImage(
          fullWindow,
          0,
          0,
          intersection.width,
          intersection.height,
          intersection.x,
          intersection.y,
          intersection.x + intersection.width,
          intersection.y + intersection.height,
          null);
    } finally {
      graphics.dispose();
    }
    return cropped;
  }

  /** Returns the target client bounds in screen space. */
  public static Rectangle getWindowBounds() {
    if (ProcessManagerFactory.currentPlatform() == ProcessManagerFactory.Platform.WINDOWS) {
      return WindowsGdiScreenCapture.getWindowBounds(windowSelectionService);
    }
    TargetFrameSource source = TargetFrameRuntime.requireActive();
    Rectangle target = source.targetBounds();
    WindowInfo selected =
        windowSelectionService != null ? windowSelectionService.getSelectedWindow() : null;
    Rectangle selectedBounds = selected != null ? selected.getBounds() : null;
    int x = selectedBounds != null ? selectedBounds.x : 0;
    int y = selectedBounds != null ? selectedBounds.y : 0;
    return new Rectangle(x, y, target.width, target.height);
  }

  /** Returns the Windows monitor bounds; Linux rendered buffers use their logical target bounds. */
  public static Rectangle getMonitorBounds() {
    if (ProcessManagerFactory.currentPlatform() == ProcessManagerFactory.Platform.WINDOWS) {
      return WindowsGdiScreenCapture.getMonitorBounds(windowSelectionService);
    }
    return getWindowBounds();
  }

  /** Mutates and returns a screen-space rectangle translated to target-client coordinates. */
  public static Rectangle toClientBounds(Rectangle screenBounds) {
    if (screenBounds == null) {
      throw new IllegalArgumentException("Screen bounds must not be null");
    }
    Rectangle offset = getWindowBounds();
    screenBounds.x -= offset.x;
    screenBounds.y -= offset.y;
    return screenBounds;
  }

  /** Returns a target-client point translated from screen space without mutating the input. */
  public static Point toClientCoords(Point screenPoint) {
    if (screenPoint == null) {
      throw new IllegalArgumentException("Screen point must not be null");
    }
    Rectangle offset = getWindowBounds();
    return new Point(screenPoint.x - offset.x, screenPoint.y - offset.y);
  }
}

package com.chromascape.utils.core.screen.window;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import java.awt.AWTException;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * Utility class for capturing screen regions, retrieving window bounds, and interacting with native
 * window functionality using JNA.
 *
 * <p>Provides methods for:
 *
 * <ul>
 *   <li>Capturing the content of a target window or any screen region (zone).
 *   <li>Determining window bounds using native window handles.
 *   <li>Focusing or checking fullscreen status of a given window.
 * </ul>
 */
public class ScreenManager {

  private static final Robot robot;

  /**
   * Grabs the HWND of the second child of the RuneLite window - The game view portion. This is
   * prone to breaking if RuneLite add more canvas elements, but this is not likely.
   */
  private static final HWND canvasHwnd =
      WindowHandler.findNthChildWindow(WindowHandler.getTargetWindow(), "SunAwtCanvas", 2);

  /**
   * JNA extension interface to allow calling {@code ClientToScreen} which converts window-relative
   * coordinates to screen coordinates.
   */
  public interface User32Extended extends User32 {

    /**
     * Converts the client-relative point to screen coordinates.
     *
     * @param hwnd the window handle of the client.
     * @param point the point to convert.
     */
    void ClientToScreen(HWND hwnd, POINT point);

    /**
     * Retrieves the coordinates of a window's client area. The client coordinates specify the
     * upper-left and lower-right corners of the client area. Because client coordinates are
     * relative to the upper-left corner of a window's client area, the coordinates of the
     * upper-left corner are (0,0). <a
     * href="https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getclientrect">Link
     * to documentation</a>
     *
     * @param hwnd Handle to the window.
     * @param rect Long pointer to a RECT structure that receives the client coordinates. The left
     *     and top members are zero. The right and bottom members contain the width and height of
     *     the window.
     * @return True if succeeded, false otherwise.
     */
    boolean GetClientRect(HWND hwnd, RECT rect);

    User32Extended INSTANCE = Native.load("user32", User32Extended.class);
  }

  static {
    try {
      robot = new Robot();
    } catch (AWTException e) {
      throw new RuntimeException("Failed to create Robot instance", e);
    }
  }

  /**
   * Captures the Canvas object of the RuneLite GameView.
   *
   * <p>Converts the ARGB screenshot into a BGR BufferedImage for OpenCV compatibility.
   *
   * @return BufferedImage of the captured window contents in BGR format.
   */
  public static BufferedImage captureWindow() {
    BufferedImage argb = robot.createScreenCapture(getWindowBounds());
    BufferedImage bgr =
        new BufferedImage(argb.getWidth(), argb.getHeight(), BufferedImage.TYPE_3BYTE_BGR);

    Graphics g = bgr.getGraphics();
    g.drawImage(argb, 0, 0, null);
    g.dispose();
    return bgr;
  }

  /**
   * Captures a specific rectangular screen region.
   *
   * <p>Converts the ARGB capture to BGR format for OpenCV usage.
   *
   * @param zone The screen rectangle to capture.
   * @return BufferedImage of the captured zone in BGR format.
   */
  public static BufferedImage captureZone(Rectangle zone) {
    BufferedImage argb = robot.createScreenCapture(zone);
    BufferedImage bgr =
        new BufferedImage(argb.getWidth(), argb.getHeight(), BufferedImage.TYPE_3BYTE_BGR);

    Graphics g = bgr.getGraphics();
    g.drawImage(argb, 0, 0, null);
    g.dispose();
    return bgr;
  }

  /**
   * Gets the bounds of the (game view) RuneLite AWT Canvas object.
   *
   * <p>Converts the client-relative origin to screen coordinates using {@code ClientToScreen}.
   *
   * @return A {@link Rectangle} representing the on-screen position and size of RuneLite's client
   *     area excluding possible window borders, title or scrollbars.
   */
  public static Rectangle getWindowBounds() {
    WinDef.RECT dimensions = new WinDef.RECT();
    User32.INSTANCE.GetClientRect(canvasHwnd, dimensions);

    WinDef.POINT clientTopLeft = new WinDef.POINT();
    clientTopLeft.x = 0;
    clientTopLeft.y = 0;

    User32Extended uex = User32Extended.INSTANCE;
    uex.ClientToScreen(canvasHwnd, clientTopLeft);

    return new Rectangle(
        clientTopLeft.x,
        clientTopLeft.y,
        dimensions.right - dimensions.left,
        dimensions.bottom - dimensions.top);
  }

  /**
   * Checks which monitor contains the target application's top left corner and returns the
   * monitor's {@link Rectangle} bounds.
   *
   * @return the monitor's bounds.
   */
  public static Rectangle getMonitorBounds() {
    WinUser.HMONITOR monitor =
        User32.INSTANCE.MonitorFromWindow(canvasHwnd, WinUser.MONITOR_DEFAULTTONEAREST);

    WinUser.MONITORINFO mi = new WinUser.MONITORINFO();
    mi.cbSize = mi.size();
    User32.INSTANCE.GetMonitorInfo(monitor, mi);

    WinDef.RECT monitorRect = mi.rcMonitor;

    return new Rectangle(
        monitorRect.left,
        monitorRect.top,
        monitorRect.right - monitorRect.left,
        monitorRect.bottom - monitorRect.top);
  }

  /**
   * Converts a screen-space {@link Rectangle} to RuneLite game-view canvas local coordinates.
   *
   * <p>This method adjusts the rectangle's position by subtracting the top-left corner of the
   * canvas (as determined by {@link ScreenManager#getWindowBounds()}) from its {@code x} and {@code
   * y} coordinates. This is necessary when working with screen-detected regions (e.g., from
   * template matching) and applying them to canvas-local images.
   *
   * <p><b>Note:</b> This method mutates and returns the original {@code Rectangle} instance.
   *
   * @param screenBounds the rectangle in absolute screen coordinates
   * @return the same rectangle, now adjusted to canvas-local coordinates
   */
  public static Rectangle toClientBounds(Rectangle screenBounds) {
    Rectangle offset = ScreenManager.getWindowBounds();
    screenBounds.x -= offset.x;
    screenBounds.y -= offset.y;
    return screenBounds;
  }

  /**
   * Converts a screen-space {@link Point} to RuneLite game-view canvas local coordinates.
   *
   * <p>This method adjusts the point's position by subtracting the top-left corner of the canvas
   * (as returned by {@link ScreenManager#getWindowBounds()}). This is typically used when
   * translating points detected in full-screen captures into the coordinate space of the
   * canvas-local window image.
   *
   * @param screenPoint the point in absolute screen coordinates
   * @return a new {@code Point} adjusted to canvas-local coordinates
   */
  public static Point toClientCoords(Point screenPoint) {
    Rectangle offset = ScreenManager.getWindowBounds();
    return new Point(screenPoint.x - offset.x, screenPoint.y - offset.y);
  }
}

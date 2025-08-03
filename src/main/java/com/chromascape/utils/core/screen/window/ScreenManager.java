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

  private static final HWND hwnd = WindowHandler.getTargetWindow();

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
   * Captures the visible content of the client (inner) area of the target window.
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
   * Gets the client (inner content) bounds of the currently focused window.
   *
   * <p>Converts the client-relative origin to screen coordinates using {@code ClientToScreen}.
   *
   * @return A {@link Rectangle} representing the on-screen position and size of the window's client
   *     area.
   */
  public static Rectangle getWindowBounds() {
    WinDef.RECT dimensions = new WinDef.RECT();
    User32.INSTANCE.GetClientRect(WindowHandler.getTargetWindow(), dimensions);

    WinDef.POINT clientTopLeft = new WinDef.POINT();
    clientTopLeft.x = 0;
    clientTopLeft.y = 0;

    User32Extended uex = User32Extended.INSTANCE;
    uex.ClientToScreen(WindowHandler.getTargetWindow(), clientTopLeft);

    return new Rectangle(
        clientTopLeft.x,
        clientTopLeft.y,
        dimensions.right - dimensions.left,
        dimensions.bottom - dimensions.top);
  }

  /** Brings the specified window to the foreground and restores it if minimized. */
  public static void focusWindow() {
    User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW);
    User32.INSTANCE.SetForegroundWindow(hwnd);
  }

  /**
   * Checks which monitor is closest to the target application and returns the monitor's {@link
   * Rectangle} bounds.
   *
   * @return the monitor's bounds.
   */
  public static Rectangle getMonitorBounds() {
    WinUser.HMONITOR monitor =
        User32.INSTANCE.MonitorFromWindow(
            WindowHandler.getTargetWindow(), WinUser.MONITOR_DEFAULTTONEAREST);

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
   * Checks whether the target window is in fullscreen mode.
   *
   * <p>Compares the window's bounds with the dimensions of the monitor it's on. Assumes a taskbar
   * offset of 48 pixels for non-borderless fullscreen windows.
   *
   * @return True if the window occupies the entire monitor space (minus taskbar), false otherwise.
   */
  public static boolean isWindowFullscreen() {
    Rectangle windowRect = getWindowBounds();
    Rectangle monitorRect = getMonitorBounds();

    int windowsTaskBarOffset = 48;
    return windowRect.x == monitorRect.x
        && windowRect.y == monitorRect.y
        && windowRect.x + windowRect.width == monitorRect.x + monitorRect.width
        && windowRect.y + windowRect.height + windowsTaskBarOffset
            == monitorRect.y + monitorRect.height;
  }

  /**
   * Converts a screen-space {@link Rectangle} to client-local coordinates relative to the captured
   * window.
   *
   * <p>This method adjusts the rectangle's position by subtracting the top-left corner of the
   * client window (as determined by {@link ScreenManager#getWindowBounds()}) from its {@code x} and
   * {@code y} coordinates. This is necessary when working with screen-detected regions (e.g., from
   * template matching) and applying them to client-local images.
   *
   * <p><b>Note:</b> This method mutates and returns the original {@code Rectangle} instance.
   *
   * @param screenBounds the rectangle in absolute screen coordinates
   * @return the same rectangle, now adjusted to client-local coordinates
   */
  public static Rectangle toClientBounds(Rectangle screenBounds) {
    Rectangle offset = ScreenManager.getWindowBounds();
    screenBounds.x -= offset.x;
    screenBounds.y -= offset.y;
    return screenBounds;
  }

  /**
   * Converts a screen-space {@link Point} to client-local coordinates relative to the captured
   * window.
   *
   * <p>This method adjusts the point's position by subtracting the top-left corner of the client
   * window (as returned by {@link ScreenManager#getWindowBounds()}). This is typically used when
   * translating points detected in full-screen captures into the coordinate space of the
   * client-local window image.
   *
   * @param screenPoint the point in absolute screen coordinates
   * @return a new {@code Point} adjusted to client-local coordinates
   */
  public static Point toClientCoords(Point screenPoint) {
    Rectangle offset = ScreenManager.getWindowBounds();
    return new Point(screenPoint.x - offset.x, screenPoint.y - offset.y);
  }
}

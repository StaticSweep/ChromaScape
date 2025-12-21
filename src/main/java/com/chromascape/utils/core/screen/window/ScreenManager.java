package com.chromascape.utils.core.screen.window;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFO;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

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

  /**
   * Captures the entire content of the game window using the Windows GDI BitBlt function.
   *
   * <p>Unlike the standard {@link Robot} class which captures the composite screen (including
   * overlays), this method directly reads the Device Context (DC) of the target window. This allows
   * for capturing the game view cleanly even when external overlays are drawn on top of it.
   *
   * <p>This method performs a manual memory copy from native GDI resources to a Java {@link
   * BufferedImage} to ensure compatibility and performance.
   *
   * @return A {@link BufferedImage} containing the window contents in BGR format, suitable for
   *     OpenCV processing.
   * @throws RuntimeException if the window dimensions are invalid or the BitBlt operation fails.
   */
  public static BufferedImage captureWindow() {
    RECT bounds = new RECT();
    User32.INSTANCE.GetClientRect(canvasHwnd, bounds);
    int width = bounds.right - bounds.left;
    int height = bounds.bottom - bounds.top;

    if (width <= 0 || height <= 0) {
      throw new RuntimeException("Invalid dimensions");
    }

    // Retrieve the Device Context for the source window and create a compatible memory DC
    HDC hdcSrc = User32.INSTANCE.GetDC(canvasHwnd);
    HDC hdcMem = GDI32.INSTANCE.CreateCompatibleDC(hdcSrc);
    HBITMAP hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(hdcSrc, width, height);

    HANDLE hOld = GDI32.INSTANCE.SelectObject(hdcMem, hBitmap);

    // Perform the Bit Block Transfer (SRCCOPY 0x00CC0020) to copy the source DC to our memory DC
    boolean success = GDI32.INSTANCE.BitBlt(hdcMem, 0, 0, width, height, hdcSrc, 0, 0, 0x00CC0020);

    BufferedImage bgr = null;

    if (success) {
      // Configure the bitmap structure for the native data retrieval
      BITMAPINFO bmi = new BITMAPINFO();
      bmi.bmiHeader.biWidth = width;
      bmi.bmiHeader.biHeight = -height; // Negative height indicates a top-down bitmap
      bmi.bmiHeader.biPlanes = 1;
      bmi.bmiHeader.biBitCount = 32;
      bmi.bmiHeader.biCompression = WinGDI.BI_RGB;

      Memory buffer = new Memory((long) width * height * 4);
      GDI32.INSTANCE.GetDIBits(hdcMem, hBitmap, 0, height, buffer, bmi, WinGDI.DIB_RGB_COLORS);

      // Copy the raw pixel data from native memory to the Java image raster
      BufferedImage argb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      int[] pixels = buffer.getIntArray(0, width * height);
      int[] targetPixels = ((DataBufferInt) argb.getRaster().getDataBuffer()).getData();
      System.arraycopy(pixels, 0, targetPixels, 0, pixels.length);

      // Create the final BGR image for OpenCV compatibility
      bgr = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
      Graphics g = bgr.getGraphics();
      g.drawImage(argb, 0, 0, null);
      g.dispose();
    }

    // Release all GDI handles to prevent resource leaks
    GDI32.INSTANCE.SelectObject(hdcMem, hOld);
    GDI32.INSTANCE.DeleteObject(hBitmap);
    GDI32.INSTANCE.DeleteDC(hdcMem);
    User32.INSTANCE.ReleaseDC(canvasHwnd, hdcSrc);

    if (bgr == null) {
      throw new RuntimeException("BitBlt capture failed");
    }
    return bgr;
  }

  /**
   * Captures a specific rectangular region of the screen by cropping the full window capture.
   *
   * <p>Since {@code BitBlt} is typically used to capture the entire window context, this method
   * first captures the full window to ensure overlays are bypassed, and then extracts the requested
   * zone.
   *
   * <p>The provided screen coordinates are automatically translated into window-relative
   * coordinates before cropping.
   *
   * @param zone The screen-space {@link Rectangle} to capture.
   * @return A {@link BufferedImage} containing the cropped region in BGR format.
   * @throws RuntimeException if the requested zone lies entirely outside the game window bounds.
   */
  public static BufferedImage captureZone(Rectangle zone) {
    // Capture the full game window to ensure a clean image without overlays
    BufferedImage fullWindow = captureWindow();

    // Translate the screen-space zone into client-space coordinates
    Rectangle clientRect = toClientBounds(new Rectangle(zone));

    // Calculate intersection to ensure valid crop bounds
    int x = Math.max(0, clientRect.x);
    int y = Math.max(0, clientRect.y);
    int w = Math.min(clientRect.width, fullWindow.getWidth() - x);
    int h = Math.min(clientRect.height, fullWindow.getHeight() - y);

    if (w <= 0 || h <= 0) {
      throw new RuntimeException("Capture zone is entirely outside the game window");
    }

    // Draw the sub-region onto a fresh image to ensure contiguous memory for OpenCV
    BufferedImage croppedBgr = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
    Graphics g = croppedBgr.getGraphics();

    g.drawImage(fullWindow.getSubimage(x, y, w, h), 0, 0, null);
    g.dispose();

    return croppedBgr;
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

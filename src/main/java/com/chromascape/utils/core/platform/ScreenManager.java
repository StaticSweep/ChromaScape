package com.chromascape.utils.core.platform;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Platform-independent interface for screen capture and window management operations.
 *
 * <p>Provides methods for:
 *
 * <ul>
 *   <li>Capturing the content of a target window or any screen region (zone).
 *   <li>Determining window bounds using platform-specific window handles.
 *   <li>Focusing or checking fullscreen status of a given window.
 *   <li>Converting between screen and client coordinates.
 * </ul>
 */
public interface ScreenManager {

  /**
   * Captures the visible content of the client (inner) area of the target window.
   *
   * <p>Converts the screenshot into a BGR BufferedImage for OpenCV compatibility.
   *
   * @return BufferedImage of the captured window contents in BGR format.
   */
  BufferedImage captureWindow();

  /**
   * Captures a specific rectangular screen region.
   *
   * <p>Converts the capture to BGR format for OpenCV usage.
   *
   * @param zone The screen rectangle to capture.
   * @return BufferedImage of the captured zone in BGR format.
   */
  BufferedImage captureZone(Rectangle zone);

  /**
   * Gets the client (inner content) bounds of the currently focused window.
   *
   * @return A {@link Rectangle} representing the on-screen position and size of the window's client
   *     area.
   */
  Rectangle getWindowBounds();

  /** Brings the specified window to the foreground and restores it if minimized. */
  void focusWindow();

  /**
   * Checks which monitor is closest to the target application and returns the monitor's bounds.
   *
   * @return the monitor's bounds.
   */
  Rectangle getMonitorBounds();

  /**
   * Checks whether the target window is in fullscreen mode.
   *
   * @return True if the window occupies the entire monitor space, false otherwise.
   */
  boolean isWindowFullscreen();

  /**
   * Converts a screen-space {@link Rectangle} to client-local coordinates relative to the captured
   * window.
   *
   * @param screenBounds the rectangle in absolute screen coordinates
   * @return the same rectangle, now adjusted to client-local coordinates
   */
  Rectangle toClientBounds(Rectangle screenBounds);

  /**
   * Converts a screen-space {@link Point} to client-local coordinates relative to the captured
   * window.
   *
   * @param screenPoint the point in absolute screen coordinates
   * @return a new {@code Point} adjusted to client-local coordinates
   */
  Point toClientCoords(Point screenPoint);
}

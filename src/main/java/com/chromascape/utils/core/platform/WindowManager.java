package com.chromascape.utils.core.platform;

import java.awt.Rectangle;

/**
 * Abstract interface for window management operations across different platforms. This allows
 * platform-specific implementations while maintaining a consistent API.
 */
public interface WindowManager {

  /**
   * Attempts to locate a window with the specified title.
   *
   * @param windowTitle The title of the window to find
   * @return A platform-specific window handle, or null if not found
   */
  Object findWindowByTitle(String windowTitle);

  /**
   * Gets the process ID associated with a window.
   *
   * @param windowHandle The platform-specific window handle
   * @return The process ID, or -1 if unable to determine
   */
  int getProcessId(Object windowHandle);

  /**
   * Gets the bounds (position and size) of a window's client area.
   *
   * @param windowHandle The platform-specific window handle
   * @return The window bounds in screen coordinates
   */
  Rectangle getWindowBounds(Object windowHandle);

  /**
   * Brings a window to the foreground and focuses it.
   *
   * @param windowHandle The platform-specific window handle
   */
  void focusWindow(Object windowHandle);

  /**
   * Checks if a window is in fullscreen mode.
   *
   * @param windowHandle The platform-specific window handle
   * @return true if the window is fullscreen, false otherwise
   */
  boolean isWindowFullscreen(Object windowHandle);

  /**
   * Gets the monitor bounds for the monitor containing the specified window.
   *
   * @param windowHandle The platform-specific window handle
   * @return The monitor bounds in screen coordinates
   */
  Rectangle getMonitorBounds(Object windowHandle);

  /**
   * Converts window-relative coordinates to screen coordinates.
   *
   * @param windowHandle The platform-specific window handle
   * @param x The x coordinate relative to the window
   * @param y The y coordinate relative to the window
   * @return A Point containing the screen coordinates
   */
  java.awt.Point windowToScreen(Object windowHandle, int x, int y);

  /**
   * Checks if the platform supports this window manager implementation.
   *
   * @return true if supported, false otherwise
   */
  boolean isSupported();
}

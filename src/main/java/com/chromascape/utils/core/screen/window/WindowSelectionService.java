package com.chromascape.utils.core.screen.window;

import com.sun.jna.platform.win32.WinDef.HWND;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Service for managing the currently selected window for capture operations.
 *
 * <p>This service maintains the user's selection of which RuneLite window to capture when multiple
 * instances are running. The selection is stored in memory and can be updated via the REST API or
 * UI.
 */
@Service
public class WindowSelectionService {

  private static final Logger logger = LogManager.getLogger(WindowSelectionService.class);

  /** The currently selected window (null = auto-select first window) */
  private final AtomicReference<WindowInfo> selectedWindow = new AtomicReference<>(null);

  /**
   * Gets the currently selected window.
   *
   * @return The selected WindowInfo, or null if no selection has been made
   */
  public WindowInfo getSelectedWindow() {
    return selectedWindow.get();
  }

  /**
   * Sets the currently selected window.
   *
   * @param window The WindowInfo to select, or null to clear selection
   */
  public void setSelectedWindow(WindowInfo window) {
    if (window == null) {
      logger.info("Window selection cleared");
    } else {
      logger.info("Window selected: {}", window.getTitle());
    }
    selectedWindow.set(window);
  }

  /**
   * Returns whether a specific window is currently selected.
   *
   * @param window The window to check
   * @return true if this window is currently selected
   */
  public boolean isSelected(WindowInfo window) {
    return window != null && window.equals(selectedWindow.get());
  }

  /**
   * Returns the native window handle (HWND) of the selected window.
   *
   * @return The HWND, or null if no window is selected
   */
  public HWND getSelectedHwnd() {
    WindowInfo window = selectedWindow.get();
    return window != null ? window.getHwnd() : null;
  }

  /**
   * Checks if a specific window selection exists.
   *
   * @return true if a window has been selected
   */
  public boolean hasSelection() {
    return selectedWindow.get() != null;
  }

  /** Clears the current selection. */
  public void clearSelection() {
    setSelectedWindow(null);
  }
}

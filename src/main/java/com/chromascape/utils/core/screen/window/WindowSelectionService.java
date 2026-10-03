package com.chromascape.utils.core.screen.window;

import com.sun.jna.platform.win32.WinDef.HWND;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>Since SC-1663 the selection carries a neutral {@link ProcessIdentity} and a monotonically
 * increasing generation, and {@link #validateSelected(ProcessManager)} re-reads the process state
 * read-only so an exited process or a recycled PID can never be consumed as a target.
 */
@Service
public class WindowSelectionService {

  private static final Logger logger = LogManager.getLogger(WindowSelectionService.class);

  /** The currently selected target (null = no selection; consumers fall back to auto-detection) */
  private final AtomicReference<TargetSelection> selection = new AtomicReference<>(null);

  /** Monotonically increasing generation, incremented on every selection change */
  private final AtomicLong generation = new AtomicLong(0);

  /**
   * Gets the currently selected window.
   *
   * @return The selected WindowInfo, or null if no selection has been made
   */
  public WindowInfo getSelectedWindow() {
    TargetSelection current = selection.get();
    return current != null ? current.window() : null;
  }

  /**
   * Sets the currently selected window.
   *
   * @param window The WindowInfo to select, or null to clear selection
   */
  public void setSelectedWindow(WindowInfo window) {
    long newGeneration = generation.incrementAndGet();
    if (window == null) {
      logger.info("Window selection cleared");
      selection.set(null);
    } else {
      logger.info("Window selected: {}", window.getTitle());
      selection.set(new TargetSelection(window, window.getProcessIdentity(), newGeneration));
    }
  }

  /**
   * Returns whether a specific window is currently selected.
   *
   * <p>Compares the neutral process identity first, so a freshly re-enumerated but logically
   * identical target still matches; falls back to reference comparison only when neither side
   * carries an identity (preserving the pre-SC-1663 behavior for legacy windows).
   *
   * @param window The window to check
   * @return true if this window is currently selected
   */
  public boolean isSelected(WindowInfo window) {
    if (window == null) {
      return false;
    }
    TargetSelection current = selection.get();
    if (current == null) {
      return false;
    }
    if (current.identity() != null && window.getProcessIdentity() != null) {
      return current.identity().equals(window.getProcessIdentity());
    }
    return window == current.window();
  }

  /**
   * Returns the native window handle (HWND) of the selected window.
   *
   * <p>This is a Windows-only adapter accessor: neutral Linux selections carry no HWND and yield
   * null here.
   *
   * @return The HWND, or null if no window is selected or the selection is neutral
   */
  public HWND getSelectedHwnd() {
    WindowInfo window = getSelectedWindow();
    return window != null ? window.getHwnd() : null;
  }

  /**
   * Checks if a specific window selection exists.
   *
   * @return true if a window has been selected
   */
  public boolean hasSelection() {
    return selection.get() != null;
  }

  /** Clears the current selection. */
  public void clearSelection() {
    setSelectedWindow(null);
  }

  /**
   * Gets the neutral identity of the selected target.
   *
   * @return the selected identity, or null when no selection exists or the selection carries no
   *     identity
   */
  public ProcessIdentity getSelectedIdentity() {
    TargetSelection current = selection.get();
    return current != null ? current.identity() : null;
  }

  /**
   * Gets the generation of the current selection.
   *
   * @return the selection generation, or the current counter value when nothing is selected
   */
  public long getSelectedGeneration() {
    TargetSelection current = selection.get();
    return current != null ? current.generation() : generation.get();
  }

  /**
   * Read-only revalidation of the current selection against live process state.
   *
   * @param processManager the discovery implementation for this platform
   * @return the validation outcome
   */
  public SelectionValidation validateSelected(ProcessManager processManager) {
    return validateSelected(processManager, getSelectedGeneration());
  }

  /**
   * Read-only revalidation of the current selection against live process state, including a
   * generation check for callers that captured the generation earlier. Never attaches, injects,
   * focuses or kills; SC-1664 consumes {@link SelectionValidation#VALID} plus a matching generation
   * as the precondition for its one-attachment lifecycle.
   *
   * @param processManager the discovery implementation for this platform
   * @param expectedGeneration the generation the caller observed; any value other than the current
   *     selection generation is stale, so a caller that observed a future or replaced generation
   *     can never validate
   * @return the validation outcome
   */
  public SelectionValidation validateSelected(
      ProcessManager processManager, long expectedGeneration) {
    if (processManager == null) {
      return SelectionValidation.UNSUPPORTED_PLATFORM;
    }
    if (!processManager.isPlatformSupported()) {
      return SelectionValidation.UNSUPPORTED_PLATFORM;
    }
    TargetSelection current = selection.get();
    if (current == null) {
      return SelectionValidation.NONE;
    }
    if (expectedGeneration != current.generation()) {
      return SelectionValidation.STALE_GENERATION;
    }
    ProcessIdentity identity = current.identity();
    if (identity == null) {
      // Legacy selection without a neutral identity: fall back to liveness by pid
      long pid = current.window().getPid();
      if (pid <= 0) {
        return SelectionValidation.NONE;
      }
      return processManager.resolveLocal(pid).isPresent()
          ? SelectionValidation.VALID
          : SelectionValidation.EXITED;
    }
    if (!identity.isLocal()) {
      return SelectionValidation.NOT_LOCAL;
    }
    Optional<ProcessIdentity> observed = processManager.resolveLocal(identity.pid());
    if (observed.isEmpty()) {
      return SelectionValidation.EXITED;
    }
    return observed.get().equals(identity)
        ? SelectionValidation.VALID
        : SelectionValidation.REPLACED;
  }
}

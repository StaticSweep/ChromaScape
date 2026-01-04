package com.chromascape.utils.core.screen.window;

import com.chromascape.utils.core.AccountManager;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for locating and identifying a specific native window (e.g., "RuneLite") on the
 * Windows operating system using JNA and Win32 APIs.
 */
public class WindowHandler {
  private static final Logger logger = LoggerFactory.getLogger(WindowHandler.class);
  private static final String _windowName = "RuneLite";

  /**
   * JNA interface for accessing low-level Win32 User32 functions that are not provided by the
   * default JNA platform mappings.
   */
  public interface User32 extends StdCallLibrary {
    User32 INSTANCE = Native.load("user32", User32.class);

    /**
     * Enumerates all top-level windows on the screen by invoking the provided callback.
     *
     * @param lpEnumFunc The callback to be called for each window.
     * @param arg A user-defined value passed to the callback (usually null).
     */
    void EnumWindows(WNDENUMPROC lpEnumFunc, Pointer arg);

    /**
     * Retrieves the title text of the specified window.
     *
     * @param hwnd Handle to the window.
     * @param lpString Buffer that receives the window title.
     * @param maxCount Maximum number of characters to copy.
     */
    void GetWindowTextA(HWND hwnd, byte[] lpString, int maxCount);

    /**
     * Retrieves the process identifier (PID) for the specified window.
     *
     * @param hwnd Handle to the window.
     * @param lpDword Receives the process ID.
     */
    void GetWindowThreadProcessId(HWND hwnd, IntByReference lpDword);

    /**
     * Enumerates all child windows of a specified parent window.
     *
     * <p>This function calls the provided callback {@code lpEnumFunc} for each child window of the
     * specified parent {@code hWndParent}. It is commonly used to locate specific child controls or
     * canvases within a larger window.
     *
     * @param hwndParent the handle of the parent window whose children are to be enumerated
     * @param lpEnumFunc the callback function to be called for each child window
     * @param data a pointer to user-defined data that will be passed to the callback
     */
    void EnumChildWindows(HWND hwndParent, WNDENUMPROC lpEnumFunc, Pointer data);

    /**
     * Retrieves the class name of a specified window.
     *
     * <p>This function writes the window class name of {@code hwnd} into the provided byte array
     * {@code lpString}, up to a maximum of {@code maxCount} characters. It is useful for
     * identifying specific child windows or controls by class.
     *
     * @param hwnd the handle of the window whose class name is to be retrieved
     * @param lpString the byte array to receive the class name
     * @param maxCount the maximum number of characters to copy into {@code lpString}
     */
    void GetClassNameA(HWND hwnd, byte[] lpString, int maxCount);

    /**
     * Retrieves the window rectangle for the specified window.
     *
     * @param hwnd The handle of the target window.
     * @param rect Receives the window rectangle coordinates.
     * @return true if successful, false otherwise.
     */
    boolean GetWindowRect(HWND hwnd, RECT rect);
  }

  /**
   * Attempts to locate the window whose title matches the {@code windowName}.
   *
   * <p>This method uses a multi-tier fallback strategy:
   * <ol>
   *   <li>Exact match on "RuneLite" or "RuneLite - accountname"</li>
   *   <li>Partial match (case-insensitive) for windows containing "runelite"</li>
   *   <li>First available Java/AWT window (final fallback)</li>
   * </ol>
   *
   * @return The {@link HWND} handle of the target window, or {@code null} if not found.
   */
  public static HWND getTargetWindow() {
    User32 user32 = User32.INSTANCE;

    // Get the selected account from AccountManager (loaded during startup)
    String selectedAccount = AccountManager.getSelectedAccount();

    // Build the resolved window name - make it effectively final
    final String resolvedWindowName =
        (selectedAccount != null && !selectedAccount.trim().isEmpty())
            ? _windowName + " - " + selectedAccount
            : _windowName;

    // Tier 1: Try exact match
    AtomicReference<HWND> exactMatch = new AtomicReference<>();
    user32.EnumWindows(
        (hwnd, arg) -> {
          byte[] buffer = new byte[512];
          user32.GetWindowTextA(hwnd, buffer, 512);
          String title = Native.toString(buffer);

          if (title.trim().equals(resolvedWindowName)) {
            exactMatch.set(hwnd);
            return false; // stop enumeration
          }
          return true;
        },
        null);

    if (exactMatch.get() != null) {
      return exactMatch.get();
    }

    // Tier 2: Try partial match (case-insensitive) for windows containing "runelite"
    AtomicReference<HWND> partialMatch = new AtomicReference<>();
    user32.EnumWindows(
        (hwnd, arg) -> {
          byte[] buffer = new byte[512];
          user32.GetWindowTextA(hwnd, buffer, 512);
          String title = Native.toString(buffer);

          if (title.toLowerCase().contains("runelite")) {
            partialMatch.set(hwnd);
            return false; // stop enumeration
          }
          return true;
        },
        null);

    if (partialMatch.get() != null) {
      return partialMatch.get();
    }

    // Tier 3: Final fallback - get first Java window (manual selection via UI/API preferred)
    return getFirstJavaWindow();
  }

  /**
   * Searches for the N-th child window of a given parent window that matches the specified class
   * name.
   *
   * <p>This is useful when multiple child windows share the same class (e.g., RuneLite has two
   * SunAwtCanvas children: the outer canvas with black bars and the inner canvas with the game
   * viewport). By specifying an index, you can reliably retrieve the intended child.
   *
   * @param parent the {@link HWND} of the parent window whose children are to be enumerated
   * @param className the class name of the child windows to match (e.g., "SunAwtCanvas")
   * @param n the 1-based index of the matching child window to retrieve (1 = first match)
   * @return the {@link HWND} of the N-th matching child window, or {@code null} if no such child
   *     exists
   */
  public static HWND findNthChildWindow(HWND parent, String className, int n) {
    AtomicReference<HWND> result = new AtomicReference<>();
    User32 user32 = User32.INSTANCE;
    int[] count = {0};

    user32.EnumChildWindows(
        parent,
        (hwnd, data) -> {
          byte[] buffer = new byte[512];
          user32.GetClassNameA(hwnd, buffer, buffer.length);
          String clsName = Native.toString(buffer).trim();

          if (clsName.equals(className)) {
            count[0]++;
            if (count[0] == n) {
              result.set(hwnd);
              return false; // stop enumeration
            }
          }
          return true; // continue enumeration
        },
        null);

    return result.get(); // may be null if no match found
  }

  /**
   * Retrieves the process ID associated with a given window handle.
   *
   * @param hwnd The handle of the target window.
   * @return The process ID (PID) of the window's owning process.
   */
  public static int getPid(HWND hwnd) {
    IntByReference pid = new IntByReference();
    User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
    return pid.getValue();
  }

  /**
   * Enumerates all RuneLite windows currently running on the system.
   *
   * <p>This method scans all top-level windows and returns information about each window
   * whose title matches the pattern "RuneLite" or "RuneLite - [accountname]".
   *
   * @return A list of WindowInfo objects containing details about each detected window
   */
  public static List<WindowInfo> getAllTargetWindows() {
    List<WindowInfo> windows = new ArrayList<>();
    User32 user32 = User32.INSTANCE;
    AtomicInteger index = new AtomicInteger(1);

    user32.EnumWindows(
        (hwnd, arg) -> {
          byte[] buffer = new byte[512];
          user32.GetWindowTextA(hwnd, buffer, 512);
          String title = Native.toString(buffer).trim();

          // Check if title starts with "RuneLite" or contains it (more flexible)
          if (title.startsWith(_windowName)
              || title.toLowerCase().contains("runelite")) {
            // Extract account name from title (format: "RuneLite - accountname" or just "RuneLite")
            String accountName = null;
            if (title.length() > _windowName.length()) {
              String suffix = title.substring(_windowName.length()).trim();
              if (suffix.startsWith("-")) {
                accountName = suffix.substring(1).trim();
              }
            }

            // Get process ID
            int pid = getPid(hwnd);

            // Get window bounds
            Rectangle bounds = getWindowBounds(user32, hwnd);

            // Create WindowInfo with sequential ID
            WindowInfo windowInfo = new WindowInfo(
                String.valueOf(index.get()),
                hwnd,
                title,
                accountName,
                pid,
                bounds
            );
            windows.add(windowInfo);
            index.incrementAndGet();
          }
          return true; // continue enumeration
        },
        null);

    return windows;
  }

  /**
   * Helper method to get the bounds (rectangle) of a window.
   *
   * @param user32 The User32 interface
   * @param hwnd The window handle
   * @return The window bounds as a Rectangle
   */
  private static Rectangle getWindowBounds(User32 user32, HWND hwnd) {
    RECT rect = new RECT();
    user32.GetWindowRect(hwnd, rect);
    return new Rectangle(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);
  }

  /**
   * Enumerates all Java/AWT windows currently running on the system.
   *
   * <p>This is more flexible than {@link #getAllTargetWindows()} as it detects any Java/AWT window
   * by checking window class names (e.g., "SunAwtFrame", "DlgFrame"), not just RuneLite windows.
   *
   * @return A list of WindowInfo objects containing details about each Java window
   */
  public static List<WindowInfo> getAllJavaWindows() {
    List<WindowInfo> windows = new ArrayList<>();
    User32 user32 = User32.INSTANCE;
    AtomicInteger index = new AtomicInteger(1);

    user32.EnumWindows(
        (hwnd, arg) -> {
          byte[] titleBuffer = new byte[512];
          user32.GetWindowTextA(hwnd, titleBuffer, 512);
          String title = Native.toString(titleBuffer).trim();

          byte[] classBuffer = new byte[512];
          user32.GetClassNameA(hwnd, classBuffer, 512);
          String className = Native.toString(classBuffer).trim();

          // Detect Java/AWT windows - they have class names like "SunAwtFrame", "DlgFrame", etc.
          // Also include RuneLite windows for backward compatibility
          boolean isJavaWindow =
              className.contains("Awt")
                  || className.contains("Sun")
                  || className.contains("awt")
                  || title.startsWith(_windowName);

          if (isJavaWindow && !title.isEmpty()) {
            int pid = getPid(hwnd);
            Rectangle bounds = getWindowBounds(user32, hwnd);

            WindowInfo windowInfo =
                new WindowInfo(
                    String.valueOf(index.get()),
                    hwnd,
                    title,
                    null, // No account name for generic Java windows
                    pid,
                    bounds);
            windows.add(windowInfo);
            index.incrementAndGet();
          }
          return true;
        },
        null);

    return windows;
  }

  /**
   * Gets the first available Java window for auto-detection fallback.
   *
   * <p>This is used as a last resort when the exact RuneLite window cannot be found.
   *
   * @return The HWND of the first Java window, or null if none found
   */
  public static HWND getFirstJavaWindow() {
    List<WindowInfo> windows = getAllJavaWindows();
    return windows.isEmpty() ? null : windows.get(0).getHwnd();
  }
}

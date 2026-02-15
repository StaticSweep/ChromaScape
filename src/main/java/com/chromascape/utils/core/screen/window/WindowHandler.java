package com.chromascape.utils.core.screen.window;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for locating and identifying a specific native window (e.g., "RuneLite") on the
 * Windows operating system using JNA and Win32 APIs.
 */
public class WindowHandler {
  private static final Logger logger = LoggerFactory.getLogger(WindowHandler.class);
  private static final String WINDOW_NAME = "RuneLite";

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
  }

  /**
   * Attempts to locate the window whose title matches the {@code WINDOW_NAME}.
   *
   * @return The {@link HWND} handle of the target window, or {@code null} if not found.
   */
  public static HWND getTargetWindow() {
    AtomicReference<HWND> targetHwnd = new AtomicReference<>();
    User32 user32 = User32.INSTANCE;

    user32.EnumWindows(
        (hwnd, arg) -> {
          byte[] buffer = new byte[512];
          user32.GetWindowTextA(hwnd, buffer, 512);
          String title = Native.toString(buffer);

          if (title.trim().equals(WINDOW_NAME)) {
            targetHwnd.set(hwnd);
            return false; // stop enumeration
          }
          return true;
        },
        null);

    return targetHwnd.get(); // May be null if not found
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
}

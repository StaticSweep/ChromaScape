package com.chromascape.utils.core.screen.window;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility class for locating and identifying a specific native window (e.g., "RuneLite") on the
 * Windows operating system using JNA and Win32 APIs.
 */
public class WindowsProcessManager implements ProcessManager {

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
   * To return the Process ID of RuneLite. Avoids non-ChromaScape related instances by searching for
   * "RuneLite" only as opposed to "RuneLite - {UserName}"
   *
   * @return The integer process ID of RuneLite loaded with the ChromaScape profile
   */
  @Override
  public int getPid() {
    HWND windowHandle = getTargetWindow();
    IntByReference pid = new IntByReference();
    User32.INSTANCE.GetWindowThreadProcessId(windowHandle, pid);
    return pid.getValue();
  }
}

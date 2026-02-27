package com.chromascape.utils.core.screen.window;

import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import java.awt.Rectangle;

/**
 * Represents information about a detected window.
 *
 * <p>This class encapsulates details about a RuneLite window including its handle, title, account
 * name, process ID, and screen position.
 */
public class WindowInfo {

  private final String id;
  private final HWND hwnd;
  private final String title;
  private final String accountName;
  private final int pid;
  private final Rectangle bounds;

  /**
   * Creates a new WindowInfo.
   *
   * @param id Sequential ID for this window (1-based index)
   * @param hwnd The native window handle
   * @param title The full window title
   * @param accountName The account name extracted from title (may be null)
   * @param pid The process ID
   * @param bounds The window bounds on screen
   */
  public WindowInfo(
      String id, HWND hwnd, String title, String accountName, int pid, Rectangle bounds) {
    this.id = id;
    this.hwnd = hwnd;
    this.title = title;
    this.accountName = accountName;
    this.pid = pid;
    this.bounds = bounds;
  }

  /**
   * Creates a WindowInfo from a WinDef.RECT.
   *
   * @param id Sequential ID for this window
   * @param hwnd The native window handle
   * @param title The full window title
   * @param accountName The account name extracted from title
   * @param pid The process ID
   * @param rect The window RECT from Win32 API
   */
  public static WindowInfo fromRect(
      String id, HWND hwnd, String title, String accountName, int pid, RECT rect) {
    return new WindowInfo(
        id,
        hwnd,
        title,
        accountName,
        pid,
        new Rectangle(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top));
  }

  public String getId() {
    return id;
  }

  public HWND getHwnd() {
    return hwnd;
  }

  public String getTitle() {
    return title;
  }

  public String getAccountName() {
    return accountName;
  }

  public int getPid() {
    return pid;
  }

  public Rectangle getBounds() {
    return bounds;
  }

  @Override
  public String toString() {
    return "WindowInfo{"
        + "id='"
        + id
        + '\''
        + ", title='"
        + title
        + '\''
        + ", accountName='"
        + accountName
        + '\''
        + ", pid="
        + pid
        + ", bounds="
        + bounds
        + '}';
  }
}

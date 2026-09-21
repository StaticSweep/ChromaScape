package com.chromascape.utils.core.screen.window;

import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import java.awt.Rectangle;

/**
 * Represents information about a detected window.
 *
 * <p>This class encapsulates details about a RuneLite window including its handle, title, account
 * name, process ID, and screen position.
 *
 * <p>Since SC-1663 a window may carry a neutral, nullable {@link ProcessIdentity}; the HWND is no
 * longer required for a valid selection value (Linux discoveries have no window handle). The
 * original constructor is preserved and derives a null identity.
 */
public class WindowInfo {

  private final String id;
  private final HWND hwnd;
  private final String title;
  private final String accountName;
  private final int pid;
  private final boolean visible;
  private final Rectangle bounds;
  private final ProcessIdentity processIdentity;

  /**
   * Creates a new WindowInfo without a neutral process identity.
   *
   * @param id Sequential ID for this window (1-based index)
   * @param hwnd The native window handle
   * @param title The full window title
   * @param accountName The account name extracted from title (may be null)
   * @param pid The process ID
   * @param bounds The window bounds on screen
   */
  public WindowInfo(
      String id,
      HWND hwnd,
      String title,
      String accountName,
      int pid,
      boolean visible,
      Rectangle bounds) {
    this(id, hwnd, title, accountName, pid, visible, bounds, null);
  }

  /**
   * Creates a new WindowInfo with an optional neutral process identity.
   *
   * @param id Sequential ID for this window (1-based index)
   * @param hwnd The native window handle (null for neutral Linux discoveries)
   * @param title The full window title (null for neutral Linux discoveries)
   * @param accountName The account name extracted from title (may be null)
   * @param pid The process ID
   * @param bounds The window bounds on screen (null for neutral Linux discoveries)
   * @param processIdentity The neutral process identity (may be null)
   */
  public WindowInfo(
      String id,
      HWND hwnd,
      String title,
      String accountName,
      int pid,
      boolean visible,
      Rectangle bounds,
      ProcessIdentity processIdentity) {
    this.id = id;
    this.hwnd = hwnd;
    this.title = title;
    this.accountName = accountName;
    this.pid = pid;
    this.visible = visible;
    this.bounds = bounds;
    this.processIdentity = processIdentity;
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
      String id, HWND hwnd, String title, String accountName, int pid, boolean visible, RECT rect) {
    return new WindowInfo(
        id,
        hwnd,
        title,
        accountName,
        pid,
        visible,
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

  /**
   * Returns the process ID, preferring the neutral identity's pid when present.
   *
   * @return the identity pid when this window carries an identity, otherwise the stored pid
   */
  public int getPid() {
    return processIdentity != null ? (int) processIdentity.pid() : pid;
  }

  public boolean isVisible() {
    return visible;
  }

  public Rectangle getBounds() {
    return bounds;
  }

  public ProcessIdentity getProcessIdentity() {
    return processIdentity;
  }

  /**
   * Returns a copy of this window with the given neutral identity attached (Windows enrichment).
   *
   * @param identity the identity to attach
   * @return a new WindowInfo with the same fields plus the identity
   */
  public WindowInfo withIdentity(ProcessIdentity identity) {
    return new WindowInfo(id, hwnd, title, accountName, pid, visible, bounds, identity);
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
        + getPid()
        + ", visible="
        + visible
        + ", bounds="
        + bounds
        + ", identity="
        + (processIdentity != null ? processIdentity.toDisplayString() : "none")
        + '}';
  }
}

package com.chromascape.utils.core.input.remoteinput;

import com.sun.jna.Library;
import com.sun.jna.Native;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Provides a wrapper for the native Kinput interface, allowing injection of mouse and keyboard
 * events into a target Java application's process via JNA and Kinput DLLs.
 *
 * <p>This class is responsible for:
 *
 * <ul>
 *   <li>Loading native Kinput libraries
 *   <li>Creating and managing the Kinput instance lifecycle
 *   <li>Sending mouse and keyboard events to a remote process
 * </ul>
 *
 * <p>Intended for use with 64-bit Java applications that expose a {@code java.awt.Canvas}.
 */
public class Kinput {

  /** JNA interface mapping for KInputCtrl.dll native methods. */
  public interface KinputInterface extends Library {

    /**
     * Creates a Kinput instance for the specified process ID.
     *
     * @param pid the target process ID
     * @return true if creation succeeded; false otherwise
     */
    boolean KInput_Create(int pid);

    /**
     * Deletes the Kinput instance for the specified process ID.
     *
     * @param pid the target process ID
     * @return true if deletion succeeded; false otherwise
     */
    boolean KInput_Delete(int pid);

    /**
     * Sends a focus event to the target process.
     *
     * @param pid the target process ID
     * @param eventID the focus event type (e.g., gain or loss)
     * @return true if event succeeded; false otherwise
     */
    boolean KInput_FocusEvent(int pid, int eventID);

    /**
     * Sends a keyboard event to the target process.
     *
     * @param pid the target process ID
     * @param eventID the keyboard event ID
     * @param when event timestamp for queuing
     * @param modifiers key modifiers
     * @param keyCode virtual key code
     * @param keyChar the character associated
     * @param keyLocation key location
     * @return true if the event was successfully sent; false otherwise
     */
    boolean KInput_KeyEvent(
        int pid,
        int eventID,
        long when,
        int modifiers,
        int keyCode,
        short keyChar,
        int keyLocation);

    /**
     * Sends a mouse event to the target process.
     *
     * @param pid the target process ID
     * @param eventID the mouse event type ID
     * @param when event timestamp for queuing
     * @param modifiers mouse modifiers
     * @param x the x-coordinate of the mouse event
     * @param y the y-coordinate of the mouse event
     * @param clickCount number of clicks
     * @param popupTrigger whether this event should pop up
     * @param button mouse button ID
     * @return true if the event was successfully sent; false otherwise
     */
    boolean KInput_MouseEvent(
        int pid,
        int eventID,
        long when,
        int modifiers,
        int x,
        int y,
        int clickCount,
        boolean popupTrigger,
        int button);
  }

  private static final int FOCUS_GAINED = 1004;
  private static final int FOCUS_LOST = 1005;

  private final int pid;
  private final KinputInterface kinput;

  /** Mouse event type IDs mapped to standard AWT semantics. */
  public enum MouseEventType {
    MOUSE_CLICK(500),
    MOUSE_PRESS(501),
    MOUSE_RELEASE(502),
    MOUSE_MOVE(503),
    MOUSE_ENTER(504),
    MOUSE_EXIT(505),
    MOUSE_DRAG(506),
    MOUSE_WHEEL(507);

    public final int id;

    MouseEventType(int id) {
      this.id = id;
    }
  }

  /** Mouse button IDs. */
  public enum MouseButton {
    NONE(0),
    LEFT(1),
    MIDDLE(2),
    RIGHT(3);

    public final int id;

    MouseButton(int id) {
      this.id = id;
    }
  }

  /**
   * Constructs a new Kinput instance for the given process ID.
   *
   * @param pid the process ID of the target Java application
   */
  public Kinput(int pid) {
    this.pid = pid;
    this.kinput = loadLibrary();
    if (!kinput.KInput_Create(pid)) {
      throw new RuntimeException("Failed to create Kinput instance for PID: " + pid);
    }
  }

  /**
   * Loads the Kinput native libraries from the build directory and prepares them for JNA.
   *
   * @return the loaded KinputInterface
   */
  private static KinputInterface loadLibrary() {
    try {
      // Try to load from build/dist directory first (preferred)
      Path buildDistPath = Path.of("build/dist");
      Path dllFileCtrl = buildDistPath.resolve("KInputCtrl.dll");
      Path dllFile64 = buildDistPath.resolve("KInput.dll");

      if (Files.exists(dllFileCtrl) && Files.exists(dllFile64)) {
        // Use build directory directly
        System.setProperty("jna.library.path", buildDistPath.toAbsolutePath().toString());
        return Native.load("KInputCtrl", KinputInterface.class);
      }

      // If DLLs are not found in build directory, throw an error
      throw new IOException(
          "Missing native libraries in build/dist directory. Please run the build process first.");
    } catch (Throwable e) {
      throw new RuntimeException("Failed to load Kinput DLLs", e);
    }
  }

  /** Sleeps briefly to simulate human-like click timing. */
  private void sleepHumanClick() {
    try {
      Thread.sleep(new Random().nextInt(50, 80));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Ensures the target window is focused before sending input. */
  private synchronized void focus() {
    if (!kinput.KInput_FocusEvent(pid, FOCUS_GAINED)) {
      throw new RuntimeException("Focus event failed for PID: " + pid);
    }
  }

  /**
   * Sends a mouse press and release to a specified screen co-ordinate. This operates in client
   * relative co-ordinates, not screen relative co-ordinates.
   *
   * @param x the x co-ordinate of the action.
   * @param y the y co-ordinate of the action.
   * @param button which {@link MouseButton} to use.
   */
  public synchronized void clickMouse(int x, int y, int button) {
    focus();
    if (!kinput.KInput_MouseEvent(
        pid,
        MouseEventType.MOUSE_PRESS.id,
        System.currentTimeMillis(),
        1,
        x,
        y,
        1,
        false,
        button)) {
      throw new RuntimeException(button + " press failed");
    }
    sleepHumanClick();
    if (!kinput.KInput_MouseEvent(
        pid,
        MouseEventType.MOUSE_RELEASE.id,
        System.currentTimeMillis(),
        1,
        x,
        y,
        1,
        false,
        button)) {
      throw new RuntimeException(button + " release failed");
    }
  }

  /** Sends a middle mouse button input. */
  public synchronized void middleInput(int x, int y, int eventID) {
    focus();
    if (!kinput.KInput_MouseEvent(
        pid, eventID, System.currentTimeMillis(), 1, x, y, 1, false, MouseButton.MIDDLE.id)) {
      throw new RuntimeException("Middle mouse event failed");
    }
  }

  /** Moves the mouse cursor to a given screen coordinate. */
  public synchronized void moveMouse(int x, int y) {
    focus();
    if (!kinput.KInput_MouseEvent(
        pid,
        MouseEventType.MOUSE_ENTER.id,
        System.currentTimeMillis(),
        0,
        x,
        y,
        0,
        false,
        MouseButton.NONE.id)) {
      throw new RuntimeException("Mouse enter failed");
    }
    if (!kinput.KInput_MouseEvent(
        pid,
        MouseEventType.MOUSE_MOVE.id,
        System.currentTimeMillis(),
        0,
        x,
        y,
        0,
        false,
        MouseButton.NONE.id)) {
      throw new RuntimeException("Mouse move failed");
    }
  }

  /**
   * Sends a key character to the client window.
   *
   * @param eventID - 401 to press/ 402 to release.
   * @param keyChar - The key character to send.
   */
  public synchronized void sendKeyEvent(int eventID, char keyChar) {
    focus();
    if (!kinput.KInput_KeyEvent(
        pid, eventID, System.currentTimeMillis(), 0, 0, (short) keyChar, 0)) {
      throw new RuntimeException("Key event failed for char: " + keyChar);
    }
  }

  /**
   * Types a character to the client window.
   *
   * @param keyChar The character to send.
   */
  public synchronized void sendCharEvent(char keyChar) {
    focus();
    // Passes keyChar into the 'short' parameter (arg5)
    if (!kinput.KInput_KeyEvent(pid, 400, System.currentTimeMillis(), 0, 0, (short) keyChar, 0)) {
      throw new RuntimeException("Character event failed for char: '" + keyChar + "'");
    }
  }

  /**
   * Sends a virtual key event (Arrow, Modifier, F-Key) to the client window's SunAwtCanvas.
   *
   * @param eventID 401 for press, 402 for release.
   * @param keyCode The virtual key code.
   * @param keyName A descriptive name used purely for error logging.
   */
  public synchronized void sendVirtualKeyEvent(int eventID, int keyCode, String keyName) {
    focus();
    // Passes keyCode into the integer parameter (arg4)
    if (!kinput.KInput_KeyEvent(
        pid, eventID, System.currentTimeMillis(), 0, keyCode, (short) 0, 0)) {
      throw new RuntimeException("Virtual key event failed for: " + keyName);
    }
  }

  /**
   * Destroys the active KInput instance. Should be called on shutdown to free memory and close
   * resources.
   */
  public synchronized void destroy() {
    if (!kinput.KInput_Delete(pid)) {
      throw new RuntimeException("Failed to delete KInput instance");
    }
  }
}

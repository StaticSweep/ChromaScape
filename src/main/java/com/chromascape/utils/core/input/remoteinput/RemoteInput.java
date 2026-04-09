package com.chromascape.utils.core.input.remoteinput;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * RemoteInput allows ChromaScape to send Java AWT signals to a target Java app, to simulate IO.
 * This approach as opposed to sending OS signals, allows the user to fully minimise and or cover
 * the target app. It also allows the user to keep using their computer as they wish, as if
 * ChromaScape was never running. This class provides functionality to load a RemoteInput binary
 * regardless of operating system, provide IO to the target application, and receive the most
 * up-to-date snapshot of the application's Java canvas (updated whenever they draw a new frame).
 */
public class RemoteInput implements AutoCloseable {

  private static final String COMPILED_BINARY_FILENAME = "libRemoteInput" + getExtension();

  private final int pid;

  /** JNA needs to load the binary as an interface, the interface acts as the exported headers. */
  private final RemoteInputInterface remoteInput;

  /**
   * RemoteInput returns a Pointer which acts as a reference to a specific client/target. A single
   * instance of RI can support several targets. RI requests this pointer when performing IO, to
   * specify which target to use.
   */
  private Pointer target;

  /**
   * Constructs the RemoteInput class.
   *
   * @param pid The process ID of the target Java application
   */
  public RemoteInput(int pid) {
    this.pid = pid;
    this.remoteInput = loadRemoteInput();
    initialise();
  }

  /**
   * The RI binary can be compiled on Linux, Mac and Windows. This function detects OS and applies
   * the corresponding filetype.
   *
   * @return OS specific filetype
   */
  private static String getExtension() {
    String os = System.getProperty("os.name").toLowerCase();
    if (os.contains("win")) {
      return ".dll";
    }
    if (os.contains("mac")) {
      return ".dylib";
    }
    return ".so";
  }

  /**
   * RemoteInput expects specific Java keycodes for several keys, compared to generic keycodes.
   * e.g., enter is typically 10, we want 13.
   *
   * @param javaKeyCode The {@link KeyEvent} Java keycode
   * @return RemoteInput's preferred keycode
   */
  private int toNativeCode(int javaKeyCode) {
    return switch (javaKeyCode) {
      case KeyEvent.VK_ENTER -> ControlKey.VK_RETURN.nativeCode;
      case KeyEvent.VK_CONTROL -> ControlKey.VK_LEFT_CONTROL.nativeCode;
      case KeyEvent.VK_ALT -> ControlKey.VK_LEFT_ALT.nativeCode;
      case KeyEvent.VK_ALT_GRAPH -> ControlKey.VK_RIGHT_ALT.nativeCode;
      case KeyEvent.VK_WINDOWS -> ControlKey.VK_LEFT_WINDOWS.nativeCode;
      default -> javaKeyCode;
    };
  }

  /**
   * Checks if the target application is already a registered client. If not, will inject RI into
   * the application. Finally, it pairs with the target
   */
  private void initialise() {
    if (!isInjectedClient()) {
      remoteInput.EIOS_Inject_PID(pid);
    }
    pairClient();
  }

  /**
   * Checks if the target application is already registered within RI's internal list of connected
   * clients. A registered client is one that already has RI injected into it.
   *
   * @return Whether the target application is registered
   */
  private boolean isInjectedClient() {
    long totalClients = remoteInput.EIOS_GetClients(false);

    for (long i = 0; i < totalClients; i++) {
      if (remoteInput.EIOS_GetClientPID(i) == pid) {
        return true;
      }
    }
    return false;
  }

  /**
   * Grants IO operation over a registered client by returning a pointer to reference a specific
   * client.
   *
   * @see #target <- The returned pointer
   */
  private void pairClient() {
    target = remoteInput.EIOS_RequestTarget(String.valueOf(pid));
    if (target == null) {
      throw new RuntimeException("Target Not Found with pid: " + pid);
    }
  }

  /**
   * Loads the RemoteInput binary as a {@link RemoteInputInterface} object to allow Java to
   * communicate directly to the native binary. Will first check if a user compiled binary exists,
   * if not, uses a provided pre-compiled binary.
   *
   * @return An interface that acts as a bridge to talk to the binary in Java, used within this
   *     class to provide IO operations
   */
  private static RemoteInputInterface loadRemoteInput() {
    Path binaryFile =
        Paths.get("third-party", "RemoteInput", "cmake-build-release", COMPILED_BINARY_FILENAME);
    if (!Files.exists(binaryFile)) {
      binaryFile = Paths.get("third-party", "RemoteInput", "precompiled", COMPILED_BINARY_FILENAME);
    }

    try {
      return Native.load(binaryFile.toString(), RemoteInputInterface.class);
    } catch (UnsatisfiedLinkError e) {
      throw new RuntimeException("Unable to load RemoteInput binary from path", e);
    }
  }

  /**
   * Retrieves the memory pointer to the target's current image buffer. The buffer contains pixel
   * data in BGRA (Blue, Green, Red, Alpha) format. Each pixel occupies 4 bytes. The total size of
   * the buffer is {@code width * height * 4} bytes. The data is updated automatically by the native
   * hooks in the target application whenever a frame is rendered.
   *
   * @return A pointer to the start of the BGRA pixel array
   */
  public synchronized Pointer getImageBuffer() {
    Pointer p = remoteInput.EIOS_GetImageBuffer(target);
    if (p == null) {
      throw new RuntimeException("Image Buffer Not Found with pid: " + pid);
    }
    return p;
  }

  /**
   * Retrieves a memory pointer similarly to {@link #getImageBuffer()}. This method however will
   * contain the mouse pointer and any objects drawn onto the canvas.
   *
   * @return A pointer to the start of the BGRA pixel array
   */
  public synchronized Pointer getDebugImageBuffer() {
    Pointer p = remoteInput.EIOS_GetDebugImageBuffer(target);
    if (p == null) {
      throw new RuntimeException("Image Buffer Not Found with pid: " + pid);
    }
    return p;
  }

  /** Will get focus of the client if in an unfocused state, necessary for mouse input. */
  private void getFocusIfNotFocused() {
    if (!remoteInput.EIOS_HasFocus(target)) {
      remoteInput.EIOS_GainFocus(target);
    }
  }

  /** Will enable keyboard input if it's currently disabled, necessary for keyboard input. */
  private void setKeyboardInputIfDisabled() {
    if (!remoteInput.EIOS_IsKeyboardInputEnabled(target)) {
      remoteInput.EIOS_SetKeyboardInputEnabled(target, true);
    }
  }

  /**
   * RemoteInput stores an internal mouse position that stays persistent after ChromaScape restarts.
   * This will return that mouse position. VirtualMouseUtils automatically syncs to this unless
   * RemoteInput is pairing for the first time, where it'll randomise mouse position.
   *
   * @return A {@link Point} referring to the client relative mouse position
   */
  public synchronized Point getMousePosition() {
    IntByReference x = new IntByReference();
    IntByReference y = new IntByReference();
    remoteInput.EIOS_GetMousePosition(target, x, y);
    return new Point(x.getValue(), y.getValue());
  }

  /**
   * Gets the target app's window dimensions. Due to all IO being performed in client relative
   * space, the user can assume the origin as 0,0.
   *
   * @return A rectangle defining the origin and bounds of the target application
   */
  public synchronized Rectangle getTargetDimensions() {
    IntByReference x = new IntByReference();
    IntByReference y = new IntByReference();
    remoteInput.EIOS_GetTargetDimensions(target, x, y);
    return new Rectangle(0, 0, x.getValue(), y.getValue());
  }

  /**
   * Sends a key down in the target java app. To get the keycode, use a {@link
   * java.awt.event.KeyEvent} object. Example: {@code KeyEvent.VK_ENTER}.
   *
   * @param javaKeyCode The Java keycode corresponding to the key being pressed
   */
  public synchronized void holdKey(int javaKeyCode) {
    setKeyboardInputIfDisabled();
    getFocusIfNotFocused();
    remoteInput.EIOS_HoldKey(target, toNativeCode(javaKeyCode));
  }

  /**
   * Queries whether a key is currently being held.
   *
   * @param javaKeyCode The Java keycode of the key in question
   * @return Whether the key is currently being held
   */
  public synchronized boolean isKeyHeld(int javaKeyCode) {
    return remoteInput.EIOS_IsKeyHeld(target, toNativeCode(javaKeyCode));
  }

  /**
   * Sends a key release event to the target Java app. This should be used in conjunction with
   * {@link #holdKey(int)} to simulate a full key press.
   *
   * @param javaKeyCode The Java keycode corresponding to the key being released
   */
  public synchronized void releaseKey(int javaKeyCode) {
    setKeyboardInputIfDisabled();
    getFocusIfNotFocused();
    remoteInput.EIOS_ReleaseKey(target, toNativeCode(javaKeyCode));
  }

  /**
   * Sends a mouse button down in the target Java app.
   *
   * @param button The {@link MouseButton} button to hold
   */
  public synchronized void holdMouse(MouseButton button) {
    getFocusIfNotFocused();
    Point mousePosition = getMousePosition();
    remoteInput.EIOS_HoldMouse(target, mousePosition.x, mousePosition.y, button.ordinal());
  }

  /**
   * Queries whether a {@link MouseButton} is currently held.
   *
   * @param button The {@link MouseButton} to check
   * @return If the mouse button is currently being held
   */
  public synchronized boolean isMouseHeld(MouseButton button) {
    return remoteInput.EIOS_IsMouseHeld(target, button.ordinal());
  }

  /**
   * Releases a mouse button at the designated client local co-ordinates.
   *
   * @param button The {@link MouseButton} to release
   */
  public synchronized void releaseMouse(MouseButton button) {
    getFocusIfNotFocused();
    Point mousePosition = getMousePosition();
    remoteInput.EIOS_ReleaseMouse(target, mousePosition.x, mousePosition.y, button.ordinal());
  }

  /**
   * Moves a mouse to a designated client local co-ordinate.
   *
   * @param location The {@link Point} location to snap the mouse to
   */
  public synchronized void moveMouse(Point location) {
    getFocusIfNotFocused();
    remoteInput.EIOS_MoveMouse(target, location.x, location.y);
  }

  /**
   * Performs a mouse wheel scroll at the current virtual mouse position. RemoteInput natively adds
   * a small float value to this, to simulate human imperfection
   *
   * @param notches The number of mouse notches to scroll, down is positive, up is negative
   */
  public synchronized void scrollMouse(int notches) {
    getFocusIfNotFocused();
    Point mousePosition = getMousePosition();
    remoteInput.EIOS_ScrollMouse(target, mousePosition.x, mousePosition.y, notches);
  }

  /**
   * Types out a string of characters whilst compensating for the need of modifier keys. Useful when
   * typing something to a dialogue box, will compensate for special characters, however lacks delay
   * between keypresses.
   *
   * @param string The text to be typed
   * @param keyWait The time in milliseconds to hold down a key
   * @param keyModWait The time in milliseconds to hold down modifier keys
   */
  public synchronized void sendString(String string, int keyWait, int keyModWait) {
    setKeyboardInputIfDisabled();
    getFocusIfNotFocused();
    remoteInput.EIOS_SendString(target, string, keyWait, keyModWait);
  }

  /**
   * Since this class implements the {@link AutoCloseable} interface, it must be closed to relieve
   * native memory. This method will release the target, effectively shutting down RemoteInput for
   * the particular ChromaScape instance. However, this does not delete the injected part of RI in
   * the target, simply shuts down control over it.
   */
  @Override
  public void close() {
    if (target != null) {
      remoteInput.EIOS_ReleaseTarget(target);
      target = null;
    }
  }
}

package com.chromascape.utils.core.input.remoteinput;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * Audited JNA mapping of the pinned RemoteInput C ABI.
 *
 * <p>Every declaration here was checked against {@code
 * third-party/RemoteInput/RemoteInput/EIOS.hxx} (and {@code Plugin/NativePlugin.hxx} for {@link
 * #EIOS_Inject_PID(int)}), not copied because it compiles.
 *
 * <p>ABI notes from that audit:
 *
 * <ul>
 *   <li>The native symbols are declared {@code extern "C"} with {@code [[gnu::visibility(
 *       "default")]]} on non-Windows platforms, so the ELF exports unmangled names. Windows {@code
 *       __stdcall} decoration ({@code @N}) and {@code RemoteInput.def} do not apply to the Linux
 *       artifact and must not be used to derive the expected Linux symbol list.
 *   <li>{@code std::int32_t} maps to Java {@code int}; {@code std::int32_t*} maps to {@link
 *       IntByReference}.
 *   <li>{@code std::size_t} maps to Java {@code long} (64-bit on LP64 and on Win64).
 *   <li>The mouse-held query is {@code EIOS_IsMouseButtonHeld}. There is <b>no</b> {@code
 *       EIOS_IsMouseHeld} symbol in the pinned sources; the upstream Java binding declared that
 *       non-existent name, and the Windows {@code .def} still lists it. Declaring it here would
 *       produce a load-time or first-invocation {@code UnsatisfiedLinkError}.
 *   <li>C++ {@code bool} is one byte in the pinned Linux ABI, while JNA's default Java {@code
 *       boolean} mapping is a 32-bit integer. Native boolean parameters and returns are therefore
 *       declared as Java {@code byte}; use {@link #toNativeBoolean(boolean)} and {@link
 *       #fromNativeBoolean(byte)} at the Java boundary.
 * </ul>
 *
 * <p>This interface is the load/verification seam only. It deliberately performs no target
 * selection, injection, pairing or input; constructing a target-bound session is SC-1664's
 * responsibility.
 */
public interface RemoteInputInterface extends Library {

  byte NATIVE_FALSE = 0;
  byte NATIVE_TRUE = 1;

  /** Converts a Java boolean to the pinned native C++ {@code bool} representation. */
  static byte toNativeBoolean(boolean value) {
    return value ? NATIVE_TRUE : NATIVE_FALSE;
  }

  /** Converts the pinned native C++ {@code bool} representation to a Java boolean. */
  static boolean fromNativeBoolean(byte value) {
    return value != NATIVE_FALSE;
  }

  /**
   * Injects the RemoteInput component into the target process. Declared for ABI completeness; this
   * story never calls it.
   *
   * @param pid the target OS process id
   */
  void EIOS_Inject_PID(int pid);

  /**
   * Pairs with an already-injected target and returns its opaque native handle.
   *
   * @param initargs the target process id as a string
   * @return the target handle, or {@code null} when not found
   */
  Pointer EIOS_RequestTarget(String initargs);

  /**
   * Releases a target handle previously returned by {@link #EIOS_RequestTarget(String)}.
   *
   * @param eios the paired target handle
   */
  void EIOS_ReleaseTarget(Pointer eios);

  /**
   * Reads the target canvas dimensions in client-relative pixels.
   *
   * @param eios the paired target handle
   * @param width receives the width
   * @param height receives the height
   */
  void EIOS_GetTargetDimensions(Pointer eios, IntByReference width, IntByReference height);

  /**
   * Returns a pointer to the current BGRA image buffer.
   *
   * @param eios the paired target handle
   * @return pointer to the pixel buffer
   */
  Pointer EIOS_GetImageBuffer(Pointer eios);

  /**
   * Returns a pointer to the debug image buffer (cursor and canvas overlays included).
   *
   * @param eios the paired target handle
   * @return pointer to the pixel buffer
   */
  Pointer EIOS_GetDebugImageBuffer(Pointer eios);

  /**
   * Whether the host currently has focus over the target.
   *
   * @param eios the paired target handle
   * @return a native C++ {@code bool}; convert with {@link #fromNativeBoolean(byte)}
   */
  byte EIOS_HasFocus(Pointer eios);

  /**
   * Requests focus over the target.
   *
   * @param eios the paired target handle
   */
  void EIOS_GainFocus(Pointer eios);

  /**
   * Releases focus over the target.
   *
   * @param eios the paired target handle
   */
  void EIOS_LoseFocus(Pointer eios);

  /**
   * Whether keyboard input is enabled for the target.
   *
   * @param eios the paired target handle
   * @return a native C++ {@code bool}; convert with {@link #fromNativeBoolean(byte)}
   */
  byte EIOS_IsKeyboardInputEnabled(Pointer eios);

  /**
   * Enables or disables keyboard input for the target.
   *
   * @param eios the paired target handle
   * @param enabled a native C++ {@code bool}; create with {@link #toNativeBoolean(boolean)}
   */
  void EIOS_SetKeyboardInputEnabled(Pointer eios, byte enabled);

  /**
   * Reads RemoteInput's persisted internal mouse position.
   *
   * @param eios the paired target handle
   * @param x receives the x value
   * @param y receives the y value
   */
  void EIOS_GetMousePosition(Pointer eios, IntByReference x, IntByReference y);

  /**
   * Moves the virtual mouse to a client-relative coordinate.
   *
   * @param eios the paired target handle
   * @param x the x coordinate
   * @param y the y coordinate
   */
  void EIOS_MoveMouse(Pointer eios, int x, int y);

  /**
   * Presses a mouse button at a client-relative coordinate.
   *
   * @param eios the paired target handle
   * @param x the x coordinate
   * @param y the y coordinate
   * @param button the mouse button id
   */
  void EIOS_HoldMouse(Pointer eios, int x, int y, int button);

  /**
   * Releases a mouse button at a client-relative coordinate.
   *
   * @param eios the paired target handle
   * @param x the x coordinate
   * @param y the y coordinate
   * @param button the mouse button id
   */
  void EIOS_ReleaseMouse(Pointer eios, int x, int y, int button);

  /**
   * Scrolls the mouse wheel at a client-relative coordinate.
   *
   * @param eios the paired target handle
   * @param x the x coordinate
   * @param y the y coordinate
   * @param lines the number of notches; positive scrolls down
   */
  void EIOS_ScrollMouse(Pointer eios, int x, int y, int lines);

  /**
   * Whether the given mouse button is currently held. Native name: {@code EIOS_IsMouseButtonHeld}.
   *
   * @param eios the paired target handle
   * @param button the mouse button id
   * @return a native C++ {@code bool}; convert with {@link #fromNativeBoolean(byte)}
   */
  byte EIOS_IsMouseButtonHeld(Pointer eios, int button);

  /**
   * Types a string into the target, compensating for modifier keys.
   *
   * @param eios the paired target handle
   * @param string the text to type
   * @param keywait milliseconds to hold a key
   * @param keymodwait milliseconds to hold modifier keys
   */
  void EIOS_SendString(Pointer eios, String string, int keywait, int keymodwait);

  /**
   * Sends a key-down event.
   *
   * @param eios the paired target handle
   * @param key the Java AWT key code
   */
  void EIOS_HoldKey(Pointer eios, int key);

  /**
   * Sends a key-up event.
   *
   * @param eios the paired target handle
   * @param key the Java AWT key code
   */
  void EIOS_ReleaseKey(Pointer eios, int key);

  /**
   * Whether the given key is currently held.
   *
   * @param eios the paired target handle
   * @param key the Java AWT key code
   * @return a native C++ {@code bool}; convert with {@link #fromNativeBoolean(byte)}
   */
  byte EIOS_IsKeyHeld(Pointer eios, int key);

  /**
   * Returns the number of clients currently injected. Native return type is {@code std::size_t}.
   *
   * @param unpairedOnly a native C++ {@code bool}; create with {@link #toNativeBoolean(boolean)}
   * @return the client count
   */
  long EIOS_GetClients(byte unpairedOnly);

  /**
   * Returns the process id of a client by index. Native parameter type is {@code std::size_t}.
   *
   * @param index the client index
   * @return the process id
   */
  int EIOS_GetClientPID(long index);
}

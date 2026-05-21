package com.chromascape.utils.core.input.remoteinput;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * JNA interface to load the RemoteInput DLL as a {@link RemoteInput} object. Contains the available
 * exported headers available for ChromaScape to use.
 */
public interface RemoteInputInterface extends Library {

  /**
   * Injects part of the RemoteInput Process into the target Java app. RI will only work after this
   * pairing process has been performed.
   *
   * @param pid The OS process ID of which to inject into.
   */
  void EIOS_Inject_PID(int pid);

  /**
   * Requests to control a specific target app after it's been registered using {@link
   * #EIOS_Inject_PID(int)}. Many targets can be controlled using a single instance of RemoteInput.
   *
   * @param initargs The String value of the target Java app's process ID (PID)
   * @return A pointer reference to the target's EIOS object. This object is used in IO operations
   *     as a reference to which target should receive IO
   */
  Pointer EIOS_RequestTarget(String initargs);

  /**
   * Retrieves the memory pointer to the target's current image buffer. The buffer contains pixel
   * data in BGRA (Blue, Green, Red, Alpha) format. Each pixel occupies 4 bytes. The total size of
   * the buffer is {@code width * height * 4} bytes. The data is updated automatically by the native
   * hooks in the target application whenever a frame is rendered.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @return A pointer to the start of the BGRA pixel array
   */
  Pointer EIOS_GetImageBuffer(Pointer eios);

  /**
   * Retrieves a memory pointer similarly to {@link #EIOS_GetImageBuffer(Pointer)}. This method
   * however will contain the mouse pointer and any objects drawn onto the canvas.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @return A pointer to the start of the BGRA pixel array
   */
  Pointer EIOS_GetDebugImageBuffer(Pointer eios);

  /**
   * Checks if the target application has keyboard input enabled. This is useful when using {@link
   * #EIOS_HoldKey(Pointer, int)}, {@link #EIOS_ReleaseKey(Pointer, int)} and or {@link
   * #EIOS_SendString(Pointer, String, int, int)}.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @return If the target has keyboard input enabled
   */
  boolean EIOS_IsKeyboardInputEnabled(Pointer eios);

  /**
   * Sets the keyboard input to either enabled or disabled, useful when conducting keyboard input.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param enabled Whether you want keyboard input to be enabled
   */
  void EIOS_SetKeyboardInputEnabled(Pointer eios, boolean enabled);

  /**
   * Gets the total number of clients that currently have RemoteInput injected into them.
   *
   * @param unpaired_only Whether to count only unpaired targets.
   * @return The total number of connected targets.
   */
  long EIOS_GetClients(boolean unpaired_only);

  /**
   * Fetches the process ID of a client given the index of the client in RemoteInput's internal
   * client array.
   *
   * @param index The index position of the client
   * @return The process ID
   */
  int EIOS_GetClientPID(long index);

  /**
   * Whether the host machine has focus over the target Java app. Focus is required when sending
   * inputs to the client, it's recommended to use {@link #EIOS_GainFocus(Pointer)} to do so.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @return Whether the host has focus over the client
   */
  boolean EIOS_HasFocus(Pointer eios);

  /**
   * Used to gain focus over the target app.
   *
   * @param eios The pointer to the paired EIOS target instance
   */
  void EIOS_GainFocus(Pointer eios);

  /**
   * Used to lose focus over the target app, might be useful for antiban possibly.
   *
   * @param eios The pointer to the paired EIOS target instance
   */
  void EIOS_LoseFocus(Pointer eios);

  /**
   * RemoteInput stores an internal mouse position that stays persistent after ChromaScape restarts.
   * This will return that mouse position. VirtualMouseUtils automatically syncs to this unless
   * RemoteInput is pairing for the first time, where it'll randomise mouse position.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param x An {@link IntByReference} which gets mutated with the x value
   * @param y An {@link IntByReference} which gets mutated with the y value
   */
  void EIOS_GetMousePosition(Pointer eios, IntByReference x, IntByReference y);

  /**
   * Gets the target app's window dimensions. Due to all IO being performed in client relative
   * space, the user can assume the origin as 0,0.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param width An {@link IntByReference} which gets mutated with the width's value in pixels
   * @param height An {@link IntByReference} which gets mutated with the height's value in pixels
   */
  void EIOS_GetTargetDimensions(Pointer eios, IntByReference width, IntByReference height);

  /**
   * Sends a key down in the target java app. To get the keycode, use a {@link
   * java.awt.event.KeyEvent} object. Example: {@code KeyEvent.VK_ENTER}.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param key The Java keycode corresponding to the key being pressed
   */
  void EIOS_HoldKey(Pointer eios, int key);

  /**
   * Sends a mouse button down in the target java app.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param x The x co-ordinate of the input
   * @param y The y co-ordinate of the input
   * @param button The {@link MouseButton} to use
   */
  void EIOS_HoldMouse(Pointer eios, int x, int y, int button);

  /**
   * Queries whether a key is currently held.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param key The Java keycode of the key
   * @return If the key is currently being held
   */
  boolean EIOS_IsKeyHeld(Pointer eios, int key);

  /**
   * Queries whether a {@link MouseButton} is currently held.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param button The {@link MouseButton} to check
   * @return If the mouse button is currently being held
   */
  boolean EIOS_IsMouseHeld(Pointer eios, int button);

  /**
   * Moves a mouse to a designated client local co-ordinate.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param x The x co-ordinate of the input
   * @param y The y co-ordinate of the input
   */
  void EIOS_MoveMouse(Pointer eios, int x, int y);

  /**
   * Sends a key release event to the target Java app. This should be used in conjunction with
   * {@link #EIOS_HoldKey(Pointer, int)} to simulate a full key press.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param key The Java keycode corresponding to the key being released
   */
  void EIOS_ReleaseKey(Pointer eios, int key);

  /**
   * Releases a mouse button at the designated client local co-ordinates.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param x The x co-ordinate of the input
   * @param y The y co-ordinate of the input
   * @param button The {@link MouseButton} to release
   */
  void EIOS_ReleaseMouse(Pointer eios, int x, int y, int button);

  /**
   * Severs the connection to the target EIOS object and releases native resources. This should be
   * called when the script stops or the controller shuts down to avoid memory leaks in the target
   * app.
   *
   * @param eios The pointer to the paired EIOS target instance
   */
  void EIOS_ReleaseTarget(Pointer eios);

  /**
   * Performs a mouse wheel scroll at the current virtual mouse position. RemoteInput natively adds
   * a small float value to this, to simulate human imperfection
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param x The x co-ordinate of the input
   * @param y The y co-ordinate of the input
   * @param lines The number of notches to scroll; positive for down, negative for up
   */
  void EIOS_ScrollMouse(Pointer eios, int x, int y, int lines);

  /**
   * Types out a string of characters whilst compensating for the need of modifier keys. Useful when
   * typing something to a dialogue box, will compensate for special characters, however lacks delay
   * between keypresses.
   *
   * @param eios The pointer to the paired EIOS target instance
   * @param string The text to be typed
   * @param keywait The time in milliseconds to hold down a key
   * @param keymodwait The time in milliseconds to hold down modifier keys
   */
  void EIOS_SendString(Pointer eios, String string, int keywait, int keymodwait);
}

package com.chromascape.utils.core.input.remoteinput;

/**
 * The KInput operations a Windows session consumes.
 *
 * <p>Extracted from {@link Kinput} so the session adapter and its lifecycle tests never touch the
 * real DLL-loading backend; {@link Kinput} is the only production implementation.
 */
public interface KinputBackend {

  /**
   * Moves the mouse cursor to a client-relative coordinate.
   *
   * @param x the x coordinate
   * @param y the y coordinate
   */
  void moveMouse(int x, int y);

  /**
   * Sends a mouse button event at a client-relative coordinate.
   *
   * @param x the x coordinate
   * @param y the y coordinate
   * @param eventId the AWT event id (501 press, 502 release)
   * @param button the button
   */
  void mouseInput(int x, int y, int eventId, Kinput.MouseButton button);

  /**
   * Sends a keyboard event for a character key.
   *
   * @param eventId the AWT event id (401 press, 402 release, 400 typed)
   * @param keyChar the character
   */
  void sendKeyEvent(int eventId, char keyChar);

  /**
   * Sends a keyboard event for a key by code.
   *
   * @param eventId the AWT event id (401 press, 402 release)
   * @param key the diagnostic key name
   * @param keyId the Java AWT key code posted to the target
   */
  void sendModifierKey(int eventId, String key, int keyId);

  /** Destroys the native KInput instance. */
  void destroy();
}

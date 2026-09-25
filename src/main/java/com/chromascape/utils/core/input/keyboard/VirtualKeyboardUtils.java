package com.chromascape.utils.core.input.keyboard;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.input.TargetInputSession;
import com.chromascape.utils.core.input.remoteinput.Kinput;
import com.chromascape.utils.core.input.remoteinput.KinputTargetInputSession;
import com.chromascape.utils.core.state.BotState;
import com.chromascape.utils.core.state.StateManager;
import com.chromascape.utils.core.statistics.StatisticsManager;

/**
 * High-level keyboard input independent of the native backend.
 *
 * <p>The session path is the fenced {@link TargetInputSession} contract (selection generation and
 * process identity revalidated on every operation); the legacy KInput path preserves the existing
 * behavior for Windows callers that construct a {@link Kinput} directly.
 */
public class VirtualKeyboardUtils implements AutoCloseable {

  private static final int KEY_PRESSED = 401;
  private static final int KEY_RELEASED = 402;
  private static final int KEY_TYPED = 400;

  private final TargetInputSession session;
  private final Kinput legacyKinput;
  private volatile boolean closed;

  /**
   * Creates a keyboard utility over a target-owned session.
   *
   * @param session the attached session
   */
  public VirtualKeyboardUtils(TargetInputSession session) {
    this.session = session;
    this.legacyKinput = null;
  }

  /**
   * Compatibility constructor for existing Windows callers.
   *
   * @param kinput the KInput backend
   */
  public VirtualKeyboardUtils(Kinput kinput) {
    this.session = new KinputTargetInputSession(kinput, null, -1, 1, 1, null, null, () -> {});
    this.legacyKinput = kinput;
  }

  private void checkOpen() {
    if (closed || !session.isOpen()) {
      throw new IllegalStateException("Keyboard utility is closed");
    }
  }

  /**
   * Sends a keyboard event for a character key (e.g., letters, numbers, symbols). Intended for use
   * with regular text input simulation.
   *
   * @param keyChar The character key to send.
   */
  public synchronized void sendKeyChar(char keyChar) {
    checkOpen();
    BaseScript.checkInterrupted();
    StateManager.setState(BotState.ACTING);
    StatisticsManager.incrementInputs();
    if (legacyKinput != null) {
      legacyKinput.sendKeyEvent(KEY_TYPED, keyChar);
    } else {
      session.sendString(String.valueOf(keyChar), 0, 0);
    }
  }

  /**
   * Sends a keyboard event for a modifier key (e.g., Shift, Ctrl, Alt, Enter).
   *
   * @param eventId 401 to simulate a key press, or 402 to simulate a key release.
   * @param key The name of the modifier key. Acceptable values: "shift", "enter", "alt", "ctrl",
   *     "esc", "space".
   * @throws IllegalArgumentException if the key name is invalid.
   */
  public synchronized void sendModifierKey(int eventId, String key) {
    validateEvent(eventId);
    int keyId =
        switch (key.toLowerCase()) {
          case "shift" -> 16;
          case "enter" -> 10;
          case "alt" -> 18;
          case "ctrl" -> 17;
          case "esc" -> 27;
          case "space" -> 32;
          default -> throw new IllegalArgumentException("Invalid modifier key: " + key);
        };
    sendKeyEvent(eventId, keyId, key);
  }

  /**
   * Sends a keyboard event for an arrow (directional) key.
   *
   * @param eventId 401 to simulate a key press, or 402 to simulate a key release.
   * @param key The arrow direction. Acceptable values: "up", "down", "left", "right".
   * @throws IllegalArgumentException if the arrow direction is invalid.
   */
  public synchronized void sendArrowKey(int eventId, String key) {
    validateEvent(eventId);
    int keyId =
        switch (key.toLowerCase()) {
          case "left" -> 37;
          case "right" -> 39;
          case "up" -> 38;
          case "down" -> 40;
          default -> throw new IllegalArgumentException("Invalid arrow key: " + key);
        };
    sendKeyEvent(eventId, keyId, key);
  }

  /**
   * Presses a key by its Java AWT key code.
   *
   * @param javaKeyCode the AWT key code
   */
  public synchronized void sendKeyDown(int javaKeyCode) {
    sendKeyEvent(KEY_PRESSED, javaKeyCode, "key");
  }

  /**
   * Releases a key by its Java AWT key code.
   *
   * @param javaKeyCode the AWT key code
   */
  public synchronized void sendKeyRelease(int javaKeyCode) {
    sendKeyEvent(KEY_RELEASED, javaKeyCode, "key");
  }

  /**
   * Returns whether a key is currently held by the session.
   *
   * @param javaKeyCode the AWT key code
   * @return whether the key is held
   */
  public synchronized boolean isKeyHeld(int javaKeyCode) {
    checkOpen();
    return session.isKeyHeld(javaKeyCode);
  }

  /**
   * Types a string with zero delays.
   *
   * @param text the text to type
   */
  public synchronized void sendString(String text) {
    sendString(text, 0, 0, 0);
  }

  /**
   * Types a string, honoring the typed-key and modifier delays.
   *
   * @param text the text to type
   * @param keyWaitMillis milliseconds a pressed key stays down
   * @param modifierWaitMillis milliseconds to wait after a key release
   * @param unused retained parameter for call-site compatibility; ignored
   */
  public synchronized void sendString(
      String text, int keyWaitMillis, int modifierWaitMillis, int unused) {
    checkOpen();
    if (keyWaitMillis < 0 || modifierWaitMillis < 0) {
      throw new IllegalArgumentException("Delays must not be negative");
    }
    BaseScript.checkInterrupted();
    StateManager.setState(BotState.ACTING);
    StatisticsManager.incrementInputs();
    session.sendString(text, keyWaitMillis, modifierWaitMillis);
  }

  private void sendKeyEvent(int eventId, int keyCode, String name) {
    validateEvent(eventId);
    checkOpen();
    BaseScript.checkInterrupted();
    StateManager.setState(BotState.ACTING);
    StatisticsManager.incrementInputs();
    if (legacyKinput != null) {
      legacyKinput.sendModifierKey(eventId, name, keyCode);
    } else if (eventId == KEY_PRESSED) {
      session.holdKey(keyCode);
    } else {
      session.releaseKey(keyCode);
    }
  }

  private static void validateEvent(int eventId) {
    if (eventId != KEY_PRESSED && eventId != KEY_RELEASED) {
      throw new IllegalArgumentException("Event id must be 401 (press) or 402 (release)");
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
  }
}

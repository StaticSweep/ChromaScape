package com.chromascape.utils.core.input.keyboard;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.input.remoteinput.RemoteInput;
import com.chromascape.utils.core.state.BotState;
import com.chromascape.utils.core.state.StateManager;
import com.chromascape.utils.core.statistics.StatisticsManager;
import java.awt.event.KeyEvent;
import java.util.Random;

/**
 * Provides high-level methods for simulating keyboard input using the RemoteInput API. The user can
 * use {@link KeyEvent} objects to dictate exactly what to press and for how long. There is
 * functionality to type out a string, compensating for modifier keys where necessary.
 */
public class VirtualKeyboardUtils {

  private final RemoteInput input;

  private static final Random RANDOM = new Random();

  /**
   * Constructs a VirtualKeyboardUtils instance that wraps a RemoteInput instance.
   *
   * @param input The RemoteInput object that can operate IO
   */
  public VirtualKeyboardUtils(RemoteInput input) {
    this.input = input;
  }

  /**
   * Updates the state of the bot for the {@link BaseScript}'s stop() function and updates the
   * BotState for the UI.
   */
  private void prepareInput() {
    BaseScript.checkInterrupted();
    StateManager.setState(BotState.ACTING);
    StatisticsManager.incrementInputs();
  }

  /**
   * Sends a key down, given that it isn't already held. As this function requires an int keycode,
   * please use {@link KeyEvent}. Typing {@code KeyEvent.VK_} should show contextual actions in your
   * IDE of choice, showing you available keys which are mapped to integer keycodes. You may opt to
   * look for Java VK keycodes online, however this is the best approach.
   *
   * <p>Example usage: {@code controller().keyboard().sendKeyRelease(KeyEvent.VK_SHIFT);}
   *
   * @param javaKeyCode the {@link KeyEvent} key to hold
   */
  public void sendKeyDown(int javaKeyCode) {
    prepareInput();
    if (!input.isKeyHeld(javaKeyCode)) {
      input.holdKey(javaKeyCode);
    }
  }

  /**
   * Releases a key, given that it is held. As this function requires an int keycode, please use
   * {@link KeyEvent}. Typing {@code KeyEvent.VK_} should show contextual actions in your IDE of
   * choice, showing you available keys which are mapped to integer keycodes. You may opt to look
   * for Java VK keycodes online, however this is the best approach.
   *
   * <p>Example usage: {@code controller().keyboard().sendKeyRelease(KeyEvent.VK_SHIFT);}
   *
   * @param javaKeyCode the {@link KeyEvent} key to release
   */
  public void sendKeyRelease(int javaKeyCode) {
    prepareInput();
    if (input.isKeyHeld(javaKeyCode)) {
      input.releaseKey(javaKeyCode);
    }
  }

  /**
   * Checks whether a key is currently being held.
   *
   * @param javaKeyCode the {@link KeyEvent} key to check
   * @return Whether the key is currently being held or not
   */
  public boolean isKeyHeld(int javaKeyCode) {
    return input.isKeyHeld(javaKeyCode);
  }

  /**
   * Types out a given string to the client window using heuristics to mimic a human. Uses default
   * heuristic settings for convenience.
   *
   * @param string The String of characters to type out in a human like fashion
   */
  public synchronized void sendString(String string) {
    prepareInput();
    for (char c : string.toCharArray()) {
      int keyWait = RANDOM.nextInt(30, 60);
      int keyModWait = RANDOM.nextInt(30, 60);
      int keyPressWait = RANDOM.nextInt(40, 85);
      input.sendString(String.valueOf(c), keyWait, keyModWait);
      BaseScript.waitMillis(keyPressWait);
    }
  }

  /**
   * Types out a given string to the client window using heuristics to mimic a human. Internally
   * randomises between 1x - 1.1x the given modifier value.
   *
   * @param string The String of characters to type out in a human like fashion
   * @param keyWait The amount of time to hold a key down
   * @param keyModWait The amount of time to hold a modifier key down (e.g., shift)
   * @param keyPressWait The amount of time to wait between pressing keys
   */
  public synchronized void sendString(
      String string, int keyWait, int keyModWait, int keyPressWait) {
    prepareInput();
    for (char c : string.toCharArray()) {
      input.sendString(String.valueOf(c), keyWait, keyModWait);
      BaseScript.waitMillis(keyPressWait);
    }
  }
}

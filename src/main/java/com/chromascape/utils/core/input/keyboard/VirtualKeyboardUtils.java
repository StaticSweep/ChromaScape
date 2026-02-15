package com.chromascape.utils.core.input.keyboard;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.input.remoteinput.Kinput;
import com.chromascape.utils.core.state.BotState;
import com.chromascape.utils.core.state.StateManager;
import com.chromascape.utils.core.statistics.StatisticsManager;

/**
 * Provides high-level methods for simulating keyboard input using the Kinput API. This utility
 * supports sending typed characters, modifier keys (e.g., Shift, Ctrl), and directional keys (arrow
 * keys), with synchronized access for safe use in multithreaded environments.
 */
public class VirtualKeyboardUtils {

  private final Kinput kinput;

  /**
   * Constructs a VirtualKeyboardUtils instance that wraps a Kinput instance.
   *
   * @param kinput The Kinput backend used to emit keyboard events to the target window.
   */
  public VirtualKeyboardUtils(Kinput kinput) {
    this.kinput = kinput;
  }

  /**
   * Sends a keyboard event for a character key (e.g., letters, numbers, symbols). Intended for use
   * with regular text input simulation.
   *
   * @param keyChar The character key to send.
   */
  public synchronized void sendKeyChar(char keyChar) {
    BaseScript.checkInterrupted();
    StateManager.setState(BotState.ACTING);
    StatisticsManager.incrementInputs();
    kinput.sendCharEvent(keyChar);
  }

  /**
   * Updates the necessary states of the bot for the {@link BaseScript}'s stop() function and
   * updates the BotState for the UI.
   */
  private void prepareInput() {
    BaseScript.checkInterrupted();
    StateManager.setState(BotState.ACTING);
    StatisticsManager.incrementInputs();
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
    prepareInput();
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
    kinput.sendVirtualKeyEvent(eventId, keyId, key);
  }

  /**
   * Sends a function key (F1-F12) using the KeyCode slot.
   *
   * @param eventId 401 to simulate a key press, or 402 to simulate a key release.
   * @param functionKeyNumber The number of the F key (e.g., 6 for F6 or 1 for F1).
   */
  public synchronized void sendFunctionKey(int eventId, int functionKeyNumber) {
    prepareInput();
    int keyId = 111 + functionKeyNumber; // F1 starts at 112
    kinput.sendVirtualKeyEvent(eventId, keyId, "F" + functionKeyNumber);
  }

  /**
   * Sends a keyboard event for an arrow (directional) key.
   *
   * @param eventId 401 to simulate a key press, or 402 to simulate a key release.
   * @param key The arrow direction. Acceptable values: "up", "down", "left", "right".
   * @throws IllegalArgumentException if the arrow direction is invalid.
   */
  public synchronized void sendArrowKey(int eventId, String key) {
    prepareInput();
    int keyId =
        switch (key.toLowerCase()) {
          case "left" -> 37;
          case "up" -> 38;
          case "right" -> 39;
          case "down" -> 40;
          default -> throw new IllegalArgumentException("Invalid arrow key: " + key);
        };
    kinput.sendVirtualKeyEvent(eventId, keyId, key);
  }
}

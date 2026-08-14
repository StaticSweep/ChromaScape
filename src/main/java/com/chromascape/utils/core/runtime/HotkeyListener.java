package com.chromascape.utils.core.runtime;

import com.chromascape.base.BaseScript;
import com.chromascape.controller.Controller;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A global keyboard listener that monitors specific hotkeys to stop the running Controller.
 *
 * <p>This class uses JNativeHook to register a low-level global keyboard hook, allowing it to
 * detect key presses even when the application is not in focus. Pressing both the equals (`=`) and
 * minus (`-`) keys simultaneously will trigger the {@link Controller#shutdown()} method.
 */
public class HotkeyListener implements NativeKeyListener {

  private final BaseScript baseScript;

  // These track whether the shutdown hotkeys are currently being held
  private boolean equals = false;
  private boolean minus = false;

  /**
   * Constructs a new HotkeyListener bound to a specific Controller instance.
   *
   * @param baseScript The controller whose shutdown method will be triggered by the hotkey.
   */
  public HotkeyListener(final BaseScript baseScript) {
    this.baseScript = baseScript;
  }

  /**
   * Starts listening for global keyboard events by registering a native hook. Suppresses internal
   * JNativeHook logging to prevent console noise.
   */
  public void start() {
    // Disable default noisy logging from JNativeHook
    Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
    logger.setLevel(Level.OFF);

    try {
      if (!GlobalScreen.isNativeHookRegistered()) {
        GlobalScreen.registerNativeHook();
      }
    } catch (NativeHookException e) {
      System.err.println("Failed to register native hook: " + e.getMessage());
      return;
    }

    GlobalScreen.addNativeKeyListener(this);
  }

  /** Stops listening for global keyboard events and unregisters the native hook. */
  public void stop() {
    try {
      GlobalScreen.unregisterNativeHook();
      System.out.println("Unregistered native hook. Exiting.");
    } catch (NativeHookException ex) {
      System.err.println("Failed to unregister: " + ex.getMessage());
    }
  }

  /**
   * Called when a key is pressed. If both the equals and minus keys are held down at the same time,
   * the {@link Controller#shutdown()} method is called.
   *
   * @param key The key press event captured by the native listener.
   */
  @Override
  public void nativeKeyPressed(final NativeKeyEvent key) {
    if (key.getKeyCode() == NativeKeyEvent.VC_EQUALS) {
      equals = true;
      if (minus) {
        baseScript.stop();
      }
    } else if (key.getKeyCode() == NativeKeyEvent.VC_MINUS) {
      minus = true;
      if (equals) {
        baseScript.stop();
      }
    }
  }

  /**
   * Called when a key is released. Clears the relevant hotkey flags.
   *
   * @param e The key release event captured by the native listener.
   */
  @Override
  public void nativeKeyReleased(NativeKeyEvent e) {
    if (e.getKeyCode() == NativeKeyEvent.VC_EQUALS) {
      equals = false;
    } else if (e.getKeyCode() == NativeKeyEvent.VC_MINUS) {
      minus = false;
    }
  }
}

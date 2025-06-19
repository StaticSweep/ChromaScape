package com.chromascape.utils.core.runtime;

import com.chromascape.controller.Controller;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;

import java.util.logging.Level;
import java.util.logging.Logger;

public class HotkeyListener implements NativeKeyListener {

    private final Controller controller;

    // These are the booleans for if the hotkeys are pressed
    private boolean equals = false;
    private boolean minus = false;

    public HotkeyListener(final Controller controller) {
        this.controller = controller;
    }

    public void start() {
        // Disable default noisy logging
        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        logger.setLevel(Level.OFF);

        // Tries to register a keyboard hook if there isn't one already
        try {
            if (!GlobalScreen.isNativeHookRegistered()) {
                GlobalScreen.registerNativeHook();
            }
        } catch (NativeHookException e) {
            System.err.println("Failed to register native hook: " + e.getMessage());
            return;
        }

        // Hooks on to the Controller
        GlobalScreen.addNativeKeyListener(this);
    }

    /**
     * Unregisters the key listener hook and disables it completely.
     */
    public void stop() {
        try {
            GlobalScreen.unregisterNativeHook();
            System.out.println("Unregistered native hook. Exiting.");
        } catch (NativeHookException ex) {
            System.err.println("Failed to unregister: " + ex.getMessage());
        }
    }

    /**
     * If a key is pressed this subroutine checks if both hotkeys are pressed without release of one another.
     * This will initiate a pause in the Controller
     *
     * @param key inherited constraint, the key being pressed
     */
    @Override
    public void nativeKeyPressed(final NativeKeyEvent key) {
        System.out.println("Key pressed: " + key.getKeyCode());

        if (key.getKeyCode() == NativeKeyEvent.VC_EQUALS) {
            equals = true;
            if (minus) {
                System.out.println("Minus + Equals");
                controller.pause();
            }
        } else if (key.getKeyCode() == NativeKeyEvent.VC_MINUS) {
            minus = true;
            if (equals) {
                System.out.println("Equals + Minus");
                controller.pause();
            }
        }
    }

    /**
     * Checks if a key is released and sets both flags to false.
     *
     * @param e inherited constraint, the key being released
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

package com.chromascape.utils.core.input.keyboard;

import com.chromascape.utils.core.input.remoteinput.KInput;

public class VirtualKeyboardUtils {

    KInput kInput;

    /**
     * A simplified wrapper for the keyboard aspects of KInput.
     * Use this class to send keyboard inputs.
     *
     * @param kInput The KInput object.
     */
    public VirtualKeyboardUtils(KInput kInput) {
        this.kInput = kInput;
    }

    /**
     * To send a key character event.
     *
     * @param eventID - 401 to press/ 402 to release.
     * @param keyChar - The key character to send.
     */
    public synchronized void sendKeyChar(int eventID, char keyChar) {
        kInput.sendKeyEvent(eventID, keyChar);
    }

    /**
     * To send a modifier key event.
     *
     * @param eventID - 401 to press/ 402 to release.
     * @param key - The key modifier to send (shift, enter, alt or ctrl).
     */
    public synchronized void sendModifierKey(int eventID, String key) {
        int keyID = switch (key.toLowerCase()) {
            case "shift" -> 16;
            case "enter" -> 10; // technically linefeed
            case "alt" -> 18;
            case "ctrl" -> 17;
            default -> throw new IllegalArgumentException("Invalid modifier key: " + key);
        };
        kInput.sendModifierKey(eventID, key, keyID);
    }

    /**
     * To send an arrow key event.
     *
     * @param eventID - 401 to press/ 402 to release.
     * @param key - The key modifier to send (up, down, left or right).
     */
    public synchronized void sendArrowKey(int eventID, String key) {
        int keyID = switch (key.toLowerCase()) {
            case "left" -> 37;
            case "right" -> 39;
            case "up" -> 38;
            case "down" -> 40;
            default -> throw new IllegalArgumentException("Invalid arrow key: " + key);
        };
        kInput.sendArrowKey(eventID, key, keyID);
    }
}

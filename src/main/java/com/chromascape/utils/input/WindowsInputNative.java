package com.chromascape.utils.input;

import com.sun.jna.Library;
import com.sun.jna.Native;

import java.util.Random;

public class WindowsInputNative {

    // JNA interface for KInputCtrl64.dll
    public interface KInput extends Library {
        boolean KInput_Create(int pid);
        boolean KInput_Delete(int pid);
        boolean KInput_FocusEvent(int pid, int eventID);
        boolean KInput_KeyEvent(int pid, int eventID, long when, int modifiers, int keyCode, short keyChar, int keyLocation);
        boolean KInput_MouseEvent(int pid, int eventID, long when, int modifiers, int x, int y, int clickCount, boolean popupTrigger, int button);
        boolean KInput_MouseWheelEvent(int pid, int eventID, long when, int modifiers, int x, int y, int clickCount, boolean popupTrigger, int scrollType, int scrollAmount, int wheelRotation);
    }

    private static final int FOCUS_GAINED = 1004;
    private static final int FOCUS_LOST = 1005;

    private final int pid;
    private final KInput kinput;

    // Mouse event IDs
    private static class MouseEventType {
        static final int MOUSE_CLICK = 500;
        static final int MOUSE_PRESS = 501;
        static final int MOUSE_RELEASE = 502;
        static final int MOUSE_MOVE = 503;
        static final int MOUSE_ENTER = 504;
        static final int MOUSE_EXIT = 505;
        static final int MOUSE_DRAG = 506;
        static final int MOUSE_WHEEL = 507;
    }

    // Mouse buttons IDs
    private static class MouseButton {
        static final int NONE = 0;
        static final int LEFT = 1;
        static final int MIDDLE = 2;
        static final int RIGHT = 3;
    }

    // Key event IDs
    private static class KeyEventType {
        static final int KEY_TYPED = 400;
        static final int KEY_PRESSED = 401;
        static final int KEY_RELEASED = 402;
    }

    // Load library once
    private static KInput loadLibrary() {
        try {
            // Set jna.library.path to working directory for DLL loading
            String workingDir = System.getProperty("user.dir");
            System.setProperty("jna.library.path", workingDir);
//            System.out.println("Loading KInputCtrl64.dll from working directory: " + workingDir);

            // Load the DLL by name (without path or extension)
            return Native.load("KInputCtrl64", KInput.class);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to load KInputCtrl64.dll", e);
        }
    }

    public WindowsInputNative(int pid) {
        this.pid = pid;
        this.kinput = loadLibrary();
        if (!kinput.KInput_Create(pid)) {
            throw new RuntimeException("Failed to create KInput instance for PID: " + pid);
        }
    }

    // Helper: current time in millis for event timestamps
    private long now() {
        return System.currentTimeMillis();
    }

    // Helper sleep for human-like click timing
    private void sleepHumanClick() {
        try {
            Random rand = new Random();
            Thread.sleep(rand.nextInt(20, 40));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Focus event to ensure target window has focus before sending input
    private synchronized void focus() {
        boolean result = kinput.KInput_FocusEvent(pid, FOCUS_GAINED);
        if (!result) {
            throw new RuntimeException("Focus event failed " + pid);
        }
    }

    public synchronized void clickLeft(int x, int y) {
        focus();
        if (!kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_PRESS, now(), 1, x, y, 1, false, MouseButton.LEFT)) {
            throw new RuntimeException("Left mouse press event failed");
        }
        sleepHumanClick();
        if (!kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_RELEASE, now(), 1, x, y, 1, false, MouseButton.LEFT)) {
            throw new RuntimeException("Left mouse release event failed");
        }
    }

    public synchronized void clickRight(int x, int y) {
        focus();
        if (!kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_PRESS, now(), 0, x, y, 1, false, MouseButton.RIGHT)) {
            throw new RuntimeException("Right mouse press event failed");
        }
        sleepHumanClick();
        if (!kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_RELEASE, now(), 0, x, y, 1, false, MouseButton.RIGHT)) {
            throw new RuntimeException("Right mouse release event failed");
        }
    }

    public synchronized void moveMouse(int x, int y) {
        focus();
        if (!kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_ENTER, now(), 0, x, y, 0, false, MouseButton.NONE)) {
            throw new RuntimeException("Mouse enter event failed");
        }
        if (!kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_MOVE, now(), 0, x, y, 0, false, MouseButton.NONE)) {
            throw new RuntimeException("Mouse move event failed");
        }
    }

    public synchronized void sendKeyEvent(int eventID, char keyChar) {
        focus();
        boolean result = kinput.KInput_KeyEvent(pid, eventID, now(), 0, 0, (short) keyChar, 0);
        if (!result) {
            throw new RuntimeException("Key event failed for char: " + keyChar);
        }
    }

    public synchronized void sendModifierKey(int eventID, String key) {
        focus();
        int keyID = switch (key.toLowerCase()) {
            case "shift" -> 16;
            case "enter" -> 10;
            case "alt" -> 18;
            default -> throw new IllegalArgumentException("Invalid modifier key: " + key);
        };
        boolean result = kinput.KInput_KeyEvent(pid, eventID, now(), 0, keyID, (short) 0, 0);
        if (!result) {
            throw new RuntimeException("Modifier key event failed for key: " + key);
        }
    }

    public synchronized void sendArrowKey(int eventID, String key) {
        focus();
        int keyID = switch (key.toLowerCase()) {
            case "left" -> 37;
            case "right" -> 39;
            case "up" -> 38;
            case "down" -> 40;
            default -> throw new IllegalArgumentException("Invalid arrow key: " + key);
        };
        boolean result = kinput.KInput_KeyEvent(pid, eventID, now(), 0, keyID, (short) 0, 0);
        if (!result) {
            throw new RuntimeException("Arrow key event failed for key: " + key);
        }
    }

    public synchronized void destroy() {
        if (!kinput.KInput_Delete(pid)) {
            throw new RuntimeException("Failed to delete KInput instance");
        }
    }
}

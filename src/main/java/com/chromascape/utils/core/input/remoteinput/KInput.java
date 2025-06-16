package com.chromascape.utils.core.input.remoteinput;

import com.sun.jna.Library;
import com.sun.jna.Native;

import java.util.Random;

public class KInput {

    // JNA interface for KInputCtrl64.dll
    public interface KInputInterface extends Library {
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
    private final KInputInterface kinput;

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

    // Load library once
    private static KInputInterface loadLibrary() {
        try {
            // Set jna.library.path to working directory for DLL loading
            String workingDir = System.getProperty("user.dir");
            System.setProperty("jna.library.path", workingDir);
//            System.out.println("Loading KInputCtrl64.dll from working directory: " + workingDir);

            // Load the DLL by name (without path or extension)
            return Native.load("KInputCtrl64", KInputInterface.class);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to load KInputCtrl64.dll", e);
        }
    }

    public KInput(int pid) {
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
            Thread.sleep(rand.nextInt(20, 60));
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
        boolean result1 = kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_PRESS, now(), 0, x, y, 1, false, MouseButton.RIGHT);
        if (!result1) {
            throw new RuntimeException("Right mouse press event failed");
        }
        sleepHumanClick();
        boolean result2 = kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_RELEASE, now(), 0, x, y, 1, false, MouseButton.RIGHT);
        if (!result2) {
            throw new RuntimeException("Right mouse release event failed");
        }
    }

    public synchronized void middleHold(int x, int y, int durationMs) throws InterruptedException {
        focus();
        if (!kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_PRESS, now(), 1, x, y, 1, false, MouseButton.MIDDLE)) {
            throw new RuntimeException("Left mouse press event failed");
        }
        Thread.sleep(durationMs);
        if (!kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_RELEASE, now(), 1, x, y, 1, false, MouseButton.MIDDLE)) {
            throw new RuntimeException("Left mouse release event failed");
        }
    }

    public synchronized void moveMouse(int x, int y) {
        focus();
        boolean result1 = kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_ENTER, now(), 0, x, y, 0, false, MouseButton.NONE);
        if (!result1) {
            throw new RuntimeException("Mouse enter event failed");
        }
        boolean result2 = kinput.KInput_MouseEvent(pid, MouseEventType.MOUSE_MOVE, now(), 0, x, y, 0, false, MouseButton.NONE);
        if (!result2) {
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

    public synchronized void sendModifierKey(int eventID, String key, int keyID) {
        focus();
        boolean result = kinput.KInput_KeyEvent(pid, eventID, now(), 0, keyID, (short) 0, 0);
        if (!result) {
            throw new RuntimeException("Modifier key event failed for key: " + key);
        }
    }

    public synchronized void sendArrowKey(int eventID, String key, int keyID) {
        focus();
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

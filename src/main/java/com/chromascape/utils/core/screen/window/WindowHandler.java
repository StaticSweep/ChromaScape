package com.chromascape.utils.core.screen.window;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import com.sun.jna.win32.StdCallLibrary;

import java.util.concurrent.atomic.AtomicReference;

public class WindowHandler {

    private final String windowName;

    public WindowHandler(String windowName) {
        this.windowName = windowName;
    }

    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);
        void EnumWindows(WNDENUMPROC lpEnumFunc, Pointer arg);
        void GetWindowTextA(HWND hWnd, byte[] lpString, int nMaxCount);
    }

    public HWND getTargetWindow() {
        AtomicReference<HWND> targetHwnd = new AtomicReference<>();
        User32 user32 = User32.INSTANCE;

        user32.EnumWindows((hWnd, arg) -> {
            byte[] buffer = new byte[512];
            user32.GetWindowTextA(hWnd, buffer, 512);
            String title = Native.toString(buffer);

            if (title.trim().equals(windowName)) {
                System.out.println("Found window: " + title);
                targetHwnd.set(hWnd);
                return false; // stop enumerating
            }
            return true;
        }, null);

        return targetHwnd.get(); // null if not found
    }
}

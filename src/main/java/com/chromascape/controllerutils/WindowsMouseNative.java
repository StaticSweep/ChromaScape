package com.chromascape.controllerutils;

import com.sun.jna.*;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.win32.*;
import java.awt.Point;
import java.util.List;

public class WindowsMouseNative {

    // Constants from WinUser.h
    private static final int INPUT_MOUSE = 0;
    private static final int MOUSEEVENTF_MOVE = 0x0001;
    private static final int MOUSEEVENTF_ABSOLUTE = 0x8000;
    private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    private static final int MOUSEEVENTF_LEFTUP = 0x0004;
    private static final int MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private static final int MOUSEEVENTF_RIGHTUP = 0x0010;

    // Screen resolution constants for absolute positioning
    private static final int SCREEN_WIDTH = 65535;
    private static final int SCREEN_HEIGHT = 65535;

    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);
        int SendInput(int nInputs, INPUT[] pInputs, int cbSize);
    }

    public static class INPUT extends Structure {
        public DWORD type;
        public MOUSEINPUT mi;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("type", "mi");
        }
    }

    public static class MOUSEINPUT extends Structure {
        public LONG dx;
        public LONG dy;
        public DWORD mouseData;
        public DWORD dwFlags;
        public DWORD time;
        public BaseTSD.ULONG_PTR dwExtraInfo;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dx", "dy", "mouseData", "dwFlags", "time", "dwExtraInfo");
        }
    }

    private Point screenToAbsolute(Point p) {
        int screenX = (int) ((p.x / (double) java.awt.Toolkit.getDefaultToolkit().getScreenSize().width) * SCREEN_WIDTH);
        int screenY = (int) ((p.y / (double) java.awt.Toolkit.getDefaultToolkit().getScreenSize().height) * SCREEN_HEIGHT);
        return new Point(screenX, screenY);
    }

    public void moveMouse(Point p) {
        Point abs = screenToAbsolute(p);
        INPUT input = new INPUT();
        input.type = new DWORD(INPUT_MOUSE);
        input.mi = new MOUSEINPUT();
        input.mi.dx = new LONG(abs.x);
        input.mi.dy = new LONG(abs.y);
        input.mi.mouseData = new DWORD(0);
        input.mi.dwFlags = new DWORD(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE);
        input.mi.time = new DWORD(0);
        input.mi.dwExtraInfo = new ULONG_PTR(0);

        sendInput(input);
    }

    public void clickLeft(int delayMS) throws InterruptedException {
        sendInput(mouseEvent(MOUSEEVENTF_LEFTDOWN));
        Thread.sleep(delayMS);
        sendInput(mouseEvent(MOUSEEVENTF_LEFTUP));
    }

    public void clickRight(int delayMS) throws InterruptedException {
        sendInput(mouseEvent(MOUSEEVENTF_RIGHTDOWN));
        Thread.sleep(delayMS);
        sendInput(mouseEvent(MOUSEEVENTF_RIGHTUP));
    }

    private void sendInput(INPUT input) {
        INPUT[] inputs = {input};
        User32.INSTANCE.SendInput(1, inputs, input.size());
    }

    private INPUT mouseEvent(int flags) {
        INPUT input = new INPUT();
        input.type = new DWORD(INPUT_MOUSE);
        input.mi = new MOUSEINPUT();
        input.mi.dx = new LONG(0);
        input.mi.dy = new LONG(0);
        input.mi.mouseData = new DWORD(0);
        input.mi.dwFlags = new DWORD(flags);
        input.mi.time = new DWORD(0);
        input.mi.dwExtraInfo = new ULONG_PTR(0);
        return input;
    }
}

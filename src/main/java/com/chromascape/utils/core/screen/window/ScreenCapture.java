package com.chromascape.utils.core.screen.window;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;

import java.awt.AWTException;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

public class ScreenCapture {

    private final Robot robot;
    private final User32 user32;

    /**
     * Interface for accessing functions not available via JNA
     */
    public interface User32Extended extends User32 {
        void ClientToScreen(HWND hWnd, POINT point);
        User32Extended INSTANCE = Native.load("user32", User32Extended.class);
    }

    /**
     * Provides Screen capture utilities.
     *
     * @throws AWTException If the Robot cannot be instantiated.
     */
    public ScreenCapture() throws AWTException {
        this.robot = new Robot();
        user32 = User32.INSTANCE;
    }

    /**
     * Captures the visible content of the inner bounds of a specified window (no padding)
     * Uses the AWT Robot class and assumes the window is visible and unobstructed.
     *
     * @return The BufferedImage of the window in BGR format.
     */
    public BufferedImage captureWindow(HWND hwnd) throws AWTException {
        BufferedImage argb = robot.createScreenCapture(getWindowBounds(hwnd));
        BufferedImage bgr = new BufferedImage(argb.getWidth(), argb.getHeight(), BufferedImage.TYPE_3BYTE_BGR);

        Graphics g = bgr.getGraphics();
        g.drawImage(argb, 0, 0, null);
        g.dispose();
        return bgr;
    }

    /**
     * Captures the visible content of a specified zone
     * Uses the AWT Robot class and assumes the zone is visible and unobstructed.
     *
     * @return The BufferedImage of the zone in BGR format.
     */
    public BufferedImage captureZone(Rectangle zone) throws AWTException {
        BufferedImage argb = robot.createScreenCapture(zone);
        BufferedImage bgr = new BufferedImage(argb.getWidth(), argb.getHeight(), BufferedImage.TYPE_3BYTE_BGR);

        Graphics g = bgr.getGraphics();
        g.drawImage(argb, 0, 0, null);
        g.dispose();
        return bgr;
    }

    /**
     * Gets a specified window's inner bounds using the HWND.
     *
     * @return The Rectangle bounds of the window.
     */
    public Rectangle getWindowBounds(HWND hwnd){
        WinDef.RECT dimensions = new WinDef.RECT();
        user32.GetClientRect(hwnd, dimensions);

        WinDef.POINT clientTopLeft = new WinDef.POINT();

        clientTopLeft.x = 0;
        clientTopLeft.y = 0;

        User32Extended uex = User32Extended.INSTANCE;
        uex.ClientToScreen(hwnd, clientTopLeft);

        return new Rectangle(
                clientTopLeft.x,
                clientTopLeft.y,
                dimensions.right - dimensions.left,
                dimensions.bottom - dimensions.top
        );
    }

    /**
     * Focuses a specified window by restoring it then setting it to the foreground.
     */
    public void focusWindow(HWND hwnd) {
        user32.ShowWindow(hwnd, WinUser.SW_SHOW);
        user32.SetForegroundWindow(hwnd);
    }

}

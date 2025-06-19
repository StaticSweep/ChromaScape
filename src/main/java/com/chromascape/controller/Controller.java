package com.chromascape.controller;

import com.chromascape.utils.core.input.keyboard.VirtualKeyboardUtils;
import com.chromascape.utils.core.runtime.HotkeyListener;
import com.chromascape.utils.core.input.mouse.VirtualMouseUtils;
import com.chromascape.utils.core.input.remoteinput.KInput;
import com.chromascape.utils.core.screen.window.ScreenCapture;
import com.chromascape.utils.core.screen.window.WindowHandler;
import com.chromascape.utils.domain.zones.ZoneManager;

import java.awt.Rectangle;

public class Controller {

    private final HotkeyListener hotkeyListener;

    private final KInput kInput;

    private final VirtualMouseUtils virtualMouseUtils;

    private final VirtualKeyboardUtils virtualKeyboardUtils;

    private final ScreenCapture screenCapture;

    private final ZoneManager zoneManager;

    private boolean running = false;

    public Controller() throws Exception {
        running = true;
        kInput = new KInput(32736);
        hotkeyListener = new HotkeyListener(this);
        hotkeyListener.start();
        screenCapture = new ScreenCapture();
        screenCapture.focusWindow(WindowHandler.getTargetWindow());
        Rectangle bounds = screenCapture.getWindowBounds(WindowHandler.getTargetWindow());
        virtualMouseUtils = new VirtualMouseUtils(kInput, bounds);
        virtualKeyboardUtils = new VirtualKeyboardUtils(kInput);
        zoneManager = new ZoneManager(screenCapture, false);
    }

    public void shutdown(){
        running = false;
        kInput.destroy();
    }

    public void pause(){
        running = false;
        hotkeyListener.stop();
    }

    public VirtualMouseUtils Mouse() {
        if (running) {
            return virtualMouseUtils;
        } else {
            System.out.println("Attempted to access virtual mouse while bot is not running.");
            return null;
        }
    }

    public VirtualKeyboardUtils Keyboard() {
        if (running) {
            return virtualKeyboardUtils;
        } else {
            System.out.println("Attempted to access virtual keyboard while bot is not running.");
            return null;
        }
    }

    public ZoneManager Zones() {
        if (running) {
            return zoneManager;
        } else {
            System.out.println("Attempted to access zones while bot is not running.");
            return null;
        }
    }

    public ScreenCapture ScreenCapture() {
        if (running) {
            return screenCapture;
        } else {
            System.out.println("Attempted to access screen capture while bot is not running.");
            return null;
        }
    }
}

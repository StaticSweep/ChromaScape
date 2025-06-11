package com.chromascape.controller;

import com.chromascape.utils.core.logic.HotkeyListener;
import com.chromascape.utils.core.input.mouse.VirtualMouseUtils;
import com.chromascape.utils.core.input.remoteinput.KInput;

public class Controller {

    private final HotkeyListener hotkeyListener;

    private final VirtualMouseUtils virtualMouseUtils;

    private boolean running = false;

    public Controller() {
        KInput nativeMouse = new KInput(55724);
        this.hotkeyListener = new HotkeyListener(this);
        this.virtualMouseUtils = new VirtualMouseUtils(nativeMouse, 1920, 1080);
    }

    public void shutdown(){
        running = false;
        // future cleanup n stuffs
    }

    public void start(){
        running = true;
        hotkeyListener.start();
    }

    public void pause(){
        running = false;
        hotkeyListener.stop();
    }

    public VirtualMouseUtils getMouse() {
        if (running) {
            return virtualMouseUtils;
        } else {
            System.out.println("Attempted to access virtual mouse while bot is not running.");
            return null;
        }
    }

}

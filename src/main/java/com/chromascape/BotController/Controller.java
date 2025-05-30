package com.chromascape.BotController;

import com.chromascape.controllerutils.HotkeyListener;
import com.chromascape.controllerutils.VirtualMouseUtils;

import java.awt.*;

public class Controller {

    private final HotkeyListener hotkeyListener;
    private final VirtualMouseUtils virtualMouseUtils;

    private boolean running = false;

    public Controller() {
        this.hotkeyListener = new HotkeyListener(this);
        this.virtualMouseUtils = new VirtualMouseUtils();
    }

    public void shutdown(){
        running = false;
//        future cleanup n stuffs
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

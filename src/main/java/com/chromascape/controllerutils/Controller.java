package com.chromascape.controllerutils;

public class Controller {

    private final HotkeyListener hotkeyListener;

    public Controller() {
        this.hotkeyListener = new HotkeyListener(this);
    }

    public void shutdown(){
//        future cleanup n stuffs
    }

    public void start(){
        hotkeyListener.start();
    }

    public void pause(){
        hotkeyListener.stop();
    }
}

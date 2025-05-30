package com.chromascape;

import com.chromascape.BotController.Controller;
import com.chromascape.controllerutils.VirtualMouseUtils;
import com.chromascape.controllerutils.WindowsInputNative;
//import com.chromascape.controllerutils.WindowsMouseNative;

import java.awt.*;
import java.io.InputStream;
import java.net.URL;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        Controller controller = new Controller();
        controller.start();
        Point point = new Point(800, 800);
        controller.getMouse().moveTo(point, "medium");
        Thread.sleep(500);
        Point point1 = new Point(1600, 200);
        controller.getMouse().moveTo(point1, "medium");
    }
}

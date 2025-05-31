package com.chromascape;

import com.chromascape.BotController.Controller;

import java.awt.*;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        Controller controller = new Controller();
        controller.start();
        Point point = new Point(800, 800);
        controller.getMouse().moveTo(point, "medium");
        Thread.sleep(500);
        Point point1 = new Point(1600, 200);
        controller.getMouse().moveTo(point1, "medium");
//        Point point2 = new Point(1400, 200);
//        controller.getMouse().moveTo(point2, "slow");
    }
}

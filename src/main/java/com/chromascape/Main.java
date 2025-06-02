package com.chromascape;

import com.chromascape.controller.Controller;

import java.awt.*;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        Controller controller = new Controller();
        controller.start();
        Point point = new Point(800, 800);
        Point point1 = new Point(1600, 200);
        Point point2 = new Point(1400, 200);
        Point point3 = new Point(1710, 590);
        Point point4 = new Point(120, 900);
        Point point5 = new Point(300, 200);
        for (int i = 0; i < 5; i++) {
            controller.getMouse().moveTo(point, "fast");
            controller.getMouse().moveTo(point1, "medium");
            controller.getMouse().moveTo(point2, "slow");
            controller.getMouse().moveTo(point3, "medium");
            controller.getMouse().moveTo(point4, "fast");
            controller.getMouse().moveTo(point5, "medium");
        }
    }
}

package com.chromascape.scripts;

import com.chromascape.controller.Controller;

import java.awt.*;
import java.util.Random;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        Random rand = new Random();
        Controller controller = new Controller();
        controller.start();
        Point point = new Point(800, 800);
        Point point1 = new Point(1600, 200);
        Point point2 = new Point(1400, 200);
        Point point3 = new Point(1710, 590);
        Point point4 = new Point(120, 900);
        Point point5 = new Point(300, 200);
        Point point6 = new Point(350, 260);
        for (int i = 0; i < 5; i++) {
            controller.getMouse().moveTo(point, "medium");
            Thread.sleep(500);
            if (rand.nextBoolean()) {
                controller.getMouse().moveToAndOvershoot(point1, "fastest");
            } else {
                controller.getMouse().moveTo(point1, "fast");
            }
            Thread.sleep(500);
            controller.getMouse().moveTo(point2, "fastest");
            Thread.sleep(500);
            controller.getMouse().moveTo(point3, "medium");
            Thread.sleep(500);
            if (rand.nextBoolean()) {
                controller.getMouse().moveToPause(point4, "fastest");
            } else {
                controller.getMouse().moveTo(point4, "fast");
            }
            Thread.sleep(500);
            controller.getMouse().moveTo(point5, "medium");
            Thread.sleep(500);
            controller.getMouse().moveTo(point6, "slow");
            Thread.sleep(500);
        }
    }
}

package com.chromascape.controllerutils;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static java.lang.Thread.sleep;

// VirtualMouseUtils.java
class VirtualMouseUtils {
    private Point currentPosition = new Point(0,0);
    private final WindowsMouseNative nativeMouse;
    Random rand = new Random();

    public VirtualMouseUtils() {
        nativeMouse = new WindowsMouseNative();
    }

    public void moveTo(Point target) throws InterruptedException {
        List<Point> path = generatePath(currentPosition, target);
        for (Point p : path) {
            nativeMouse.moveMouse(p);
            currentPosition = p;
            sleep(10); // smoothness control
        }
    }

    public void clickLeft() throws InterruptedException {
        nativeMouse.clickLeft(rand.nextInt(70, 110));
    }

    public void clickRight() throws InterruptedException {
        nativeMouse.clickRight(rand.nextInt(70, 110));
    }

    // path generation example
    private List<Point> generatePath(Point start, Point end) {
        // implementation returning intermediate points
        return new ArrayList<Point>(Arrays.asList(start, end));
    }
}

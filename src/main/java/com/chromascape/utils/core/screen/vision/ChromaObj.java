package com.chromascape.utils.core.screen.vision;

import org.bytedeco.opencv.opencv_core.Mat;

import java.awt.Rectangle;

public class ChromaObj {
    private final int id;
    private final Mat contour;
    private final Rectangle boundingBox;

    public ChromaObj(int id, Mat contour, Rectangle boundingBox) {
        this.id = id;
        this.contour = contour;
        this.boundingBox = boundingBox;
    }

    public int getId() { return id; }
    public Mat getContour() { return contour; }
    public Rectangle getBoundingBox() { return boundingBox; }
}

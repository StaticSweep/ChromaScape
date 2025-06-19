package com.chromascape.utils.core.screen.colour;

import org.bytedeco.opencv.opencv_core.Scalar;

public class ColourObj {
    private final String name;
    private final Scalar HSVMin;
    private final Scalar HSVMax;

    public ColourObj(String name, Scalar HSVMin, Scalar HSVMax) {
        this.name = name;
        this.HSVMin = new Scalar(HSVMin.get(0), HSVMin.get(1), HSVMin.get(2), HSVMin.get(3));
        this.HSVMax = new Scalar(HSVMax.get(0), HSVMax.get(1), HSVMax.get(2), HSVMax.get(3));
    }

    public Scalar getHSVMin() {
        return new Scalar(HSVMin.get(0), HSVMin.get(1), HSVMin.get(2), HSVMin.get(3));
    }

    public Scalar getHSVMax() {
        return new Scalar(HSVMax.get(0), HSVMax.get(1), HSVMax.get(2), HSVMax.get(3));
    }

    public String getName() {
        return name;
    }
}

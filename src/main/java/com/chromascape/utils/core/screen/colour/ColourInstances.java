package com.chromascape.utils.core.screen.colour;

import org.bytedeco.opencv.opencv_core.Scalar;

import java.util.List;

public class ColourInstances {

    private static final List<ColourObj> COLOURS = List.of(
            new ColourObj("Yellow", new Scalar(25, 200, 200, 0), new Scalar(35, 255, 255, 0)),
            new ColourObj("Red", new Scalar(0, 150, 50, 0), new Scalar(0, 255, 255, 0))
            // add as needed
    );

    public static List<ColourObj> getColours() {
        return COLOURS;
    }

    public static ColourObj getByName(String name) {
        for (ColourObj colour : COLOURS) {
            if (colour.getName().equals(name)) {
                return colour;
            }
        }
        return null;
    }
}

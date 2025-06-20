package com.chromascape.scripts;

import com.chromascape.controller.Controller;
import com.chromascape.utils.core.input.distribution.ClickDistribution;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.topology.ChromaObj;
import com.chromascape.utils.core.screen.topology.ContourHandler;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {
        Controller controller = new Controller();

        List<ChromaObj> yellowObjects = ContourHandler.getChromaObjsInColour(
                controller.Zones().getGameView(),
                ColourInstances.getByName("Red")
        );

        // Make sure the list is not empty and the bounding box is not null
        if (!yellowObjects.isEmpty() && yellowObjects.get(0).getBoundingBox() != null) {
            Rectangle boundingBox = yellowObjects.get(0).getBoundingBox();
            Point targetPoint = ClickDistribution.generateRandomPoint(boundingBox);
            controller.Mouse().moveTo(targetPoint, "medium");
        } else {
            // handle the case where no yellow object or bounding box found
            System.err.println("No yellow chroma object with bounding box found.");
        }

    }
}

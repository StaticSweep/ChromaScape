package com.chromascape.scripts;

import com.chromascape.controller.Controller;
import com.chromascape.utils.core.input.distribution.ClickDistribution;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.topology.ContourHandler;

public class Main {

    public static void main(String[] args) throws Exception {
        Controller controller = new Controller();

//        controller.Mouse().moveTo(
//                ClickDistribution.generateRandomPoint(
//                        controller.Zones().getInventorySlots().get(5)
//                ), "medium");

//        controller.Mouse().moveTo(
//                ClickDistribution.generateRandomPoint(
//                        ContourHandler.getChromaObjsInColour(
//                                controller.Zones().getGameView(),
//                                ColourInstances.getByName("Red")
//                        ).get(0).getBoundingBox()
//                ),
//                "medium"
//        );
    }
}

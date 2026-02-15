package com.chromascape.scripts;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.actions.PointSelector;
import com.chromascape.utils.core.input.distribution.ClickDistribution;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DemoWineScript serves as a tutorial and example script to demonstrate how to automate basic tasks
 * using the ChromaScape framework.
 *
 * <p><b>Warning:</b> This script is NOT intended for actual use or to be run at all! Running it may
 * violate terms of service of the target application and result in a ban.
 *
 * <p>The script automates a simplified "wine making" task by interacting with a game UI through
 * template matching, clicking, and keyboard inputs.
 */
public class DemoWineScript extends BaseScript {

  private final Logger logger = LogManager.getLogger(this.getClass());

  private static final String grapes = "/images/user/Grapes.png";
  private static final String jugs = "/images/user/Jug_of_water.png";
  private static final String dumpBank = "/images/user/Dump_bank.png";
  private static final String unfermented = "/images/user/Unfermented.png";

  private static final int MAX_ATTEMPTS = 15;
  private static final int INVENT_SLOT_GRAPES = 13;
  private static final int INVENT_SLOT_JUGS = 14;

  private boolean bankFlag = true;

  /**
   * The core logic of the script. This function will loop repeatedly until {@link #stop()} is
   * called. You should avoid putting all your script logic directly inside this function, instead
   * split it up into other functions as shown below.
   */
  @Override
  protected void cycle() {
    if (bankFlag) {
      clickBank(); // Open the bank once at the start of the script
      waitRandomMillis(700, 900);
      bankFlag = false;
      // Cannot start in bank because UI needs to initialise
    }

    clickImage(grapes, "fast", 0.07); // Take out grapes
    waitRandomMillis(300, 600);

    clickImage(jugs, "slow", 0.065); // Take out water jugs
    waitRandomMillis(400, 500);

    pressEscape(); // Exit bank UI
    waitRandomMillis(600, 800);

    clickInventSlot(INVENT_SLOT_JUGS, "fast"); // Click the jugs of water in the inventory
    waitRandomMillis(400, 500);

    clickInventSlot(INVENT_SLOT_GRAPES, "slow"); // Use the jugs on the grapes to start making wine
    waitRandomMillis(800, 900);

    pressSpace(); // Accept the start button
    waitRandomMillis(17000, 18000); // Wait for wines to combine

    clickBank(); // Open the bank to drop off items
    waitRandomMillis(700, 900);

    clickImage(dumpBank, "medium", 0.055); // Put the fermenting wines in the bank to repeat
    waitRandomMillis(650, 750);

    if (checkIfImageInv(unfermented, 0.055)) { // Repeating because bank is weird
      controller().mouse().leftClick();
      waitRandomMillis(600, 800);
    }
  }

  /**
   * Simulates pressing the Escape key by sending the key press and release events to the client
   * keyboard controller.
   */
  private void pressEscape() {
    controller().keyboard().sendModifierKey(401, "esc");
    waitRandomMillis(80, 100);
    controller().keyboard().sendModifierKey(402, "esc");
  }

  /**
   * Simulates pressing the Space key by sending the key press and release events to the client
   * keyboard controller.
   */
  private void pressSpace() {
    controller().keyboard().sendModifierKey(401, "space");
    waitRandomMillis(300, 500);
    controller().keyboard().sendModifierKey(402, "space");
  }

  /**
   * Attempts to locate and click the purple bank object within the game view. It searches for
   * purple contours, then clicks a randomly distributed point inside the contour bounding box,
   * retrying up to a maximum number of attempts. Logs failures and stops the script if unable to
   * click successfully.
   */
  private void clickBank() {
    Point clickLocation = new Point();
    try {
      clickLocation =
          PointSelector.getRandomPointInColour(
              controller().zones().getGameView(), "Purple", MAX_ATTEMPTS);
    } catch (Exception e) {
      logger.error("Failed while generating bank click location: {}", String.valueOf(e));
      stop();
    }

    if (clickLocation == null) {
      logger.error("clickBank click location is null");
      stop();
    }

    try {
      controller().mouse().moveTo(clickLocation, "medium");
      controller().mouse().leftClick();
      logger.info("Clicked on purple bank object at {}", clickLocation);
    } catch (Exception e) {
      logger.error(e.getMessage());
      stop();
    }
  }

  /**
   * Searches for the provided image template within the current game view, then clicks a random
   * point within the detected bounding box if the match exceeds the defined threshold.
   *
   * @param imagePath the BufferedImage template to locate and click within the game view
   * @param speed the speed that the mouse moves to click the image
   * @param threshold the openCV threshold to decide if a match exists
   */
  private void clickImage(String imagePath, String speed, double threshold) {
    try {
      BufferedImage gameView = controller().zones().getGameView();
      Point clickLocation = PointSelector.getRandomPointInImage(imagePath, gameView, threshold);

      if (clickLocation == null) {
        logger.error("clickImage click location is null");
        stop();
      }

      controller().mouse().moveTo(clickLocation, speed);
      controller().mouse().leftClick();
      logger.info("Clicked on image at {}", clickLocation);

    } catch (Exception e) {
      logger.error("clickImage failed: {}", e.getMessage());
      stop();
    }
  }

  /**
   * Clicks a random point within the bounding box of a given inventory slot.
   *
   * @param slot the index of the inventory slot to click (0-27)
   * @param speed the speed that the mouse moves to click the image
   */
  private void clickInventSlot(int slot, String speed) {
    try {
      Rectangle boundingBox = controller().zones().getInventorySlots().get(slot);
      if (boundingBox == null || boundingBox.isEmpty()) {
        logger.info("Inventory slot {} not found.", slot);
        stop();
        return;
      }

      Point clickLocation = ClickDistribution.generateRandomPoint(boundingBox);

      if (clickLocation == null) {
        logger.error("clickInventSlot click location is null");
        stop();
      }

      controller().mouse().moveTo(clickLocation, speed);
      controller().mouse().leftClick();
      logger.info("Clicked inventory slot {} at {}", slot, clickLocation);

    } catch (Exception e) {
      logger.error("clickInventSlot failed: {}", e.getMessage());
      stop();
    }
  }

  /**
   * Checks if an image exists on the screen and returns a boolean referring to if it was detected.
   *
   * @param imagePath the path to the image being searched
   * @param threshold the openCV threshold to decide if a match exists
   * @return true if the image exists in the inventory slot 1, else false
   */
  private boolean checkIfImageInv(String imagePath, double threshold) {
    try {
      BufferedImage inventorySlot1 =
          ScreenManager.captureZone(controller().zones().getInventorySlots().get(0));
      Rectangle boundingBox = TemplateMatching.match(imagePath, inventorySlot1, threshold, false);

      if (boundingBox == null || boundingBox.isEmpty()) {
        logger.error("Template match failed: No valid inventory bounding box.");
        return false;
      }

      return true;

    } catch (Exception e) {
      logger.error("checkIfImageInv failed: {}", e.getMessage());
      stop();
    }
    return false;
  }
}

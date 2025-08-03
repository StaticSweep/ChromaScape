package com.chromascape.scripts;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.input.Sleeper;
import com.chromascape.utils.core.input.distribution.ClickDistribution;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.topology.ChromaObj;
import com.chromascape.utils.core.screen.topology.ColourContours;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.web.logs.LogService;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

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

  private final LogService logger;

  private static final String grapes = "/images/user/Grapes.png";
  private static final String jugs = "/images/user/Jug_of_water.png";
  private static final String dumpBank = "/images/user/Dump_bank.png";
  private static final String unfermented = "/images/user/Unfermented.png";

  private static final int MAX_ATTEMPTS = 15;
  private static final int INVENT_SLOT_GRAPES = 13;
  private static final int INVENT_SLOT_JUGS = 14;

  private boolean bankFlag = true;

  /**
   * Constructs a BaseScript.
   *
   * @param isFixed whether the client UI is fixed or resizable
   * @param duration the total runtime of the script in minutes
   * @param logger the logging service for recording events and progress
   */
  public DemoWineScript(boolean isFixed, int duration, LogService logger) {
    super(isFixed, duration, logger);
    this.logger = logger;
  }

  /**
   * The core logic of the script.
   *
   * <p>This method is called repeatedly in a loop by {@link #run()} for the specified duration.
   * Subclasses must override this method to implement their specific bot behavior.
   *
   * <p>Note: This method is called synchronously on the running thread.
   */
  @Override
  protected void cycle() {
    if (bankFlag) {
      clickBank(); // Open the bank once at the start of the script
      Sleeper.waitRandomMillis(700, 900);
      bankFlag = false;
      // Cannot start in bank because UI needs to initialise
    }

    clickImage(grapes, "fast", 0.07); // Take out grapes
    Sleeper.waitRandomMillis(300, 600);

    clickImage(jugs, "slow", 0.065); // Take out water jugs
    Sleeper.waitRandomMillis(400, 500);

    pressEscape(); // Exit bank UI
    Sleeper.waitRandomMillis(600, 800);

    clickInventSlot(INVENT_SLOT_JUGS, "fast"); // Click the jugs of water in the inventory
    Sleeper.waitRandomMillis(400, 500);

    clickInventSlot(INVENT_SLOT_GRAPES, "slow"); // Use the jugs on the grapes to start making wine
    Sleeper.waitRandomMillis(800, 900);

    pressSpace(); // Accept the start button
    Sleeper.waitRandomMillis(17000, 18000); // Wait for wines to combine

    clickBank(); // Open the bank to drop off items
    Sleeper.waitRandomMillis(700, 900);

    clickImage(dumpBank, "medium", 0.055); // Put the fermenting wines in the bank to repeat
    Sleeper.waitRandomMillis(650, 750);

    if (checkIfImageExists(unfermented, 0.055)) { // Repeating because bank is weird
      controller().mouse().leftClick();
      Sleeper.waitRandomMillis(600, 800);
    }
  }

  /**
   * Simulates pressing the Escape key by sending the key press and release events to the client
   * keyboard controller.
   */
  private void pressEscape() {
    controller().keyboard().sendModifierKey(401, "esc");
    Sleeper.waitRandomMillis(80, 100);
    controller().keyboard().sendModifierKey(402, "esc");
  }

  /**
   * Simulates pressing the Space key by sending the key press and release events to the client
   * keyboard controller.
   */
  private void pressSpace() {
    controller().keyboard().sendModifierKey(401, "space");
    Sleeper.waitRandomMillis(300, 500);
    controller().keyboard().sendModifierKey(402, "space");
  }

  /**
   * Attempts to locate and click the purple bank object within the game view. It searches for
   * purple contours, then clicks a randomly distributed point inside the contour bounding box,
   * retrying up to a maximum number of attempts. Logs failures and stops the script if unable to
   * click successfully.
   */
  private void clickBank() {
    List<ChromaObj> purpleObjs;
    try {
      purpleObjs =
          ColourContours.getChromaObjsInColour(
              controller().zones().getGameView(), ColourInstances.getByName("Purple"));
    } catch (Exception e) {
      logger.addLog(e.getMessage());
      stop();
      return;
    }

    if (purpleObjs.isEmpty()) {
      logger.addLog("No purple objects found");
      stop();
      return;
    }

    Point clickLocation;
    ChromaObj purpleObject = purpleObjs.get(0);

    int attempts = 0;
    clickLocation = ClickDistribution.generateRandomPoint(purpleObject.boundingBox());
    while (!ColourContours.isPointInContour(clickLocation, purpleObject.contour())
        && attempts < MAX_ATTEMPTS) {
      clickLocation = ClickDistribution.generateRandomPoint(purpleObject.boundingBox());
      attempts++;
    }
    logger.addLog("Attempts: " + attempts);
    if (attempts >= MAX_ATTEMPTS) {
      logger.addLog("Failed to find a valid point in purple contour.");
      stop();
      return;
    }

    try {
      controller().mouse().moveTo(clickLocation, "medium");
      controller().mouse().leftClick();
      logger.addLog("Clicked on purple bank object at " + clickLocation);
    } catch (Exception e) {
      logger.addLog(e.getMessage());
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
      Rectangle boundingBox = TemplateMatching.match(imagePath, gameView, threshold, false);

      if (boundingBox == null || boundingBox.isEmpty()) {
        logger.addLog("Template match failed: No valid bounding box.");
        stop();
        return;
      }

      Point clickLocation = ClickDistribution.generateRandomPoint(boundingBox);

      controller().mouse().moveTo(clickLocation, speed);
      controller().mouse().leftClick();
      logger.addLog("Clicked on image at " + clickLocation);

    } catch (Exception e) {
      logger.addLog("clickImage failed: " + e.getMessage());
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
        logger.addLog("Inventory slot " + slot + " not found.");
        stop();
        return;
      }

      Point clickLocation = ClickDistribution.generateRandomPoint(boundingBox);

      controller().mouse().moveTo(clickLocation, speed);
      controller().mouse().leftClick();
      logger.addLog("Clicked inventory slot " + slot + " at " + clickLocation);

    } catch (Exception e) {
      logger.addLog("clickInventSlot failed: " + e.getMessage());
      stop();
    }
  }

  /**
   * Checks if an image exists on the screen and returns a boolean referring to if it was detected.
   *
   * @param imagePath the path to the image being searched on screen
   * @param threshold the openCV threshold to decide if a match exists
   * @return true if the image exists on screen, else false
   */
  private boolean checkIfImageExists(String imagePath, double threshold) {
    try {
      BufferedImage gameView = controller().zones().getGameView();
      Rectangle boundingBox = TemplateMatching.match(imagePath, gameView, threshold, false);

      if (boundingBox == null || boundingBox.isEmpty()) {
        logger.addLog("Template match failed: No valid bounding box.");
        return false;
      }

      return true;

    } catch (Exception e) {
      logger.addLog("checkIfImageExists failed: " + e.getMessage());
      stop();
    }
    return false;
  }
}

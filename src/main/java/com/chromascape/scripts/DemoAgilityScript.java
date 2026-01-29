package com.chromascape.scripts;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.actions.MovingObject;
import com.chromascape.utils.actions.PointSelector;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.ColourContours;
import com.chromascape.utils.domain.ocr.Ocr;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * An Agility script designed to be used with the ChromaScape RuneLite plugin configuration.
 *
 * <p>>>>TO USE THIS SCRIPT YOU MUST ENABLE THE PERMANENT XP BAR<<<
 *
 * <p>See instructions here: <a
 * href="https://github.com/StaticSweep/ChromaScape/wiki/Requirements">Instructions</a>
 *
 * <p>The script relies on the improved agility plugin to show only the next visible obstacle or
 * mark.
 *
 * <ul>
 *   <li>Green Highlight indicates the next obstacle is safe to click
 *   <li>Red Highlight (or absence of Green) indicates a Mark of Grace (next obstacle isn't
 *       highlighted green because of the plugin)
 *   <li>This script uses concurrency (multiple threads) to click the obstacle until a red click
 *       occurs.
 * </ul>
 *
 * <p>This implementation prioritizes Mark of Grace collection over course progression and includes
 * fail-safe logic to prevent getting confused during the delay between looting and the plugin
 * updating the obstacle highlights.
 */
public class DemoAgilityScript extends BaseScript {

  // Logger that appends to the Web UI
  private static final Logger logger = LogManager.getLogger(DemoAgilityScript.class);

  // Preset tiles for specific rooftop courses
  private static final Map<String, Point> ROOFTOP_RESET_TILES =
      new HashMap<>() {
        {
          put("Draynor", new Point(3103, 3278));
          put("Varrock", new Point(3223, 3414));
          put("Canifis", new Point(3507, 3487));
        }
      };

  // Configuration Constants

  /**
   * This is where you pick the reset tile for your script. e.g., if using Varrock, set the String
   * below to "Varrock"
   */
  private static final Point RESET_TILE = ROOFTOP_RESET_TILES.get("Canifis");

  private static final int TIMEOUT_XP_CHANGE = 15;
  private static final int TIMEOUT_GREEN_APPEAR = 10;

  // Colour Definitions
  // These are instantiated as final fields to prevent unnecessary memory allocation during cycles
  private static final ColourObj OBSTACLE_COLOUR =
      new ColourObj("green", new Scalar(59, 254, 254, 0), new Scalar(60, 255, 255, 0));
  private static final ColourObj MARK_COLOUR =
      new ColourObj("red", new Scalar(0, 254, 254, 0), new Scalar(1, 255, 255, 0));

  // Cached OCR colour object to reduce lookup overhead in repetitive loops
  private static ColourObj TEXT_COLOUR_WHITE = null;

  // Random used in randomising break times between obstacles
  private final Random random = new Random();

  /** Initializes the script and pre-loads necessary colour instances. */
  public DemoAgilityScript() {
    TEXT_COLOUR_WHITE = ColourInstances.getByName("White");
  }

  /**
   * The main execution loop of the script.
   *
   * <p>The cycle follows a priority order:
   *
   * <ul>
   *   <li>Check for the next obstacle highlight and mark of grace as a fallback
   *   <li>If present, click it and wait for xp or pickup
   *   <li>If neither is present, verify state and potentially walk to reset
   *   <li>1% chance of taking a break after each obstacle click
   * </ul>
   */
  @Override
  protected void cycle() {
    String previousXp = getXp();

    // Check the state of the course
    if (!isObstacleVisible()) {
      if (handleMarkOrLost()) {
        // If we clicked a mark, wait for the course to reset and the Green highlight to appear
        waitForObstacleToAppear(TIMEOUT_GREEN_APPEAR);
        return;
      }
    }

    // Interact with the detected obstacle
    // Clicking continuously until the Red X animation is detected
    try {
      MovingObject.clickMovingObjectByColourObjUntilRedClick(OBSTACLE_COLOUR, this);
    } catch (Exception e) {
      logger.error("Mouse movement interrupted while clicking moving object: {}", e.getMessage());
      stop();
    }

    // Wait for the action to complete via XP update
    waitUntilXpChange(previousXp, TIMEOUT_XP_CHANGE);

    // Humanizing sleep to mimic natural player behavior
    // And to prevent overloading moving object logic
    waitRandomMillis(650, 800);

    // 1% chance to take a break between 2 and 5 minutes after clicking an obstacle
    if (random.nextInt(100) < 1) {
      logger.info("Taking a break...");
      waitRandomMillis(120000, 300000);
    }
  }

  /**
   * Manages the scenario when the agility obstacle is not visible. It first tries to find a Mark of
   * Grace. If no mark is found, it enters a fail-safe check to confirm the player is truly lost
   * before finally attempting to walk to the RESET tile.
   *
   * @return true if a Mark of Grace was successfully clicked, false otherwise
   */
  private boolean handleMarkOrLost() {
    if (clickMarkOfGraceIfPresent()) {
      return true;
    }

    // Double check we are actually lost to protect against lag or rendering delays
    waitRandomMillis(600, 800);
    if (!isObstacleVisible()) {
      try {
        logger.info("Lost detected. Walking to reset tile.");
        controller().walker().pathTo(RESET_TILE, true);
        waitRandomMillis(4000, 6000);
      } catch (Exception e) {
        logger.error("Walker error {}", e.getMessage());
        stop();
      }
    }
    return false;
  }

  /**
   * Extracts the current Total XP from beside the minimap UI element using OCR.
   *
   * @return the XP string
   */
  private String getXp() {
    Rectangle xpZone = controller().zones().getMinimap().get("totalXP");
    try {
      return Ocr.extractText(xpZone, "Plain 12", TEXT_COLOUR_WHITE, true);
    } catch (IOException e) {
      logger.error("Images could not be read from disk {}", e.getMessage());
    }
    return "";
  }

  /**
   * Scans the game view for the Red colour associated with a Mark of Grace and attempts to click
   * it.
   *
   * @return true if the mouse action was taken, false if no mark was found
   */
  private boolean clickMarkOfGraceIfPresent() {
    BufferedImage gameView = controller().zones().getGameView();
    // You'll see that there's an extra parameter on the point selector
    // This is "tightness", how closely grouped the click should be
    // 15.0 or more works best for ground items, best to look from a higher camera angle
    Point clickLocation = PointSelector.getRandomPointByColourObj(gameView, MARK_COLOUR, 15, 15.0);

    if (clickLocation != null) {
      try {
        controller().mouse().moveTo(clickLocation, "medium");
        controller().mouse().leftClick();
        return true;
      } catch (Exception e) {
        logger.error("Mouse failed while moving to mark of grace {}", e.getMessage());
        stop();
      }
    }
    return false;
  }

  /**
   * Blocks execution until the Total XP value changes or the timeout is reached.
   *
   * @param previousXp the XP value captured before the action started
   * @param timeoutSeconds the maximum duration to wait in seconds
   */
  private void waitUntilXpChange(String previousXp, int timeoutSeconds) {
    LocalDateTime endTime = LocalDateTime.now().plusSeconds(timeoutSeconds);
    // Ensure we do not hang if the initial OCR read failed and returned an empty string
    while (!previousXp.isEmpty()
        && Objects.equals(previousXp, getXp())
        && LocalDateTime.now().isBefore(endTime)) {
      waitMillis(300);
    }
  }

  /**
   * Blocks execution until the obstacle highlight appears or the timeout is reached.
   *
   * @param timeoutSeconds the maximum duration to wait in seconds
   */
  private void waitForObstacleToAppear(int timeoutSeconds) {
    LocalDateTime endTime = LocalDateTime.now().plusSeconds(timeoutSeconds);
    while (!isObstacleVisible() && LocalDateTime.now().isBefore(endTime)) {
      waitMillis(300);
    }
  }

  /**
   * Checks if the obstacle highlight is currently present in the game view.
   *
   * @return true if the colour contours are detected, false otherwise
   */
  private boolean isObstacleVisible() {
    BufferedImage gameView = controller().zones().getGameView();
    return !ColourContours.getChromaObjsInColour(gameView, OBSTACLE_COLOUR).isEmpty();
  }
}

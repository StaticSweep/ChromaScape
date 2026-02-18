package com.chromascape.scripts;

import com.chromascape.api.DiscordNotification;
import com.chromascape.base.BaseScript;
import com.chromascape.utils.actions.Minimap;
import com.chromascape.utils.actions.MovingObject;
import com.chromascape.utils.actions.PointSelector;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.ColourContours;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
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
  private static final int TIMEOUT_OBSTACLE_APPEAR = 10;

  // Colour Definitions
  // These are instantiated as final fields to prevent unnecessary memory allocation during cycles
  private static final ColourObj OBSTACLE_COLOUR =
      new ColourObj("green", new Scalar(59, 254, 254, 0), new Scalar(60, 255, 255, 0));
  private static final ColourObj MARK_COLOUR =
      new ColourObj("red", new Scalar(0, 254, 254, 0), new Scalar(1, 255, 255, 0));

  // Random used in randomising break times between obstacles
  private final Random random = new Random();

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
    // Log the current XP before clicking obstacle for comparison later
    // The idea is to click the obstacle then wait for XP change then loop
    int previousXp = -1;
    try {
      // Read XP
      previousXp = Minimap.getXp(this);
      // Make sure it's read properly
      if (previousXp == -1) {
        stop();
        DiscordNotification.send("Xp could not be read.");
      }
    } catch (IOException e) {
      logger.error(e);
      stop();
      DiscordNotification.send(
          "Bot couldn't read XP bar because of OCR font library load, stopping and logging :(");
    }

    // Check the state of the course
    if (!isObstacleVisible()) {
      if (clickMarkOfGraceIfPresent()) {
        waitForObstacleToAppear();
      } else {
        recoverToResetTile();
      }
      return;
    }

    // Interact with the detected obstacle
    // Clicking continuously until the Red X animation is detected
    try {
      MovingObject.clickMovingObjectByColourObjUntilRedClick(OBSTACLE_COLOUR, this);
    } catch (Exception e) {
      logger.error("Mouse movement interrupted while clicking moving object: {}", e.getMessage());
      stop();
      DiscordNotification.send(
          "Mouse movement interrupted while clicking moving object: " + e.getMessage());
    }

    // Wait for the action to complete via XP update
    waitUntilXpChange(previousXp);

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
   * Manages the scenario when nothing is visible.
   * Firstly, confirms that it's really lost, if so -> uses the walker to path back to the reset tile.
   * Finally, waits for the player's animation to settle after reaching the true tile.
   */
  private void recoverToResetTile() {
    // Double check we are actually lost to protect against lag or rendering delays
    waitRandomMillis(600, 800);
    if (!isObstacleVisible()) {
      try {
        logger.info("We are lost. Walking to reset tile.");
        controller().walker().pathTo(RESET_TILE, true);
        // wait for camera to stabilise and walking animation to finish at true tile.
        waitRandomMillis(4000, 6000);
      } catch (Exception e) {
        logger.error("Walker error {}", e.getMessage());
        stop();
      }
    }
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
   */
  private void waitUntilXpChange(int previousXp) {
    LocalDateTime endTime = LocalDateTime.now().plusSeconds(TIMEOUT_XP_CHANGE);
    // Ensure we do not hang if the initial OCR read failed and returned an empty string
    try {
      while (previousXp == Minimap.getXp(this) && LocalDateTime.now().isBefore(endTime)) {
        waitMillis(300);
      }
    } catch (Exception e) {
      logger.error(e);
      stop();
      DiscordNotification.send("Bot couldn't read XP bar, stopping");
    }
  }

  /** Blocks execution until the obstacle highlight appears or the timeout is reached. */
  private void waitForObstacleToAppear() {
    LocalDateTime endTime = LocalDateTime.now().plusSeconds(TIMEOUT_OBSTACLE_APPEAR);
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

package com.chromascape.utils.actions;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides interaction for moving entities such as Agility obstacles or NPCs.
 *
 * <p>This class utilizes the red click sprite's appearance delay to asynchronously pre-calculate
 * the next retry point. This ensures that if verification fails, the backup point is ready
 * instantly but calculated with fresh screen data to minimize stale click locations.
 *
 * <p><b>Async Verification Pipeline</b> unlike static clicking, this implementation clicks a target
 * and validates success by scanning for the red X click sprite. It uses background threads to
 * ensure zero downtime between a failed click and the subsequent retry.
 */
public class MovingObject {

  /**
   * Half-width of the detection box. A padding of 7 creates a 14x14px capture region which is
   * optimized for the 11x11px Red X sprite. Inspired by OSBC.
   */
  private static final int PADDING = 7;

  /** Logger that appends to the Web UI. */
  private static final Logger logger = LogManager.getLogger(MovingObject.class);

  private static final String[] RED_CLICK_IMAGES = {
    "/images/mouse_clicks/red_1.png",
    "/images/mouse_clicks/red_2.png",
    "/images/mouse_clicks/red_3.png",
    "/images/mouse_clicks/red_4.png"
  };

  /**
   * Overload for the primary click method that accepts a colour name string. It performs a lookup
   * of the ColourObj from the ColourInstances.
   *
   * @param colour The unique name of the colour such as Agility_Green
   * @param baseScript The active script instance for accessing the Controller
   * @return true if a Red X was detected or false if the colour was not found or max retries were
   *     exceeded
   * @throws InterruptedException When mouse movement is interrupted.
   */
  public static boolean clickMovingObjectInColourUntilRedClick(String colour, BaseScript baseScript)
      throws InterruptedException {
    return clickMovingObjectByColourObjUntilRedClick(ColourInstances.getByName(colour), baseScript);
  }

  /**
   * Attempts to click a moving target defined by a ColourObj and verifies the action by looking for
   * a rec click.
   *
   * <ul>
   *   <li>Finds a random point within the current screen position of the colour
   *   <li>Clicks the point and immediately starts a background task to find the next location
   *   <li>Waits for the game to render the Red X interaction sprite
   *   <li>Captures a small region around the click and checks for the sprite
   *   <li>If verification fails, it retrieves the pre-calculated point and retries instantly
   * </ul>
   *
   * @param colour The colour of the moving object
   * @param baseScript The active script instance
   * @return true if the interaction was verified with a Red X or false otherwise
   * @throws InterruptedException When mouse movement is interrupted.
   */
  public static boolean clickMovingObjectByColourObjUntilRedClick(
      ColourObj colour, BaseScript baseScript) throws InterruptedException {

    // Initial Calculation and Click
    BufferedImage gameView = baseScript.controller().zones().getGameView();
    Point clickLocation = PointSelector.getRandomPointByColourObj(gameView, colour, 15);

    if (clickLocation == null) {
      return false;
    }

    clickMovingPointOnce(baseScript, clickLocation);

    int attempts = 10;
    int safetyCounter = 0;

    while (safetyCounter < attempts) {

      // Start calculating the NEXT point immediately
      // Running this in the background during the wait below
      CompletableFuture<Point> nextPointFuture =
          CompletableFuture.supplyAsync(
              () -> {
                BufferedImage futureView = baseScript.controller().zones().getGameView();
                // Increased scan radius for retries to catch moving targets
                return PointSelector.getRandomPointByColourObj(futureView, colour, 15);
              });

      // Wait for Red X to appear due to game delay
      // This 120ms covers the computation time of nextPointFuture
      BaseScript.waitMillis(120);

      // Update the click image
      BufferedImage clickImage = getClickImage(clickLocation);

      // Verify click
      if (clickImageContainsRedClick(clickImage)) {
        // Success so cancel the backup calculation
        nextPointFuture.cancel(true);
        return true;
      }

      // Failure detected so retrieve the backup point
      try {
        // This should return almost instantly
        clickLocation = nextPointFuture.get();
      } catch (InterruptedException | ExecutionException e) {
        logger.error("Async point calculation failed {}", e.getMessage());
        break;
      }

      if (clickLocation == null) {
        logger.warn("Could not find fallback point for colour {}", colour.name());
        break;
      }

      // Instant Retry
      clickMovingPointOnce(baseScript, clickLocation);
      safetyCounter++;
    }

    logger.error("Failed to verify red click on {} after {} attempts", colour.name(), attempts);
    return false;
  }

  /**
   * Captures a screenshot centered on the last click location.
   *
   * <p>The region size is calculated using the PADDING constant. By default, it is large enough to
   * contain the 11px interaction sprite even with minor rendering offsets.
   *
   * @param clickLocation The screen coordinate where the mouse last clicked
   * @return A BufferedImage of the immediate area around the cursor or null if location is invalid
   */
  private static BufferedImage getClickImage(Point clickLocation) {
    if (clickLocation == null) {
      return null;
    }

    Rectangle clickRect =
        new Rectangle(
            clickLocation.x - PADDING, clickLocation.y - PADDING, PADDING * 2, PADDING * 2);
    return ScreenManager.captureZone(clickRect);
  }

  /**
   * Scans the captured click image for any frame of the Red X animation.
   *
   * <p>Iterates through the preloaded red click images using template matching.
   *
   * @param clickImage The screenshot from the getClickImage method
   * @return true if any frame of the rec click animation is present, false otherwise
   */
  private static boolean clickImageContainsRedClick(BufferedImage clickImage) {
    if (clickImage == null) {
      return false;
    }

    for (String redClickImage : RED_CLICK_IMAGES) {
      try {
        if (TemplateMatching.match(redClickImage, clickImage, 0.15, false) != null) {
          return true;
        }
      } catch (Exception ignored) {
        // ignore to prevent throwing
      }
    }
    return false;
  }

  /**
   * Executes the mouse input on the BaseScript's Controller object.
   *
   * <p>Moves the mouse to the target using the fast speed profile and performs a left click.
   *
   * @param baseScript The active script instance containing the Controller
   * @param clickPoint The exact screen coordinate to click
   * @throws InterruptedException When mouse movement is interrupted.
   */
  private static void clickMovingPointOnce(BaseScript baseScript, Point clickPoint)
      throws InterruptedException {
    baseScript.controller().mouse().moveTo(clickPoint, "fast");
    baseScript.controller().mouse().leftClick();
  }
}

package com.chromascape.utils.actions;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.input.distribution.ClickDistribution;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.ChromaObj;
import com.chromascape.utils.core.screen.topology.ColourContours;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The {@code PointSelector} class provides utility methods for selecting random points within
 * graphical zones. These methods are designed to reduce code duplication and streamline common
 * actions when automating interactions with graphical objects like colours or images.
 *
 * <p><b>Features:</b>
 *
 * <ul>
 *   <li>Finds a random point within the bounding box of a detected image template.
 *   <li>Finds a random point inside the contour of the first detected object of a specified colour.
 * </ul>
 *
 * <p>These utilities are commonly reused across scripts written for the ChromaScape automation
 * framework. The class does not perform any input actions (such as clicking), but provides the
 * coordinates needed for such actions.
 *
 * <p><b>Typical Usage:</b>
 *
 * <pre>
 * Point pointInImage = PointSelector.getRandomPointInImage(templatePath, gameView, threshold);
 * Point pointInColour = PointSelector.getRandomPointInColour(gameView, "Purple", maxAttempts);
 * </pre>
 *
 * <p>All methods are static and thread-safe.
 */
public class PointSelector {

  private static final Logger logger = LogManager.getLogger(PointSelector.class);

  /**
   * Searches for the provided image template within the current game view, then returns a random
   * point within the detected bounding box if the match exceeds the defined threshold. Uses {@link
   * TemplateMatching#match(String, BufferedImage, double, boolean) TemplateMatching.match} to
   * derive the location of the image onscreen based on the threshold (lower = stricter).
   *
   * @param templatePath the BufferedImage template to locate and click within the larger image view
   * @param image the larger image, what you're searching in
   * @param threshold the openCV threshold to decide if a match exists
   * @return The point to click
   */
  public static Point getRandomPointInImage(
      String templatePath, BufferedImage image, double threshold) {
    BaseScript.checkInterrupted();
    try {
      Rectangle boundingBox = TemplateMatching.match(templatePath, image, threshold, false);

      if (boundingBox == null || boundingBox.isEmpty()) {
        logger.error("getRandomPointInImage failed: No valid bounding box.");
        return null;
      }

      return ClickDistribution.generateRandomPoint(boundingBox);
    } catch (Exception e) {
      logger.error("getRandomPointInImage failed: {}", e.getMessage());
      logger.error(e.getStackTrace());
    }
    return null;
  }

  /**
   * Attempts to find a random point inside the contour of the first object of the specified colour.
   * Is an abstraction of {@link #getRandomPointByColourObj(BufferedImage, ColourObj, int)
   * getRandomPointByColourObj} and instead searches the colour for you at runtime. Uses
   * ColourContours to mask out the provided buffered image by the provided ColourObj. Based on the
   * mask, extracts contours of the colour. Generates a random point within the bounding box of its
   * {@link ChromaObj} and checks a maximum of {@code int maxAttempts} whether the point lands in
   * the contour. Fails if exceeded.
   *
   * @param image the image to search in (e.g. game view from controller)
   * @param colourName the name of the colour (must match ColourInstances key, e.g. "Purple")
   * @param maxAttempts maximum number of attempts to find a point inside the contour
   * @return a random Point inside the contour, or null if not found/error
   */
  public static Point getRandomPointInColour(
      BufferedImage image, String colourName, int maxAttempts) {
    return getRandomPointByColourObj(image, ColourInstances.getByName(colourName), maxAttempts);
  }

  /**
   * Attempts to find a random point inside the contour of the first object of the specified
   * ColourObj. Uses ColourContours to mask out the provided buffered image by the provided
   * ColourObj. Based on the mask, extracts contours of the colour. Generates a random point within
   * the bounding box of its {@link ChromaObj} and checks a maximum of {@code int maxAttempts}
   * whether the point lands in the contour. Fails if exceeded.
   *
   * @param image the image to search in (e.g. game view from controller)
   * @param colour the name of the colour (must match ColourInstances key, e.g. "Purple")
   * @param maxAttempts maximum number of attempts to find a point inside the contour
   * @return a random Point inside the contour, or null if not found/error
   */
  public static Point getRandomPointByColourObj(
      BufferedImage image, ColourObj colour, int maxAttempts) {
    List<ChromaObj> objs;
    try {
      objs = ColourContours.getChromaObjsInColour(image, colour);
    } catch (Exception e) {
      logger.error(e.getMessage());
      logger.error(e.getStackTrace());
      return null;
    }

    if (objs.isEmpty()) {
      logger.error("No objects found for colour: {}", colour);
      return null;
    }

    ChromaObj obj = objs.get(0);
    int attempts = 0;
    Point p = ClickDistribution.generateRandomPoint(obj.boundingBox());
    while (!ColourContours.isPointInContour(p, obj.contour()) && attempts < maxAttempts) {
      BaseScript.checkInterrupted();
      p = ClickDistribution.generateRandomPoint(obj.boundingBox());
      attempts++;
    }
    logger.info("Attempts to find point in colour '{}': {}", colour, attempts);
    if (attempts >= maxAttempts) {
      logger.error(
          "Failed to find a valid point in {} contour after {} attempts.", colour, maxAttempts);
      return null;
    }

    return p;
  }
}

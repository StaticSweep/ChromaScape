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
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The {@code PointSelector} class provides utility methods for selecting random points within zones
 * and colours. These methods are designed to reduce code duplication and focus common actions when
 * automating interactions with graphical objects like colours or images.
 *
 * <p><b>Features:</b>
 *
 * <ul>
 *   <li>Finds a random point within the bounding box of a detected image template.
 *   <li>Finds a random point inside the contour of the first detected object of a specified colour.
 *   <li>Supports both <b>heuristic-based</b> distributions (dynamic sizing) and <b>explicit
 *       tightness</b> control.
 * </ul>
 *
 * <p>These utilities are commonly reused across scripts. The class does not perform any input
 * actions (such as clicking), but provides the coordinates needed for it.
 *
 * <p><b>Typical Usage:</b>
 *
 * <pre>
 * // Default heuristic distribution
 * Point imgPoint = PointSelector.getRandomPointInImage(templatePath, gameView, 0.15);
 * // Custom tightness (maybe clicking a ground item)
 * Point colorPoint = PointSelector.getRandomPointInColour(gameView, "Purple", 5, 15.0);
 * </pre>
 *
 * <p>All methods are static and thread-safe.
 */
public class PointSelector {

  private static final Logger logger = LogManager.getLogger(PointSelector.class);

  /**
   * Searches for the provided image template within a larger image, then returns a random point
   * within the detected bounding box if the match exceeds the defined threshold. Relative to the
   * larger image.
   *
   * <p>This method uses the default {@link ClickDistribution} heuristic, which dynamically adjusts
   * distribution spread based on the size of the found target.
   *
   * @param templatePath the BufferedImage template to locate within the larger image
   * @param image the larger image to search inside (e.g. game view)
   * @param threshold the match confidence threshold (0.0 to 1.0) required to consider a detection
   *     valid
   * @return a valid {@link Point} within the detected region, or {@code null} if no match is found
   */
  public static Point getRandomPointInImage(
      String templatePath, BufferedImage image, double threshold) {
    // Defines which function to apply onto the rectangle found
    return findPointInTemplate(
        templatePath, image, threshold, ClickDistribution::generateRandomPoint);
  }

  /**
   * Searches for the provided image template within a larger and returns a random point with a
   * specific Gaussian distribution tightness. Relative to the larger image.
   *
   * <p>This overload allows for control over where the click lands. Higher tightness values force
   * the point closer to the center of the image, while lower values spread it towards the edges.
   *
   * @param templatePath the BufferedImage template to search for within the larger image view
   * @param image the larger image to search inside (e.g. game view)
   * @param threshold the match confidence threshold (0.0 to 1.0) required to consider a detection
   *     valid
   * @param tightness the distribution divisor. Higher values (e.g., 15.0) result in a tighter
   *     cluster around the center
   * @return a valid {@link Point} within the detected region, or {@code null} if no match is found
   */
  public static Point getRandomPointInImage(
      String templatePath, BufferedImage image, double threshold, double tightness) {
    // Defines which function to apply onto the rectangle found
    return findPointInTemplate(
        templatePath,
        image,
        threshold,
        rect -> ClickDistribution.generateRandomPoint(rect, tightness));
  }

  /**
   * Attempts to find a random point inside the contour of the first object of the specified colour
   * using the default distribution heuristic.
   *
   * <p>This is an overload for {@link #getRandomPointByColourObj(BufferedImage, ColourObj, int)}.
   * It looks up the colour by name from {@link ColourInstances} at runtime.
   *
   * @param image the image to search in (e.g. game view)
   * @param colourName the name of the colour (must match a {@link ColourInstances} key, e.g.
   *     "Purple")
   * @param maxAttempts maximum number of re-rolls to find a point that falls exactly inside the
   *     irregular contour
   * @return a random {@link Point} inside the contour, or {@code null} if not found or max attempts
   *     exceeded
   */
  public static Point getRandomPointInColour(
      BufferedImage image, String colourName, int maxAttempts) {
    // Calls the public API after grabbing the colour
    return getRandomPointByColourObj(image, ColourInstances.getByName(colourName), maxAttempts);
  }

  /**
   * Attempts to find a random point inside the contour of the first object of the specified colour
   * using a specific Gaussian tightness.
   *
   * @param image the image to search in (e.g. game view)
   * @param colourName the name of the colour (must match a {@link ColourInstances} key, e.g.
   *     "Purple")
   * @param maxAttempts maximum number of re-rolls to find a point that falls exactly inside the
   *     irregular contour
   * @param tightness the distribution divisor. Higher values (e.g., 15.0) result in a tighter
   *     cluster around the center
   * @return a random {@link Point} inside the contour, or {@code null} if not found or max attempts
   *     exceeded
   */
  public static Point getRandomPointInColour(
      BufferedImage image, String colourName, int maxAttempts, double tightness) {
    // Call the public API after grabbing the colour
    return getRandomPointByColourObj(
        image, ColourInstances.getByName(colourName), maxAttempts, tightness);
  }

  /**
   * Attempts to find a random point inside the contour of the first object of the specified {@link
   * ColourObj}.
   *
   * <p>Uses {@link ColourContours} to mask the image and extract contours. It generates a random
   * point within the bounding box of the detected {@link ChromaObj} and verifies if the point lies
   * within the actual contour polygon.
   *
   * @param image the image to search in
   * @param colour the specific {@link ColourObj} to detect
   * @param maxAttempts maximum number of re-rolls to find a point that falls exactly inside the
   *     irregular contour
   * @return a random {@link Point} inside the contour, or {@code null} if not found or max attempts
   *     exceeded
   */
  public static Point getRandomPointByColourObj(
      BufferedImage image, ColourObj colour, int maxAttempts) {
    // Defines which function to apply onto the rectangle found
    return findPointInColourInternal(
        image, colour, maxAttempts, ClickDistribution::generateRandomPoint);
  }

  /**
   * Attempts to find a random point inside the contour of the first object of the specified {@link
   * ColourObj} using a specific Gaussian tightness.
   *
   * @param image the image to search in
   * @param colour the specific {@link ColourObj} to detect
   * @param maxAttempts maximum number of re-rolls to find a point that falls exactly inside the
   *     irregular contour
   * @param tightness the distribution divisor. Higher values (e.g., 15.0) result in a tighter
   *     cluster around the center
   * @return a random {@link Point} inside the contour, or {@code null} if not found or max attempts
   *     exceeded
   */
  public static Point getRandomPointByColourObj(
      BufferedImage image, ColourObj colour, int maxAttempts, double tightness) {
    // Defines which function to apply onto the rectangle found
    return findPointInColourInternal(
        image, colour, maxAttempts, rect -> ClickDistribution.generateRandomPoint(rect, tightness));
  }

  /**
   * Internal abstraction for template matching logic. Executes the match and applies the provided
   * point generation strategy.
   */
  private static Point findPointInTemplate(
      String templatePath,
      BufferedImage image,
      double threshold,
      Function<Rectangle, Point> pointGenerator) {
    BaseScript.checkInterrupted();
    try {
      Rectangle boundingBox = TemplateMatching.match(templatePath, image, threshold, false);

      if (boundingBox == null || boundingBox.isEmpty()) {
        logger.error("getRandomPointInImage failed: No valid bounding box.");
        return null;
      }
      // Applying the desired function parameter onto the bounding box and returning it
      return pointGenerator.apply(boundingBox);
    } catch (Exception e) {
      logger.error("getRandomPointInImage failed: {}", e.getMessage());
      logger.error(e.getStackTrace());
    }
    return null;
  }

  /**
   * Internal abstraction for colour contour logic. Handles object detection, contour validation
   * loops, and memory cleanup.
   */
  private static Point findPointInColourInternal(
      BufferedImage image,
      ColourObj colour,
      int maxAttempts,
      Function<Rectangle, Point> pointGenerator) {

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

    // Use the closest object to screen centre since only one object is desired
    ChromaObj obj = ColourContours.getChromaObjClosestToCentre(objs);
    try {
      int attempts = 0;
      // Generate initial point using the function provided (Heuristic or Tightness)
      Point p = pointGenerator.apply(obj.boundingBox());

      // Resample if the point is outside the actual pixel contour
      while (!ColourContours.isPointInContour(p, obj.contour()) && attempts < maxAttempts) {
        BaseScript.checkInterrupted();
        // Apply the desired function on the bounding box
        p = pointGenerator.apply(obj.boundingBox());
        attempts++;
      }

      if (attempts >= maxAttempts) {
        logger.error(
            "Failed to find a valid point in {} contour after {} attempts.", colour, maxAttempts);
        return null;
      }
      return p;
    } finally {
      // Release Mat contours to free memory.
      for (ChromaObj chromaObj : objs) {
        chromaObj.release();
      }
    }
  }
}

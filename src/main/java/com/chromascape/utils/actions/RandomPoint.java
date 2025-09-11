package com.chromascape.utils.actions;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.input.distribution.ClickDistribution;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.topology.ChromaObj;
import com.chromascape.utils.core.screen.topology.ColourContours;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RandomPoint {

  private static final Logger logger = LogManager.getLogger(RandomPoint.class.getName());

  /**
   * Searches for the provided image template within the current game view, then returns a random
   * point within the detected bounding box if the match exceeds the defined threshold.
   *
   * @param baseScript your current script (use keyword "this")
   * @param templatePath the BufferedImage template to locate and click within the larger image view
   * @param image the larger image, what you're searching in
   * @param threshold the openCV threshold to decide if a match exists
   * @return The point to click
   */
  public static Point getRandomPointInImage(
      BaseScript baseScript, String templatePath, BufferedImage image, double threshold) {
    try {
      Rectangle boundingBox = TemplateMatching.match(templatePath, image, threshold, false);

      if (boundingBox == null || boundingBox.isEmpty()) {
        logger.error("getRandomPointInImage failed: No valid bounding box.");
        baseScript.stop();
        return null;
      }

      return ClickDistribution.generateRandomPoint(boundingBox);
    } catch (Exception e) {
      logger.error("getRandomPointInImage failed: {}", e.getMessage());
      logger.error(e.getStackTrace());
      baseScript.stop();
    }
    return null;
  }

  /**
   * Attempts to find a random point inside the contour of the first object of the specified color.
   *
   * @param baseScript your current script (use keyword "this")
   * @param image the image to search in (e.g. game view from controller)
   * @param colourName the name of the color (must match ColourInstances key, e.g. "Purple")
   * @param maxAttempts maximum number of attempts to find a point inside the contour
   * @return a random Point inside the contour, or null if not found/error
   */
  public static Point getRandomPointInColour(
      BaseScript baseScript, BufferedImage image, String colourName, int maxAttempts) {
    List<ChromaObj> objs;
    try {
      objs = ColourContours.getChromaObjsInColour(image, ColourInstances.getByName(colourName));
    } catch (Exception e) {
      logger.error(e.getMessage());
      logger.error(e.getStackTrace());
      baseScript.stop();
      return null;
    }

    if (objs.isEmpty()) {
      logger.error("No objects found for colour: {}", colourName);
      baseScript.stop();
      return null;
    }

    ChromaObj obj = objs.get(0);
    int attempts = 0;
    Point p = ClickDistribution.generateRandomPoint(obj.boundingBox());
    while (!ColourContours.isPointInContour(p, obj.contour()) && attempts < maxAttempts) {
      p = ClickDistribution.generateRandomPoint(obj.boundingBox());
      attempts++;
    }
    logger.info("Attempts to find point in colour '{}': {}", colourName, attempts);
    if (attempts >= maxAttempts) {
      logger.error(
          "Failed to find a valid point in {} contour after {} attempts.", colourName, maxAttempts);
      baseScript.stop();
      return null;
    }

    return p;
  }
}

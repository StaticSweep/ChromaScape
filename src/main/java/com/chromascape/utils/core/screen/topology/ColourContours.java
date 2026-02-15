package com.chromascape.utils.core.screen.topology;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.inRange;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.viewport.ViewportManager;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.core.state.StateManager;
import com.chromascape.utils.core.statistics.StatisticsManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.*;

/**
 * Utility class for extracting and processing colour-based contours from images. Uses OpenCV to
 * convert images to HSV, extract colours within HSV ranges, find contours, and create ChromaObj
 * objects representing these contours.
 */
public class ColourContours {

  private static final Mat DILATE_KERNEL = getStructuringElement(MORPH_ELLIPSE, new Size(20, 20));
  private static final Mat ERODE_KERNEL = getStructuringElement(MORPH_ELLIPSE, new Size(20, 20));
  private static final Scalar COLOUR_WHITE = new Scalar(255);
  private static final Mat EMPTY_HIERARCHY = new Mat();
  private static final org.bytedeco.opencv.opencv_core.Point OFFSET_ZERO =
      new org.bytedeco.opencv.opencv_core.Point(0, 0);

  /**
   * Finds and returns a list of ChromaObj instances representing contours in the given image that
   * match the specified colour range.
   *
   * @param image the BufferedImage to process
   * @param colourObj the ColourObj specifying the HSV colour range to extract
   * @return a list of ChromaObj objects representing detected contours of the specified colour
   */
  public static List<ChromaObj> getChromaObjsInColour(BufferedImage image, ColourObj colourObj) {
    Mat mask = extractColours(image, colourObj);
    morphClose(mask);
    ViewportManager.getInstance().updateState(mask);
    MatVector contours = extractContours(mask);
    mask.release();
    return createChromaObjects(contours);
  }

  /**
   * Iterates over a list of ChromaObjs to calculate and return whichever is closest to the
   * player/screen centre. Useful in a wide range of activities and preferred over arbitrary choice
   * by detection.
   *
   * @param chromaObjs {@code List<ChromaObj>} of which to iterate over.
   * @return a single {@link ChromaObj} which is closest to the player.
   */
  public static ChromaObj getChromaObjClosestToCentre(List<ChromaObj> chromaObjs) {
    if (chromaObjs == null || chromaObjs.isEmpty()) {
      return null;
    }

    Point screenCentre =
        new Point(
            (int) ScreenManager.getWindowBounds().getCenterX(),
            (int) ScreenManager.getWindowBounds().getCenterY());

    double minDistance = Double.MAX_VALUE;
    ChromaObj closestChromaObj = null;

    for (ChromaObj chromaObj : chromaObjs) {
      Point objCentre =
          new Point(
              (int) chromaObj.boundingBox().getCenterX(),
              (int) chromaObj.boundingBox().getCenterY());

      double currentDistance = objCentre.distance(screenCentre);

      if (currentDistance < minDistance) {
        minDistance = currentDistance;
        closestChromaObj = chromaObj;
      }
    }

    return closestChromaObj;
  }

  /**
   * Converts the input image to HSV colour space and extracts a binary mask where pixels within the
   * HSV range specified by the colourObj are white (255), and others are black (0).
   *
   * @param image the BufferedImage to convert and threshold
   * @param colourObj the ColourObj specifying the HSV minimum and maximum bounds
   * @return a Mat binary mask with pixels in range set to 255, others 0
   */
  public static Mat extractColours(BufferedImage image, ColourObj colourObj) {
    // Convert BufferedImage to Mat explicitly
    try (Mat hsvImage = Java2DFrameUtils.toMat(image)) {
      return extractColours(hsvImage, colourObj);
    }
  }

  /**
   * Converts the input Mat to HSV colour space and extracts a binary mask.
   *
   * @param inputMat the source image Mat (BGR)
   * @param colourObj the ColourObj specifying the HSV minimum and maximum bounds
   * @return a Mat binary mask with pixels in range set to 255, others 0
   */
  public static Mat extractColours(Mat inputMat, ColourObj colourObj) {
    StateManager.setState(com.chromascape.utils.core.state.BotState.SEARCHING);
    Mat hsvImage = inputMat.clone();
    cvtColor(hsvImage, hsvImage, COLOR_BGR2HSV);
    Mat result = new Mat(hsvImage.size(), CV_8UC1);
    Mat hsvMin = new Mat(colourObj.hsvMin());
    Mat hsvMax = new Mat(colourObj.hsvMax());
    inRange(hsvImage, hsvMin, hsvMax, result);
    hsvImage.release();
    hsvMin.release();
    hsvMax.release();

    return result;
  }

  /**
   * Uses Morphological Closing via dilation and erosion, to ensure that no breaks appear in the
   * contour. Fills object's contours to ensure consistency and to reduce duplicate contours.
   * Mutates the given Mat object rather than assigning separate objects.
   *
   * @param result The 8UC1 {@link Mat} mask which to mutate.
   */
  public static void morphClose(Mat result) {
    // Dilate the contour to fix breaks e.g., C should become O
    morphologyEx(result, result, MORPH_DILATE, DILATE_KERNEL);

    // Completely fill internal space with white
    // For consistency and improved contour calculation
    try (MatVector contours = new MatVector()) {
      findContours(result, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
      // Using static constants for reused variables to reduce CPU allocation fatigue
      drawContours(
          result,
          contours,
          -1,
          COLOUR_WHITE,
          -1,
          LINE_8,
          EMPTY_HIERARCHY,
          Integer.MAX_VALUE,
          OFFSET_ZERO);
    }

    // Restore original size through erosion whilst closing contour breaks
    morphologyEx(result, result, MORPH_ERODE, ERODE_KERNEL);
  }

  /**
   * Finds contours in a binary mask image.
   *
   * @param binaryMask a binary Mat mask where contours are to be found
   * @return a MatVector containing all detected contours
   */
  public static MatVector extractContours(Mat binaryMask) {
    MatVector contours = new MatVector();
    findContours(binaryMask, contours, CV_RETR_LIST, CHAIN_APPROX_SIMPLE);
    return contours;
  }

  /**
   * Creates a list of ChromaObj objects from the given contours. Each ChromaObj contains the
   * contour index, the contour Mat itself, and its bounding rectangle as a Java AWT Rectangle.
   *
   * @param contours MatVector containing contours detected in the image
   * @return list of ChromaObj objects representing each contour with bounding box
   */
  public static List<ChromaObj> createChromaObjects(MatVector contours) {
    List<ChromaObj> chromaObjects = new ArrayList<>();
    for (int i = 0; i < contours.size(); i++) {
      Mat contour = contours.get(i);
      Rect rect = boundingRect(contour);
      Rectangle offset = ScreenManager.getWindowBounds();
      Rectangle contourBounds =
          new Rectangle(rect.x() + offset.x, rect.y() + offset.y, rect.width(), rect.height());
      chromaObjects.add(new ChromaObj(i, contour, contourBounds));
      StatisticsManager.incrementObjectsDetected();
    }
    return chromaObjects;
  }

  /**
   * Checks whether a given point lies inside a specified contour.
   *
   * @param point the Point to test
   * @param contour the Mat representing the contour to test against
   * @return true if the point lies inside the contour; false otherwise
   */
  public static boolean isPointInContour(Point point, Mat contour) {
    Point clientPoint = ScreenManager.toClientCoords(point);
    try (Point2f point2f = new Point2f(clientPoint.x, clientPoint.y)) {
      return pointPolygonTest(contour, point2f, false) > 0;
    }
  }
}

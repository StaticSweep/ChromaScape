package com.chromascape.utils.core.screen.topology;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.inRange;
import static org.bytedeco.opencv.global.opencv_imgproc.CHAIN_APPROX_SIMPLE;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2HSV;
import static org.bytedeco.opencv.global.opencv_imgproc.CV_RETR_LIST;
import static org.bytedeco.opencv.global.opencv_imgproc.boundingRect;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.findContours;
import static org.bytedeco.opencv.global.opencv_imgproc.pointPolygonTest;

import com.chromascape.utils.core.screen.DisplayImage;
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
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.Rect;

/**
 * Utility class for extracting and processing colour-based contours from images. Uses OpenCV to
 * convert images to HSV, extract colours within HSV ranges, find contours, and create ChromaObj
 * objects representing these contours.
 */
public class ColourContours {

  public static boolean debug = false;

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
    MatVector contours = extractContours(mask);
    mask.release();
    return createChromaObjects(contours);
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

    // if debugging, display the mask
    if (debug) {
      DisplayImage.display(Java2DFrameUtils.toBufferedImage(result));
    }

    ViewportManager.getInstance().updateState(result);

    return result;
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

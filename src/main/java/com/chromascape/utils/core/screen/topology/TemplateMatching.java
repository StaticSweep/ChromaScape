package com.chromascape.utils.core.screen.topology;

import static org.bytedeco.opencv.global.opencv_core.extractChannel;
import static org.bytedeco.opencv.global.opencv_core.minMaxLoc;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.opencv.imgproc.Imgproc.TM_SQDIFF_NORMED;

import com.chromascape.utils.core.screen.viewport.ViewportManager;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.core.state.BotState;
import com.chromascape.utils.core.state.StateManager;
import com.chromascape.utils.core.statistics.StatisticsManager;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;

/**
 * Utility class for performing alpha-aware template matching using OpenCV and JavaCV.
 *
 * <p>This class provides a single static method, {@link #match}, which uses the TM_SQDIFF_NORMED
 * algorithm to locate a template image within a larger base image. It uses an alpha mask to ignore
 * transparent pixels in the template.
 *
 * <p>This is commonly to locate UI elements or sprites in the client window, based on screen
 * captures and template assets.
 */
public class TemplateMatching {

  private static final Logger logger = LogManager.getLogger(TemplateMatching.class.getName());

  private static double minVal;

  /**
   * Performs template matching to locate a smaller image (template) within a larger image (base),
   * using normalized squared difference matching with an alpha channel mask to ignore transparent
   * pixels.
   *
   * <p>The method requires both images to have 4 channels (BGRA). If they do not, they are
   * converted internally. The matching ignores fully transparent pixels in the template by applying
   * a mask based on its alpha channel.
   *
   * <p>The method returns the bounding rectangle of the best match if its matching score is below
   * the given threshold. If no match satisfies the threshold, the method returns {@code null}.
   *
   * @param templateImg The template image (smaller), expected as a {@link BufferedImage} in BGRA
   *     format or convertible to it.
   * @param baseImg The base image (larger) where the template is searched, expected as a {@link
   *     BufferedImage} in BGRA format or convertible to it.
   * @param threshold The maximum allowed normalized squared difference score for a valid match.
   *     Lower values mean better matches.
   * @param debugMsg Set this to true if you want detailed messages useful when debugging.
   * @return A {@link Rectangle} representing the position and size of the matching area in the base
   *     image, or {@code null} if no match meets the threshold criteria.
   * @throws IllegalArgumentException If either input image is empty (not loaded).
   * @throws Exception If the template is larger than the base image.
   */
  public static Rectangle match(
      String templateImg, BufferedImage baseImg, double threshold, boolean debugMsg)
      throws Exception {

    debug(">> Entered patternMatch()", debugMsg);
    StateManager.setState(BotState.SEARCHING);

    Mat template = loadMatFromResource(templateImg);

    // Prepare a mat in RGB to send to the viewport
    Mat view = new Mat();
    // Use the template as source and view as destination.
    // This handles data copying/conversion safely without modifying template.
    if (template.channels() == 4) {
      cvtColor(template, view, COLOR_BGRA2RGB);
    } else {
      cvtColor(template, view, COLOR_BGR2RGB);
    }
    ViewportManager.getInstance().updateState(view);
    // Release the view Mat immediately as ViewportManager handles the data.
    view.release();

    if (template.empty()) {
      throw new IllegalArgumentException("Template image is empty");
    }

    Mat base = Java2DFrameUtils.toMat(baseImg);

    if (base.empty()) {
      throw new IllegalArgumentException("Base image is empty");
    }

    debug(
        "Template size: "
            + template.size().width()
            + "x"
            + template.size().height()
            + ", channels: "
            + template.channels(),
        debugMsg);
    debug(
        "Base size: "
            + base.size().width()
            + "x"
            + base.size().height()
            + ", channels: "
            + base.channels(),
        debugMsg);

    if (template.channels() != 4) {
      debug("Converting template to BGRA...", debugMsg);
      cvtColor(template, template, COLOR_BGR2BGRA);
    }

    if (base.channels() != 4) {
      debug("Converting base to BGRA...", debugMsg);
      cvtColor(base, base, COLOR_BGR2BGRA);
    }

    if (template.cols() > base.cols() || template.rows() > base.rows()) {
      throw new Exception("Template is larger than base image");
    }

    int convRows = base.rows() - template.rows() + 1;
    int convCols = base.cols() - template.cols() + 1;
    debug("Convolution matrix size: " + convCols + "x" + convRows, debugMsg);

    Mat alpha = new Mat();
    extractChannel(template, alpha, 3);
    debug(
        "Alpha mask size: "
            + alpha.size().width()
            + "x"
            + alpha.size().height()
            + ", channels: "
            + alpha.channels(),
        debugMsg);

    Mat convolution = new Mat(convRows, convCols);

    debug("Calling matchTemplate()...", debugMsg);
    matchTemplate(base, template, convolution, TM_SQDIFF_NORMED, alpha);
    debug("matchTemplate() done.", debugMsg);
    debug("Convolution empty: " + convolution.empty(), debugMsg);

    if (convolution.empty()) {
      throw new RuntimeException("matchTemplate() failed — convolution matrix is empty");
    }

    DoublePointer minVal = new DoublePointer(1);
    DoublePointer maxVal = new DoublePointer(1);
    Point minLoc = new Point();
    Point maxLoc = new Point();

    debug("Calling minMaxLoc()...", debugMsg);
    minMaxLoc(convolution, minVal, maxVal, minLoc, maxLoc, new Mat());
    debug("minMaxLoc() done.", debugMsg);
    debug("minVal: " + minVal.get() + ", maxVal: " + maxVal.get(), debugMsg);
    debug(
        "minLoc: ("
            + minLoc.x()
            + ","
            + minLoc.y()
            + "), maxLoc: ("
            + maxLoc.x()
            + ","
            + maxLoc.y()
            + ")",
        debugMsg);

    TemplateMatching.minVal = minVal.get();

    if (minVal.get() > threshold) {
      logger.warn("No match: minVal above threshold ({} > {})", minVal.get(), threshold);
      return null;
    }
    // offset for screen cords
    Rectangle offset = ScreenManager.getWindowBounds();

    Rectangle match =
        new Rectangle(
            offset.x + minLoc.x(), offset.y + minLoc.y(), template.cols(), template.rows());

    StatisticsManager.incrementObjectsDetected();
    debug("Match found at: " + match, debugMsg);

    template.release();
    base.release();
    convolution.release();
    alpha.release();

    debug("<< Exiting patternMatch()", debugMsg);
    return match;
  }

  /**
   * Solution to show debug messages in console.
   *
   * @param message String message to show.
   * @param debug Works only if debug is true.
   */
  private static void debug(String message, boolean debug) {
    if (debug) {
      logger.warn(message);
    }
  }

  /**
   * Loads an image as a Mat from a resource path, preserving alpha channel.
   *
   * @param resourcePath path to image resource, e.g. "/images/user/myTemplate.png" (first "/" is
   *     necessary)
   * @return Mat with image data including alpha
   * @throws IOException if resource not found or temp file write fails
   */
  public static Mat loadMatFromResource(String resourcePath) throws IOException {
    // Get resource as stream from classpath
    InputStream is = TemplateMatching.class.getResourceAsStream(resourcePath);
    if (is == null) {
      throw new IllegalArgumentException("Resource not found: " + resourcePath);
    }

    // Create a temp file to write the resource contents (OpenCV imread needs a file
    // path)
    Path tempFile = Files.createTempFile("opencv-temp-", ".png");
    tempFile.toFile().deleteOnExit();

    // Copy resource stream to temp file
    Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);

    // Load with imread and IMREAD_UNCHANGED to keep alpha
    Mat mat = opencv_imgcodecs.imread(tempFile.toString(), opencv_imgcodecs.IMREAD_UNCHANGED);

    if (mat.empty()) {
      throw new IllegalStateException("Failed to load Mat from resource: " + resourcePath);
    }

    return mat;
  }

  /**
   * Returns the minVal of the last matched object. The minVal dictates how strongly an object
   * matches onscreen. (lower = better)
   *
   * @return the minVal.
   */
  public static double getMinVal() {
    return minVal;
  }
}

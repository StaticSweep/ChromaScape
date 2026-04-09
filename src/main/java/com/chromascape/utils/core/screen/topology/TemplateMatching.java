package com.chromascape.utils.core.screen.topology;

import static org.bytedeco.opencv.global.opencv_core.extractChannel;
import static org.bytedeco.opencv.global.opencv_core.minMaxLoc;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.opencv.imgproc.Imgproc.TM_SQDIFF_NORMED;

import com.chromascape.utils.core.screen.viewport.ViewportManager;
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
   * @return A {@link MatchResult} representing the position and size of the matching area in the
   *     base image, or {@code null} if no match meets the threshold criteria.
   */
  public static MatchResult match(String templateImg, BufferedImage baseImg, double threshold) {

    // Update bot's semantic state
    StateManager.setState(BotState.SEARCHING);

    Mat template = null;
    Mat base = null;
    Mat convolution = null;
    Mat alpha = null;
    Mat mask = null;

    try {
      // Read template image from disk and load it as a Mat
      try {
        template = loadMatFromResource(templateImg);
      } catch (IOException e) {
        return new MatchResult(null, Double.MAX_VALUE, false, "Template image is empty");
      }

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
        return new MatchResult(null, Double.MAX_VALUE, false, "Template image is empty");
      }

      base = Java2DFrameUtils.toMat(baseImg);

      if (base.empty()) {
        return new MatchResult(null, Double.MAX_VALUE, false, "Base image is empty");
      }

      if (template.channels() != 4) {
        cvtColor(template, template, COLOR_BGR2BGRA);
      }

      if (base.channels() != 4) {
        cvtColor(base, base, COLOR_BGR2BGRA);
      }

      if (template.cols() > base.cols() || template.rows() > base.rows()) {
        return new MatchResult(null, Double.MAX_VALUE, false, "Template is larger than base image");
      }

      int convRows = base.rows() - template.rows() + 1;
      int convCols = base.cols() - template.cols() + 1;

      alpha = new Mat();
      extractChannel(template, alpha, 3);

      convolution = new Mat(convRows, convCols);

      matchTemplate(base, template, convolution, TM_SQDIFF_NORMED, alpha);

      if (convolution.empty()) {
        return new MatchResult(null, Double.MAX_VALUE, false, "Convolution matrix is empty");
      }

      DoublePointer minVal = new DoublePointer(1);
      DoublePointer maxVal = new DoublePointer(1);
      Point minLoc = new Point();
      Point maxLoc = new Point();

      mask = new Mat();
      minMaxLoc(convolution, minVal, maxVal, minLoc, maxLoc, mask);

      if (minVal.get() > threshold) {
        return new MatchResult(null, minVal.get(), false, "MinVal greater than threshold");
      }

      Rectangle match = new Rectangle(minLoc.x(), minLoc.y(), template.cols(), template.rows());

      // Update singleton state manager to update stats in UI
      StatisticsManager.incrementObjectsDetected();

      return new MatchResult(match, minVal.get(), true, "Match found");
    } finally {

      // Release native memory
      if (template != null && !template.isNull()) {
        template.release();
      }
      if (base != null && !base.isNull()) {
        base.release();
      }
      if (convolution != null && !convolution.isNull()) {
        convolution.release();
      }
      if (alpha != null && !alpha.isNull()) {
        alpha.release();
      }
      if (mask != null && !mask.isNull()) {
        mask.release();
      }
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
}

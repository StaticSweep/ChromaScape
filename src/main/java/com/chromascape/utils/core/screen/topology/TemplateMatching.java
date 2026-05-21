package com.chromascape.utils.core.screen.topology;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_core.extractChannel;
import static org.bytedeco.opencv.global.opencv_core.minMaxLoc;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.opencv.imgproc.Imgproc.TM_SQDIFF_NORMED;

import com.chromascape.utils.core.screen.viewport.ViewportManager;
import com.chromascape.utils.core.state.BotState;
import com.chromascape.utils.core.state.StateManager;
import com.chromascape.utils.core.statistics.StatisticsManager;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bytedeco.javacpp.DoublePointer;
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
   * using normalised squared difference matching with an alpha channel mask to ignore transparent
   * pixels.
   *
   * <p>The method returns the bounding rectangle of the best match if its matching score is below
   * the given threshold. If no match satisfies the threshold, the method returns {@code null}.
   *
   * @param templateImg The template image (smaller), expected as a {@link BufferedImage}.
   * @param baseImg The base image (larger) where the template is searched, expected as a {@link
   *     BufferedImage} in RGB format.
   * @param threshold The maximum allowed normalised squared difference score for a valid match.
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
      ViewportManager.getInstance().updateState(template);
      // Release the view Mat immediately as ViewportManager handles the data.
      view.release();

      if (template.empty()) {
        return new MatchResult(null, Double.MAX_VALUE, false, "Template image is empty");
      }

      // Internally swaps channels to from RGBA to BGRA or RGB to BGR
      base = bufferedImageToMat(baseImg);

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

    // Create a temp file to write the resource contents
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
   * Converts a Java {@link BufferedImage} into an OpenCV {@link Mat} object. To ensure
   * compatibility with standard OpenCV processing pipeline expectations, this method forces a
   * standardisation step. It draws the input image onto a fresh canvas explicitly formatted as
   * {@code BufferedImage.TYPE_3BYTE_BGR}.
   *
   * @param image the source {@code BufferedImage} to convert
   * @return a {@code Mat} containing the BGR pixel data of the image, or an empty {@code Mat} if
   *     the input image is null
   */
  public static Mat bufferedImageToMat(BufferedImage image) {
    if (image == null) {
      return new Mat();
    }
    // Convert ARGB/GRAY images to BGR for standardisation
    BufferedImage bgrImage =
        new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
    Graphics2D g = bgrImage.createGraphics();
    g.drawImage(image, 0, 0, null);
    g.dispose();
    // Extract data
    byte[] cleanData = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();
    // Create mat of correct format and size
    Mat mat = new Mat(bgrImage.getHeight(), bgrImage.getWidth(), CV_8UC3);
    // Put data into mat
    mat.data().put(cleanData);
    return mat;
  }

  /**
   * Converts an OpenCV {@link Mat} object into a Java {@link BufferedImage}.
   *
   * <p>This method dynamically uses the number of {@code sourceMat.channels()} to create output
   * mats:
   *
   * <ul>
   *   <li><b>1 Channel:</b> Maps directly to {@code BufferedImage.TYPE_BYTE_GRAY} (Grayscale).
   *   <li><b>3 Channels:</b> Maps directly to {@code BufferedImage.TYPE_3BYTE_BGR} (Standard BGR
   *       Colour).
   *   <li><b>4 Channels:</b> Maps to {@code BufferedImage.TYPE_4BYTE_ABGR}, manually reordering the
   *       byte alignment from OpenCV's BGRA format to Java's expected ABGR format.
   * </ul>
   *
   * @param mat the source OpenCV matrix to convert; may be uncontinuous, but must not be null or
   *     empty
   * @return a {@code BufferedImage} matching the dimensions and colour depth of the input, or
   *     {@code null} if the input matrix is null or empty
   * @throws IllegalArgumentException if the matrix has an unsupported number of channels (e.g., 2
   *     channels)
   */
  public static BufferedImage matToBufferedImage(Mat mat) {
    if (mat == null || mat.empty()) {
      return null;
    }

    Mat sourceMat = mat.isContinuous() ? mat : mat.clone();

    byte[] sourcePixels = new byte[sourceMat.cols() * sourceMat.rows() * sourceMat.channels()];
    sourceMat.data().get(sourcePixels);

    BufferedImage image;
    byte[] targetPixels;

    if (sourceMat.channels() == 3) {
      image = new BufferedImage(sourceMat.cols(), sourceMat.rows(), BufferedImage.TYPE_3BYTE_BGR);
      targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
      System.arraycopy(sourcePixels, 0, targetPixels, 0, sourcePixels.length);

    } else if (sourceMat.channels() == 4) {

      image = new BufferedImage(sourceMat.cols(), sourceMat.rows(), BufferedImage.TYPE_4BYTE_ABGR);
      targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();

      for (int i = 0; i < sourcePixels.length; i += 4) {
        targetPixels[i] = sourcePixels[i + 3];
        targetPixels[i + 1] = sourcePixels[i];
        targetPixels[i + 2] = sourcePixels[i + 1];
        targetPixels[i + 3] = sourcePixels[i + 2];
      }

    } else if (sourceMat.channels() == 1) {
      image = new BufferedImage(sourceMat.cols(), sourceMat.rows(), BufferedImage.TYPE_BYTE_GRAY);
      targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
      System.arraycopy(sourcePixels, 0, targetPixels, 0, sourcePixels.length);

    } else {
      throw new IllegalArgumentException("Unsupported channel count: " + sourceMat.channels());
    }

    if (!sourceMat.isContinuous() && sourceMat != mat) {
      sourceMat.release();
    }

    return image;
  }
}

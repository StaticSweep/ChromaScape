package com.chromascape.utils.domain.zones;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.bytedeco.javacpp.indexer.UByteRawIndexer;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

/**
 * Utility class for applying rectangular masks to images.
 *
 * <p>Provides static methods for blacking out regions of {@link BufferedImage} or OpenCV {@link
 * Mat} objects based on AWT {@link Rectangle} coordinates. Used primarily for excluding visual
 * zones from further processing.
 */
public class MaskZones {

  /**
   * Applies a rectangular mask to a given {@link BufferedImage} and returns a new image with the
   * specified area set to black.
   *
   * @param originalImg The original input image.
   * @param maskArea The rectangular area to mask, in AWT {@link Rectangle} coordinates.
   * @return a new {@link BufferedImage} With the specified region zeroed out.
   * @throws IllegalArgumentException If the rectangle is out of image bounds or invalid.
   */
  public static BufferedImage maskZones(BufferedImage originalImg, Rectangle maskArea) {
    Mat original = Java2DFrameUtils.toMat(originalImg);
    Mat output = maskZonesMat(original, maskArea);
    BufferedImage outImg = Java2DFrameUtils.toBufferedImage(output);
    original.release();
    output.release();
    return outImg;
  }

  /**
   * Applies a rectangular mask directly to a {@link Mat} image and returns a new {@link Mat} with
   * the specified region zeroed out.
   *
   * @param original The original input image as an OpenCV {@link Mat}.
   * @param maskArea The rectangular area to mask, in AWT {@link Rectangle} coordinates.
   * @return A cloned {@link Mat} with the masked region set to zero.
   * @throws IllegalArgumentException If the rectangle is out of image bounds or invalid.
   */
  public static Mat maskZonesMat(Mat original, Rectangle maskArea) {
    Mat output = original.clone();
    Rect rect = new Rect(maskArea.x, maskArea.y, maskArea.width, maskArea.height);

    // Bounds check
    if (rect.x() < 0
        || rect.y() < 0
        || rect.width() <= 0
        || rect.height() <= 0
        || rect.x() + rect.width() > output.cols()
        || rect.y() + rect.height() > output.rows()) {
      throw new IllegalArgumentException("Mask rectangle out of bounds: " + rect);
    }

    Mat roi = new Mat(output, rect);

    if (output.channels() == 1) {
      UByteRawIndexer indexer = roi.createIndexer();
      for (int y = 0; y < rect.height(); y++) {
        for (int x = 0; x < rect.width(); x++) {
          indexer.put(y, x, 0);
        }
      }
      indexer.release();
    } else if (output.channels() == 3) {
      UByteRawIndexer indexer = roi.createIndexer();
      for (int y = 0; y < rect.height(); y++) {
        for (int x = 0; x < rect.width(); x++) {
          indexer.put(y, x, 0, 0); // B
          indexer.put(y, x, 1, 0); // G
          indexer.put(y, x, 2, 0); // R
        }
      }
      indexer.release();
    }

    roi.release();
    return output;
  }
}

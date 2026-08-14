package com.chromascape.web.image;

import static org.bytedeco.opencv.global.opencv_core.CV_8U;
import static org.bytedeco.opencv.global.opencv_core.bitwise_and;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Utility class for applying a mask to an image using OpenCV.
 *
 * <p>The mask should be a single-channel 8-bit Mat where white pixels (255) indicate areas to keep
 * from the original image and black pixels (0) indicate areas to mask out (set to black).
 */
public class MaskImage {

  /**
   * Applies a mask to the original image.
   *
   * <p>This method takes an original color image and a mask image and returns a new image where
   * pixels outside the white areas of the mask are set to black (masked out). The mask is expected
   * to be a single-channel 8-bit Mat where white (255) pixels indicate the region to keep.
   *
   * <p>If the mask is not already a single-channel 8-bit Mat, it will be converted to grayscale and
   * 8-bit internally.
   *
   * @param original the original image Mat (e.g. 3- or 4-channel color image).
   * @param mask the mask image Mat; should be same size as original.
   * @return a new Mat containing the original image masked by the mask.
   * @throws IllegalArgumentException if original or mask is null, or if they have different sizes.
   */
  public static Mat applyMaskToImage(Mat original, Mat mask) {
    if (original == null || mask == null) {
      throw new IllegalArgumentException("Original image and mask must not be null");
    }
    if (original.rows() != mask.rows() || original.cols() != mask.cols()) {
      throw new IllegalArgumentException("Original and mask must be the same size");
    }

    // Ensure mask is single channel 8-bit
    Mat maskGray = new Mat();
    if (mask.channels() != 1 || mask.depth() != CV_8U) {
      // Convert mask to grayscale and 8-bit if needed
      cvtColor(mask, maskGray, opencv_imgproc.COLOR_BGR2GRAY);
      maskGray.convertTo(maskGray, CV_8U);
    } else {
      maskGray = mask.clone();
    }

    // Create output Mat same size and type as original
    Mat output =
        new Mat(
            original.size(),
            original.type(),
            new Scalar(0, 0, 0, 0)); // black transparent background

    // Copy original pixels where mask is white
    // This can be done using bitwise_and with mask applied to each channel
    bitwise_and(original, original, output, maskGray);

    return output;
  }
}

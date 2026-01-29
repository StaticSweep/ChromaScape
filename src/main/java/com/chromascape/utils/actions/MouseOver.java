package com.chromascape.utils.actions;

import static org.bytedeco.opencv.global.opencv_core.bitwise_or;
import static org.bytedeco.opencv.global.opencv_core.inRange;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2HSV;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.opencv.core.CvType.CV_8UC1;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.domain.ocr.Ocr;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * An actions utility to provide a high level API for MouseOverText.
 *
 * <p>Uses OpenCV to iterate over a list of colours, and collates the resulting image into one
 * overall mask. This allows the user to get the text of the whole MouseOverText zone regardless of
 * colour.
 *
 * <p>Allows the user to grab the MouseOverText immediately as a string, excluding spaces.
 */
public class MouseOver {

  /** Colours that exist within the MouseOverText zone. */
  private static final List<ColourObj> colours =
      new ArrayList<>(
          Arrays.asList(
              new ColourObj("TEXT_CYAN", new Scalar(80, 180, 200, 0), new Scalar(100, 255, 255, 0)),
              new ColourObj(
                  "TEXT_OFF_WHITE", new Scalar(0, 0, 190, 0), new Scalar(180, 30, 255, 0)),
              new ColourObj("TEXT_ORANGE", new Scalar(8, 140, 180, 0), new Scalar(22, 220, 255, 0)),
              new ColourObj("TEXT_GREEN", new Scalar(50, 190, 100, 0), new Scalar(95, 255, 255, 0)),
              new ColourObj(
                  "TEXT_YELLOW", new Scalar(25, 130, 190, 0), new Scalar(35, 255, 255, 0)),
              new ColourObj("TEXT_RED", new Scalar(0, 190, 190, 0), new Scalar(8, 255, 255, 0))));

  /**
   * Captures the minimap to extract all possible colours. Layers the captures to create a mask
   * containing all text regardless of colour. Searches for text based on this.
   *
   * @param baseScript Your script instance, typically {@code this}.
   * @return The string found within the MouseOverText zone (No spaces).
   * @throws IOException If font loading failed within OCR.
   */
  public static String getText(BaseScript baseScript) throws IOException {
    // Get image of MouseOverText
    Rectangle zone = baseScript.controller().zones().getMouseOver();
    BufferedImage capture = ScreenManager.captureZone(zone);

    // Convert the captured BGR image to HSV once here,
    // so we don't have to do it inside the loop for every single colour
    Mat hsvMat = new Mat();
    try (Mat bgrMat = Java2DFrameUtils.toMat(capture)) {
      cvtColor(bgrMat, hsvMat, COLOR_BGR2HSV);
    }

    // Accumulate all colour matches into a single binary mask
    try (Mat combinedMask =
            new Mat(capture.getHeight(), capture.getWidth(), CV_8UC1, new Scalar(0));
        Mat tempMask = new Mat()) { // Reusable mask for the loop using try with resources

      for (ColourObj c : colours) {
        // In memory thresholding using the pre-converted HSV Mat
        try (Mat min = new Mat(c.hsvMin());
            Mat max = new Mat(c.hsvMax())) {
          inRange(hsvMat, min, max, tempMask);
          bitwise_or(combinedMask, tempMask, combinedMask);
        }
      }

      // Cleanup
      hsvMat.release();

      return Ocr.extractTextFromMask(combinedMask, "Bold 12", true);
    }
  }
}

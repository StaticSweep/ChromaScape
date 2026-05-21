package com.chromascape.utils.actions;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.ColourContours;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.domain.ocr.Ocr;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * An actions utility to provide a high level API for MouseOverText. Allows the user to get the text
 * of the MouseOverText zone regardless of colour.
 *
 * <p>Allows the user to grab the MouseOverText as a string, excluding spaces.
 */
public class MouseOver {

  // Higher -> brighter pixels are considered shadows
  private static final int SHADOW_THRESHOLD = 120;

  // Lower -> stricter tolerance on similar colours
  private static final int COLOUR_TOLERANCE = 20;

  private static final ColourObj ORANGE =
      new ColourObj("Orange", new Scalar(16, 224, 255, 0), new Scalar(16, 224, 255, 0));

  private static final Mat OLIVE = new Mat(1, 1, CV_8UC3, new Scalar(17, 73, 73, 0));

  /**
   * Extracts text from the MouseOverText zone. Does not include spaces.
   *
   * @param baseScript {@code this} the script that the user is running.
   * @return a String of all text found.
   */
  public static String getText(BaseScript baseScript) {
    // Get image of MouseOverText
    Rectangle zone = baseScript.controller().zones().getMouseOver();
    BufferedImage capture = ScreenManager.captureZone(zone);
    // BGR image of the zone
    Mat image = TemplateMatching.bufferedImageToMat(capture);
    // Replace orange to olive to exclude interface colours near zone
    Mat orangeMask = ColourContours.extractColours(image, ORANGE);
    image.setTo(OLIVE, orangeMask);
    // Release mask
    orangeMask.release();
    // Extract possible character colours based on shadow
    Set<Integer> textColours = extractTextColour(image);
    Object[] uniqueTextColours = removeDuplicatesWithinRange(textColours);
    // Set non character pixels to black
    Mat mask = maskText(uniqueTextColours, image);
    // For testing
    // DisplayImage.display(TemplateMatching.matToBufferedImage(mask));
    return Ocr.extractTextFromMask(mask, "Bold 12", true);
  }

  /**
   * Eliminates any colours that can be considered similar enough within the colour threshold.
   *
   * @param textColours a list of packed RGB integers.
   * @return Unique colours that can't be considered similar.
   */
  private static Object[] removeDuplicatesWithinRange(Set<Integer> textColours) {
    List<Integer> palette = new ArrayList<>();

    for (Integer newColour : textColours) {
      boolean found = false;

      for (Integer existingColour : palette) {

        if (isInThreshold(existingColour, newColour)) {
          found = true;
          break;
        }
      }

      if (!found) {
        palette.add(newColour);
      }
    }
    return palette.toArray();
  }

  /**
   * Compares each pixel in an image against a palette of unique colours that represent text colours
   * in an image. Creates a mask with white pixels where text should be and the background black.
   *
   * @param colours a list of unique colours not within the colour threshold of each other.
   * @param image the image containing text to mask.
   * @return a CV_8UC1 mask with white pixels where text should be and the rest black.
   */
  private static Mat maskText(Object[] colours, Mat image) {
    Mat mask = new Mat(image.rows(), image.cols(), CV_8UC1, new Scalar(0));
    UByteIndexer img = image.createIndexer();
    UByteIndexer out = mask.createIndexer();

    for (int y = 0; y < image.rows(); y++) {
      for (int x = 0; x < image.cols(); x++) {
        int b = img.get(y, x, 0) & 0xFF;
        int g = img.get(y, x, 1) & 0xFF;
        int r = img.get(y, x, 2) & 0xFF;
        int packed = (r << 16) | (g << 8) | b;

        boolean isWhite = false;

        for (Object colour : colours) {
          Integer colourInt = (Integer) colour;

          if (isInThreshold(colourInt, packed)) {
            isWhite = true;
            break;
          }
        }

        out.put(y, x, isWhite ? 255 : 0);
      }
    }

    img.release();
    out.release();
    return mask;
  }

  /**
   * Compares the Euclidean distance between the RGB values of two pixels against a static tolerance
   * value to gauge if they are similar enough.
   *
   * @param colour1 one of the colours to compare.
   * @param colour2 one of the colours to compare.
   * @return whether the colours are similar enough to consider part of the same text colour.
   */
  private static boolean isInThreshold(int colour1, int colour2) {
    // Unpack first colour
    int r = (colour1 >> 16) & 0xFF;
    int g = (colour1 >> 8) & 0xFF;
    int b = colour1 & 0xFF;
    // Unpack second
    int r2 = (colour2 >> 16) & 0xFF;
    int g2 = (colour2 >> 8) & 0xFF;
    int b2 = colour2 & 0xFF;
    // Calculate difference
    int dr = r - r2;
    int dg = g - g2;
    int db = b - b2;
    // Calculate distance^2
    int dist2 = dr * dr + dg * dg + db * db;
    return dist2 <= COLOUR_TOLERANCE * COLOUR_TOLERANCE;
  }

  private static boolean isShadow(int r, int g, int b) {
    return ((r + g + b) / 3 < SHADOW_THRESHOLD)
        && (r < SHADOW_THRESHOLD * 2)
        && (g < SHADOW_THRESHOLD * 2)
        && (b < SHADOW_THRESHOLD * 2);
  }

  /**
   * Iterates through an image's pixels, determines whether a pixel is a shadow of text based on
   * brightness, and then saves the pixel top-left of it. Returns a set of unique pixels that are
   * considered to be part of text.
   *
   * @param image the input image which may contain text.
   * @return a unique set of packed RGB integers representing pixels belonging to text.
   */
  private static Set<Integer> extractTextColour(Mat image) {
    int width = image.cols();
    int height = image.rows();

    UByteIndexer indexer = image.createIndexer();

    Set<Integer> textColours = new HashSet<>();

    for (int y = 1; y < height; y++) {
      for (int x = 1; x < width; x++) {

        int blue = indexer.get(y, x, 0) & 0xFF;
        int green = indexer.get(y, x, 1) & 0xFF;
        int red = indexer.get(y, x, 2) & 0xFF;

        if (isShadow(red, green, blue)) {
          int matchBlue = indexer.get(y - 1, x - 1, 0) & 0xFF;
          int matchGreen = indexer.get(y - 1, x - 1, 1) & 0xFF;
          int matchRed = indexer.get(y - 1, x - 1, 2) & 0xFF;

          if (isShadow(matchRed, matchGreen, matchBlue)) {
            continue;
          }

          int r = matchRed & 0xFF;
          int g = matchGreen & 0xFF;
          int b = matchBlue & 0xFF;

          int packed = (r << 16) | (g << 8) | b;

          textColours.add(packed);
        }
      }
    }
    return textColours;
  }
}

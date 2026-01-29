package com.chromascape.utils.domain.ocr;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.minMaxLoc;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.FILLED;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_8;
import static org.bytedeco.opencv.global.opencv_imgproc.TM_CCOEFF_NORMED;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.matchTemplate;
import static org.bytedeco.opencv.global.opencv_imgproc.rectangle;

import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.ColourContours;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.domain.zones.MaskZones;
import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Provides Ocr (Optical Character Recognition) functionality using JavaCV/OpenCV. Allows for
 * font-based glyph matching in screen-captured images to extract text.
 */
public class Ocr {

  /** Stores successful character matches during Ocr extraction. */
  private static final List<CharMatch> matches = new ArrayList<>();

  /** Cached zero scalar to prevent CPU allocation fatigue */
  private static final Scalar ZERO_SCALAR = new Scalar(0);

  /** Cache for loaded fonts to prevent disk I/O on every OCR call. */
  private static final Map<String, Map<String, Mat>> fontCache = new HashMap<>();

  /**
   * Allowed characters for OCR to remove runtime overhead for unnecessary glyphs. Most common
   * characters found.
   */
  private static final String ALLOWED_CHARS =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789()[],&-:/*'_\"?";

  /**
   * Loads a font glyph set from disk, converts each glyph to grayscale, and stores in a map. Uses
   * an internal cache to avoid repeated disk I/O. Only allows whitelisted glyphs ( please add if
   * necessary).
   *
   * @param font Name of the font folder inside resources.
   * @return A map from character string to Mat (glyph image).
   * @throws IOException if font data cannot be read.
   */
  public static synchronized Map<String, Mat> loadFont(String font) throws IOException {
    if (fontCache.containsKey(font)) {
      return fontCache.get(font);
    }

    Map<String, Mat> fontMap = new HashMap<>();

    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    Ocr.class.getResourceAsStream("/fonts/" + font + "/" + font + ".index"))))) {

      String fontFileName;
      while ((fontFileName = reader.readLine()) != null) {

        String fileName = fontFileName.replace(".bmp", "");
        int codePoint = Integer.parseInt(fileName);
        String character = String.valueOf((char) codePoint);

        if (!ALLOWED_CHARS.contains(character)) {
          continue;
        }

        try (InputStream is =
            Ocr.class.getResourceAsStream("/fonts/" + font + "/" + fontFileName)) {
          if (is == null) {
            throw new FileNotFoundException("Missing font image: " + fontFileName);
          }
          Mat img = Java2DFrameUtils.toMat(ImageIO.read(is));
          cvtColor(img, img, COLOR_BGR2GRAY);
          fontMap.put(character, img);
        }
      }
    }
    fontCache.put(font, fontMap);
    return fontMap;
  }

  /**
   * Extracts a string of text from a screen region by template-matching glyphs from a font. Note:
   * this will not include any spaces.
   *
   * @param zone Rectangle on screen to extract text from.
   * @param font Font name to use for glyph matching.
   * @param colour ColourObj specifying the color to isolate.
   * @param clean Whether to clear internal match storage after use.
   * @return The extracted text string from the zone.
   * @throws IOException if font images cannot be read.
   */
  public static String extractText(Rectangle zone, String font, ColourObj colour, boolean clean)
      throws IOException {
    Map<String, Mat> fontMap = loadFont(font);
    matches.clear();
    BufferedImage zoneImage = ScreenManager.captureZone(zone);
    Mat zoneMat = ColourContours.extractColours(zoneImage, colour);
    return extraction(fontMap, zoneMat, font, clean);
  }

  /**
   * Extracts a string of text from a screen region by template-matching glyphs from a font. Note:
   * this will not include any spaces.
   *
   * @param mask Mat CU81 mask to extract text from
   * @param font Font name to use for glyph matching.
   * @param clean Whether to clear internal match storage after use.
   * @return The extracted text string from the zone.
   * @throws IOException if font images cannot be read.
   */
  public static String extractTextFromMask(Mat mask, String font, boolean clean)
      throws IOException {
    Map<String, Mat> fontMap = loadFont(font);
    matches.clear();
    return extraction(fontMap, mask.clone(), font, clean);
  }

  /**
   * Internal function to perform Template matched OCR. Iterates over a font map, zeroing out the
   * convolution as it goes.
   *
   * @param fontMap List of glyphs, string character & Mat bitmap.
   * @param zoneMat Mat image of the source being searched within.
   * @param font Type of font.
   * @param clean Delete matches?
   * @return String of extracted letters, no spaces.
   */
  private static String extraction(
      Map<String, Mat> fontMap, Mat zoneMat, String font, boolean clean) {
    double threshold = 0.99;
    // Supports (CV_8UC1) binary greyscale.
    // Holds pointers and correlation as reusable memory allocation to avoid JNI overhead
    try (DoublePointer minVal = new DoublePointer(1);
        DoublePointer maxVal = new DoublePointer(1);
        Point minLoc = new Point();
        Point maxLoc = new Point();
        Mat correlation = new Mat()) {
      // Template match each glyph in the font to the zoneMat.
      for (String glyph : fontMap.keySet()) {
        // These are to store the glyph sizes outside of try with resources scope.
        int glyphImgRows;
        int glyphImgCols;

        // We are trimming the font images and template matching -
        // Based on the font type and how the image is stored.
        int ycropModifier = getCropModifierForFont(font);

        try (Rect roi =
                new Rect(
                    0,
                    ycropModifier,
                    fontMap.get(glyph).arrayWidth(),
                    fontMap.get(glyph).arrayHeight() - ycropModifier);
            Mat croppedGlyph = new Mat(fontMap.get(glyph), roi)) {
          // Match template with cropped glyph and store size outside try-with-resources.
          matchTemplate(zoneMat, croppedGlyph, correlation, TM_CCOEFF_NORMED);
          glyphImgRows = croppedGlyph.rows();
          glyphImgCols = croppedGlyph.cols();
        }
        // Call minMaxLoc repeatedly, zero out the area based on glyph size, save locations as
        // CharMatch objs.
        while (true) { // Loop breaks when threshold is not met.
          minMaxLoc(correlation, minVal, maxVal, minLoc, maxLoc, null);

          if (maxVal.get() < threshold) {
            break;
          }

          Rectangle matchLocation =
              new Rectangle(maxLoc.x(), maxLoc.y(), glyphImgCols, glyphImgRows);
          matches.add(
              new CharMatch(glyph, matchLocation.x, matchLocation.y, glyphImgCols, glyphImgRows));

          zeroOutRegion(correlation, matchLocation);

          Mat oldZoneMat = zoneMat;
          zoneMat = MaskZones.maskZonesMat(zoneMat.clone(), matchLocation);
          oldZoneMat.release();
        }
      }
    } finally {
      zoneMat.release();
    }

    // Sort CharMatch objects based on left-most positions.
    matches.sort(Comparator.comparingInt(CharMatch::y).thenComparingInt(CharMatch::x));

    StringBuilder result = new StringBuilder();
    for (CharMatch match : matches) {
      result.append(match.character());
    }

    if (clean) {
      matches.clear();
    }

    return result.toString();
  }

  /**
   * Returns a BufferedImage mask representing matched glyph positions within a screen region. This
   * is useful for clicking text. You are intended to extract contours from this and use it as a
   * ChromaObj.
   *
   * @param zone Rectangle on screen to perform Ocr in.
   * @param font Font name to use for glyph matching.
   * @param text Expected string result; skips mask generation if mismatched.
   * @param colour ColourObj specifying the color to isolate.
   * @return A BufferedImage mask of the matched character zones, or null if text doesn't match.
   * @throws IOException if font images cannot be read.
   * @throws AWTException if screen capture fails.
   */
  public static BufferedImage extractTextLocationMask(
      Rectangle zone, String font, String text, ColourObj colour) throws IOException, AWTException {
    // Get the full window bounds (this must match the screen capture bounds)
    Rectangle window = ScreenManager.getWindowBounds();

    // Create a black mask matching the window size
    Mat fullScreenMask = new Mat(window.height, window.width, CV_8UC1, new Scalar(0));

    // Early exit: text doesn't match expected
    if (!extractText(zone, font, colour, false).equals(text)) {
      return null;
    }

    // Create a zone-sized mask where matched characters will be drawn
    Mat zoneMask = new Mat(zone.height, zone.width, CV_8UC1, new Scalar(0));

    // Draw rectangles for matched characters
    for (CharMatch match : matches) {
      rectangle(
          zoneMask,
          new Point(match.x(), match.y()),
          new Point(match.x() + match.width(), match.y() + match.height()),
          new Scalar(255),
          FILLED,
          LINE_8,
          0);
    }

    // Convert screen-relative zone to window-relative position
    Mat roiMat = getMat(zone, window, fullScreenMask);
    zoneMask.copyTo(roiMat);

    // Release temporary mats
    zoneMask.release();
    roiMat.release();

    return Java2DFrameUtils.toBufferedImage(fullScreenMask);
  }

  /**
   * Converts a zone-relative rectangle to a window-relative Mat region for masking.
   *
   * @param zone Ocr region.
   * @param window Full window bounds from capture.
   * @param fullScreenMask The full-screen output mask.
   * @return A Mat region of interest inside the full screen mask.
   * @throws IllegalArgumentException if the zone is outside the screen bounds.
   */
  private static Mat getMat(Rectangle zone, Rectangle window, Mat fullScreenMask) {
    int relX = zone.x - window.x;
    int relY = zone.y - window.y;

    // Validate bounds to avoid OpenCV crash
    if (relX < 0
        || relY < 0
        || relX + zone.width > window.width
        || relY + zone.height > window.height) {
      throw new IllegalArgumentException(
          "Zone is outside the window bounds: zone=" + zone + ", window=" + window);
    }

    // Create region of interest in the full mask and copy the zone mask into it
    Rect roi = new Rect(relX, relY, zone.width, zone.height);
    return new Mat(fullScreenMask, roi);
  }

  /**
   * Sets all values in a rectangular region of a correlation matrix to zero. This prevents repeated
   * template matches in the same area.
   *
   * @param correlation The template match result matrix.
   * @param match The rectangle area to zero out.
   */
  public static void zeroOutRegion(Mat correlation, Rectangle match) {
    // Make sure the rectangle is within bounds of the correlation Mat
    int x = Math.max(match.x, 0);
    int y = Math.max(match.y, 0);
    int width = Math.min(match.width, correlation.cols() - x);
    int height = Math.min(match.height, correlation.rows() - y);

    if (width <= 0 || height <= 0) {
      return;
    }

    Rect roi = new Rect(x, y, width, height);
    Mat subMat = new Mat(correlation, roi);
    // Set all pixels in this region to 0 (lowest confidence)
    subMat.setTo(new Mat(ZERO_SCALAR));
    subMat.release();
  }

  /**
   * Returns a vertical crop offset used when slicing glyph images, depending on font type.
   *
   * @param font Font name.
   * @return Crop offset in pixels.
   */
  private static int getCropModifierForFont(String font) {
    return Objects.equals(font, "Plain 12") ? 2 : 1;
  }
}

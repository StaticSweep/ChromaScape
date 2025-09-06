package com.chromascape.utils.domain.walker;

import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2BGR;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_RGB2BGR;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

import com.chromascape.controller.Controller;
import com.chromascape.utils.core.screen.topology.Similarity;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.web.logs.LogService;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Handles detection of the in-game compass orientation by comparing the current compass state
 * against a preloaded library of compass images for each degree.
 *
 * <p>This class enables angle-based transformations, such as rotating click positions on the
 * minimap to match the player's camera orientation.
 *
 * <p>Images are preloaded from resources on construction, and each query is performed via
 * structural similarity (SSIM) against all 360 cached images.
 */
public class Compass {

  private final Controller controller;
  private Map<Integer, Mat> compassLibrary;

  /**
   * Constructs a {@code Compass} instance and initializes the compass image library from bundled
   * resources. The library contains 360 images representing each degree of the compass.
   *
   * @param controller Provides access to client zones and state for determining compass position on
   *     screen.
   * @param logger Log service for reporting errors that may occur during resource loading.
   */
  public Compass(Controller controller, LogService logger) {
    this.controller = controller;
    try {
      this.compassLibrary = loadCompass();
    } catch (IOException e) {
      logger.addLog(e.getMessage());
    }
  }

  /**
   * Loads the full compass image library into memory. This method resolves the correct resource
   * path depending on whether the client is in fixed or resizable mode, then loads and converts
   * each degree image into an OpenCV {@link Mat}.
   *
   * <p>Processing is parallelized for faster startup.
   *
   * @return A {@link Map} from compass angle (0–359) to its corresponding {@link Mat}
   *     representation.
   * @throws IOException If any resource image fails to load.
   */
  private Map<Integer, Mat> loadCompass() throws IOException {
    String location = "/images/ui/compass_degrees/";
    String path;
    Map<Integer, Mat> compassLibrary = new HashMap<>();
    // Checks if the client is fixed and changes path accordingly
    if (controller.zones().getIsFixed()) {
      path = location + "fixed_classic";
    } else {
      path = location + "resizable_classic";
    }
    // Parallelized for loop to load assets
    IntStream.range(0, 360)
        .parallel()
        .forEach(
            i -> {
              Mat compass;
              try {
                compass = TemplateMatching.loadMatFromResource(path + String.format("/%d.png", i));
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
              cvtColor(compass, compass, COLOR_BGRA2BGR);
              compassLibrary.put(i, compass);
            });
    return compassLibrary;
  }

  /**
   * Determines the current orientation of the in-game compass by comparing the captured compass
   * region against the preloaded compass library.
   *
   * <p>For each degree (0–359), a mean structural similarity (MSSIM) score is calculated between
   * the captured frame and the stored compass image. The angle with the highest similarity score is
   * returned.
   *
   * <p>If the maximum similarity is shared by a cardinal direction (0°, 90°, 180°, 270°), that
   * cardinal angle is returned preferentially for stability.
   *
   * @return The detected compass angle in degrees (0–359).
   */
  public int getCompassAngle() {
    Rectangle zone = controller.zones().getMinimap().get("compassSimilarity");
    BufferedImage img = ScreenManager.captureZone(zone);
    double[] similarities = new double[360];
    // Parallel similarity checks
    try (Mat compass = Java2DFrameUtils.toMat(img)) {
      cvtColor(compass, compass, COLOR_RGB2BGR);
      IntStream.range(0, 360)
          .parallel()
          .forEach(
              i -> {
                Scalar similarity = Similarity.getMSSIM(compassLibrary.get(i), compass);
                similarities[i] = (similarity.get(0) + similarity.get(1) + similarity.get(2)) / 3.0;
              });
    }
    // Grabbing the max value
    double max = -Double.MAX_VALUE;
    int maxIndex = -1;
    for (int i = 0; i < similarities.length; i++) {
      if (similarities[i] > max) {
        max = similarities[i];
        maxIndex = i;
      }
    }
    // Return a cardinal if it shares a max value
    int[] cardinals = new int[] {0, 90, 180, 270};
    for (int cardinal : cardinals) {
      if (similarities[cardinal] == max) {
        return cardinal;
      }
    }
    // Default if not cardinal
    return maxIndex;
  }
}

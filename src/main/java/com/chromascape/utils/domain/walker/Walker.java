package com.chromascape.utils.domain.walker;

import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2BGR;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_RGB2BGR;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

import com.chromascape.api.Dax;
import com.chromascape.controller.Controller;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.Similarity;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.domain.ocr.Ocr;
import com.chromascape.web.logs.LogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

public class Walker {

  private final Controller controller;
  private final LogService logger;
  private final Dax dax;
  private final ObjectMapper objectMapper;
  private static final boolean isLoaded = false;
  private Map<Integer, Mat> compassLibrary;

  public Walker(Controller controller, LogService logger) {
    this.controller = controller;
    this.logger = logger;
    this.dax = new Dax();
    this.objectMapper = new ObjectMapper();
    try {
      this.compassLibrary = loadCompass();
    } catch (IOException e) {
      logger.addLog(e.getMessage());
    }
  }

  public List<Tile> getPath(Point end, boolean isMembers) throws IOException, InterruptedException {
    Rectangle zone = controller.zones().getGridInfo().get("Tile");
    ColourObj colour = ColourInstances.getByName("White");
    // Extracts the position using OCR and splits it into a 3 value list (x, y, z)
    List<String> position =
        Arrays.asList(Ocr.extractText(zone, "Plain 12", colour, true).split(","));
    // Sends the payload through the DAX API and saves the raw output
    String path =
        dax.generatePath(
            new Point(Integer.parseInt(position.get(0)), Integer.parseInt(position.get(1))),
            end,
            isMembers);
    // Deserializes the raw output using Jackson
    DaxPath daxPath = objectMapper.readValue(path, DaxPath.class);
    return daxPath.path();
  }

  public Map<Integer, Mat> loadCompass() throws IOException {
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
              Mat compass = null;
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
    // Grabbing the index of the max value
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

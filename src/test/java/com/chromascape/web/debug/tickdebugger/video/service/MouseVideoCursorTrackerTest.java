package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Golden synthetic cases for {@code cursor-template/v2}. Frames are produced by exact area
 * downscaling of a full-resolution scene, as ffmpeg's {@code flags=area} does, so expected hotspot
 * coordinates are known exactly. These establish mechanics only; accuracy qualification is the
 * held-out FA12 corpus run.
 */
class MouseVideoCursorTrackerTest {
  private static final String[] ARROW = {
    "B...........",
    "BB..........",
    "BWB.........",
    "BWWB........",
    "BWWWB.......",
    "BWWWWB......",
    "BWWWWWB.....",
    "BWWWWWWB....",
    "BWWWWWWWB...",
    "BWWWWWWWWB..",
    "BWWWWWWWWWB.",
    "BWWWWWWBBBBB",
    "BWWWBWWB....",
    "BWWBBWWB....",
    "BWB..BWWB...",
    "BB...BWWB...",
    "B.....BWWB..",
    "......BWWB..",
    ".......BB...",
  };

  private static final int SOURCE_WIDTH = 764;
  private static final int SOURCE_HEIGHT = 502;
  private static final int ANALYSIS_WIDTH = 480;
  private static final int ANALYSIS_HEIGHT = 316;

  @Test
  void locatesTheHotspotOnTexturedBackgroundsAfterAreaDownscale() {
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    Random random = new Random(2150);
    List<MouseVideoCursorTracker.Context> calibration = new ArrayList<>();
    List<int[]> truth = new ArrayList<>();
    List<MouseVideoCursorTracker.Context> frames = new ArrayList<>();
    for (int frame = 0; frame < 30; frame++) {
      int x = 20 + random.nextInt(SOURCE_WIDTH - 60);
      int y = 20 + random.nextInt(SOURCE_HEIGHT - 60);
      MouseVideoCursorTracker.Context analysis = alone(downscale(scene(random.nextLong(), x, y)));
      frames.add(analysis);
      truth.add(new int[] {x, y});
      calibration.add(analysis);
    }
    Map<String, Object> evidence =
        tracker.calibrate(
            calibration, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);
    assertThat(evidence.get("calibrated")).isEqualTo(true);
    assertThat(evidence.get("sourceScale")).isEqualTo(1.0d);
    for (int index = 0; index < frames.size(); index++) {
      MouseVideoCursorTracker.Detection detection = tracker.track(frames.get(index));
      assertThat(detection.visible()).as("frame %d", index).isTrue();
      assertThat(Math.abs(detection.x() - truth.get(index)[0])).isLessThanOrEqualTo(1);
      assertThat(Math.abs(detection.y() - truth.get(index)[1])).isLessThanOrEqualTo(1);
    }
  }

  @Test
  void reportsAbsentAndDuplicatedCursorsAsAmbiguousInsteadOfGuessing() {
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    List<MouseVideoCursorTracker.Context> calibration = new ArrayList<>();
    for (int frame = 0; frame < 10; frame++) {
      calibration.add(alone(downscale(scene(frame, 100 + frame * 30, 120))));
    }
    tracker.calibrate(calibration, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);

    MouseVideoCursorTracker.Detection absent = tracker.track(alone(downscale(scene(99, -1, -1))));
    assertThat(absent.visible()).isFalse();

    int[][] doubled = scene(7, 200, 150);
    drawArrow(doubled, 520, 300);
    MouseVideoCursorTracker.Detection duplicate = tracker.track(alone(downscale(doubled)));
    assertThat(duplicate.visible()).isFalse();
    assertThat(duplicate.evidence().get("reason")).isEqualTo("competing placement");
  }

  @Test
  void staysUncalibratedWithoutAnyConfidentMatch() {
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    List<MouseVideoCursorTracker.Context> calibration = new ArrayList<>();
    for (int frame = 0; frame < 5; frame++) {
      calibration.add(alone(downscale(scene(frame, -1, -1))));
    }
    Map<String, Object> evidence =
        tracker.calibrate(
            calibration, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);
    assertThat(evidence.get("calibrated")).isEqualTo(false);
    MouseVideoCursorTracker.Detection detection = tracker.track(calibration.get(0));
    assertThat(detection.visible()).isFalse();
    assertThat(detection.evidence().get("reason")).isEqualTo("uncalibrated");
  }

  @Test
  void ignoresStationaryLookalikesButKeepsStoppedCursor() {
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    // One fixed background carrying a static cursor-shaped decoy; the real cursor moves, then
    // stops.
    int[][] decoy = scene(5, 600, 400);
    List<int[][]> frames = new ArrayList<>();
    List<int[]> truth = new ArrayList<>();
    for (int frame = 0; frame < 24; frame++) {
      int x = frame < 16 ? 60 + frame * 25 : 435;
      int y = frame < 16 ? 80 + frame * 9 : 215;
      int[][] image = copy(decoy);
      drawArrow(image, x, y);
      frames.add(downscale(image));
      truth.add(new int[] {x, y});
    }
    List<MouseVideoCursorTracker.Context> contexts = new ArrayList<>();
    for (int index = 0; index < frames.size(); index++) {
      List<int[][]> neighbors = new ArrayList<>();
      if (index >= 4) {
        neighbors.add(frames.get(index - 4));
      }
      if (index + 4 < frames.size()) {
        neighbors.add(frames.get(index + 4));
      }
      contexts.add(
          new MouseVideoCursorTracker.Context(
              frames.get(index), neighbors, index > 0 ? frames.get(index - 1) : null));
    }
    tracker.calibrate(contexts, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);
    for (int index = 0; index < contexts.size(); index++) {
      MouseVideoCursorTracker.Detection detection = tracker.track(contexts.get(index));
      assertThat(detection.visible()).as("frame %d", index).isTrue();
      assertThat(Math.abs(detection.x() - truth.get(index)[0])).isLessThanOrEqualTo(1);
      assertThat(Math.abs(detection.y() - truth.get(index)[1])).isLessThanOrEqualTo(1);
    }
    int[][] backgroundOnly = downscale(decoy);
    MouseVideoCursorTracker.Detection onlyDecoy =
        tracker.track(
            new MouseVideoCursorTracker.Context(
                backgroundOnly, List.of(backgroundOnly, backgroundOnly), backgroundOnly));
    assertThat(onlyDecoy.visible()).isFalse();
  }

  @Test
  void vanishedCursorIsNotHeldOnLookalike() {
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    // The decoy sits at (600, 400); the cursor moves onto it, rests, then disappears.
    int[][] decoy = scene(11, 600, 400);
    List<int[][]> frames = new ArrayList<>();
    for (int frame = 0; frame < 30; frame++) {
      int[][] image = copy(decoy);
      if (frame < 10) {
        drawArrow(image, 300 + frame * 30, 100 + frame * 30);
      } else if (frame < 16) {
        drawArrow(image, 600, 400);
      }
      frames.add(downscale(image));
    }
    List<MouseVideoCursorTracker.Context> contexts = new ArrayList<>();
    for (int index = 0; index < frames.size(); index++) {
      List<int[][]> neighbors = new ArrayList<>();
      if (index >= 4) {
        neighbors.add(frames.get(index - 4));
      }
      if (index + 4 < frames.size()) {
        neighbors.add(frames.get(index + 4));
      }
      contexts.add(
          new MouseVideoCursorTracker.Context(
              frames.get(index), neighbors, index > 0 ? frames.get(index - 1) : null));
    }
    tracker.calibrate(contexts, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);
    for (int index = 0; index < contexts.size(); index++) {
      MouseVideoCursorTracker.Detection detection = tracker.track(contexts.get(index));
      if (index >= 16) {
        assertThat(detection.visible()).as("frame %d after the cursor vanished", index).isFalse();
      }
    }
  }

  @Test
  void declaresCursorsTooSmallToResolveAtTheAnalysisResolution() {
    double nativeFactor = ANALYSIS_WIDTH / (double) SOURCE_WIDTH;
    assertThat(MouseVideoCursorTracker.resolvable(arrow(), 1.0d, nativeFactor)).isTrue();
    assertThat(MouseVideoCursorTracker.resolvable(arrow(), 0.3d, nativeFactor)).isFalse();
  }

  private static MouseVideoCursorTracker.Context alone(int[][] frame) {
    return new MouseVideoCursorTracker.Context(frame, List.of(), null);
  }

  private static int[][] copy(int[][] image) {
    int[][] copy = new int[image.length][];
    for (int row = 0; row < image.length; row++) {
      copy[row] = image[row].clone();
    }
    return copy;
  }

  /** Smooth mid-grey noise with an optional arrow whose hotspot is at source pixel (x, y). */
  private static int[][] scene(long seed, int x, int y) {
    Random random = new Random(seed);
    int[][] image = new int[SOURCE_HEIGHT][SOURCE_WIDTH];
    double a = random.nextDouble() * 6;
    double b = random.nextDouble() * 6;
    for (int row = 0; row < SOURCE_HEIGHT; row++) {
      for (int column = 0; column < SOURCE_WIDTH; column++) {
        double wave = Math.sin(column / 23.0d + a) * Math.cos(row / 17.0d + b);
        image[row][column] = (int) (110 + 40 * wave + random.nextInt(9) - 4);
      }
    }
    if (x >= 0) {
      drawArrow(image, x, y);
    }
    return image;
  }

  private static void drawArrow(int[][] image, int x, int y) {
    for (int row = 0; row < ARROW.length; row++) {
      for (int column = 0; column < ARROW[row].length(); column++) {
        char cell = ARROW[row].charAt(column);
        if (cell == '.') {
          continue;
        }
        image[y + row][x + column] = cell == 'B' ? 0 : 255;
      }
    }
  }

  /** Exact area-average resample to the analysis size. */
  private static int[][] downscale(int[][] source) {
    double fx = SOURCE_WIDTH / (double) ANALYSIS_WIDTH;
    double fy = SOURCE_HEIGHT / (double) ANALYSIS_HEIGHT;
    int[][] out = new int[ANALYSIS_HEIGHT][ANALYSIS_WIDTH];
    for (int j = 0; j < ANALYSIS_HEIGHT; j++) {
      for (int i = 0; i < ANALYSIS_WIDTH; i++) {
        double x0 = i * fx;
        double x1 = (i + 1) * fx;
        double y0 = j * fy;
        double y1 = (j + 1) * fy;
        double total = 0.0d;
        double weight = 0.0d;
        for (int v = (int) y0; v < Math.min(SOURCE_HEIGHT, Math.ceil(y1)); v++) {
          double wy = Math.min(y1, v + 1) - Math.max(y0, v);
          for (int u = (int) x0; u < Math.min(SOURCE_WIDTH, Math.ceil(x1)); u++) {
            double w = wy * (Math.min(x1, u + 1) - Math.max(x0, u));
            total += w * source[v][u];
            weight += w;
          }
        }
        out[j][i] = (int) Math.round(total / weight);
      }
    }
    return out;
  }

  private static BufferedImage arrow() {
    BufferedImage image =
        new BufferedImage(ARROW[0].length(), ARROW.length, BufferedImage.TYPE_INT_ARGB);
    for (int row = 0; row < ARROW.length; row++) {
      for (int column = 0; column < ARROW[row].length(); column++) {
        char cell = ARROW[row].charAt(column);
        int argb = cell == 'B' ? 0xff000000 : cell == 'W' ? 0xffffffff : 0x00000000;
        image.setRGB(column, row, argb);
      }
    }
    return image;
  }
}

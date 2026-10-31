package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
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
  private static final int MARK_X = 500;
  private static final int MARK_Y = 300;

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
  void aHoldDoesNotSurviveAMissedSample() {
    // Reproduction of the FA12 compressed-300k ghost: the cursor leaves over a cursor-like mark,
    // the sample in which it leaves is missed, and the mark, unchanged from then on, was resumed
    // as the "held" cursor because the hold compared with the last detection, not the previous
    // sample's.
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    int[][] background = scene(13, -1, -1);
    int[][] marked = copy(background);
    drawMark(marked, 600, 400);
    List<int[][]> moving = new ArrayList<>();
    for (int frame = 0; frame < 10; frame++) {
      int[][] image = copy(marked);
      drawArrow(image, 330 + frame * 30, 130 + frame * 30);
      moving.add(downscale(image));
    }
    List<MouseVideoCursorTracker.Context> contexts = new ArrayList<>();
    for (int index = 0; index < moving.size(); index++) {
      List<int[][]> neighbors = new ArrayList<>();
      neighbors.add(moving.get(Math.max(0, index - 4)));
      neighbors.add(moving.get(Math.min(moving.size() - 1, index + 4)));
      contexts.add(
          new MouseVideoCursorTracker.Context(
              moving.get(index), neighbors, index > 0 ? moving.get(index - 1) : null));
    }
    tracker.calibrate(contexts, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);
    MouseVideoCursorTracker.Detection onMark = null;
    for (MouseVideoCursorTracker.Context context : contexts) {
      onMark = tracker.track(context);
    }
    assertThat(onMark.visible()).as("cursor arriving on the mark").isTrue();

    int[][] flat = new int[ANALYSIS_HEIGHT][ANALYSIS_WIDTH];
    for (int[] row : flat) {
      Arrays.fill(row, 128);
    }
    assertThat(
            tracker
                .track(new MouseVideoCursorTracker.Context(flat, List.<int[][]>of(flat), flat))
                .visible())
        .as("the sample in which the cursor leaves is missed")
        .isFalse();

    int[][] markOnly = downscale(marked);
    MouseVideoCursorTracker.Detection afterMiss =
        tracker.track(
            new MouseVideoCursorTracker.Context(
                markOnly, List.of(moving.get(moving.size() - 1), markOnly), markOnly));
    assertThat(afterMiss.visible()).as("the unchanged mark after a missed sample").isFalse();
  }

  @Test
  void doesNotStartATrackOnAMarkThatAlreadyMatchedInThePreviousSample() {
    // Reproduction of the Langsamt s3 lock: a cursor-shaped mark is already matching while a
    // second identical placement makes the sample ambiguous; the competitor then disappears and
    // the survivor looks "changed" versus its 1.5 s neighbours. v5 opened a fresh track on it; a
    // real cursor must appear, so v6 refuses a placement that already matched one sample earlier.
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    int[][] base = scene(31, -1, -1);
    List<int[][]> frames = new ArrayList<>();
    for (int frame = 0; frame < 21; frame++) {
      int[][] image = copy(base);
      if (frame >= 10 && frame <= 12) {
        drawArrow(image, 600, 400);
      }
      if (frame == 10) {
        drawArrow(image, 120, 300);
      }
      if (frame >= 15) {
        drawArrow(image, 200 + (frame - 15) * 20, 120);
      }
      frames.add(downscale(image));
    }
    List<MouseVideoCursorTracker.Context> calibration = new ArrayList<>();
    for (int frame : new int[] {0, 5, 10, 11, 12, 15, 20}) {
      calibration.add(alone(frames.get(frame)));
    }
    tracker.calibrate(calibration, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);

    assertThat(tracker.track(context(frames, 10)).visible()).isFalse();
    assertThat(tracker.track(context(frames, 11)).visible()).isFalse();
    assertThat(tracker.track(context(frames, 12)).visible()).isFalse();
    // A cursor that genuinely arrives after frame 15 is still tracked.
    for (int frame = 16; frame < 21; frame++) {
      MouseVideoCursorTracker.Detection detection = tracker.track(context(frames, frame));
      assertThat(detection.visible()).as("arriving cursor at frame %d", frame).isTrue();
      assertThat(Math.abs(detection.x() - (200 + (frame - 15) * 20))).isLessThanOrEqualTo(2);
      assertThat(Math.abs(detection.y() - 120)).isLessThanOrEqualTo(2);
    }
  }

  @Test
  void keepsARestingCursorVisibleForFiveSeconds() {
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    // Frames are samples at 0.25 s, so 20 resting samples are five seconds of video.
    int[][] background = scene(17, -1, -1);
    List<int[][]> frames = new ArrayList<>();
    for (int frame = 0; frame < 32; frame++) {
      int[][] image = copy(background);
      int x = frame < 12 ? 80 + frame * 20 : 320;
      int y = frame < 12 ? 100 + frame * 12 : 244;
      drawArrow(image, x, y);
      frames.add(downscale(image));
    }
    List<MouseVideoCursorTracker.Context> calibration = new ArrayList<>();
    for (int frame = 0; frame < 12; frame++) {
      calibration.add(alone(frames.get(frame)));
    }
    tracker.calibrate(calibration, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);

    for (int frame = 0; frame < frames.size(); frame++) {
      MouseVideoCursorTracker.Detection detection = tracker.track(context(frames, frame));
      assertThat(detection.visible()).as("frame %d", frame).isTrue();
    }
  }

  @Test
  void rechecksTheWholeFrameWhileATrackExists() {
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    // 0.25 s samples, so the 2 s re-check falls every eight windowed samples.
    tracker.setSampleIntervalSeconds(0.25d);
    int[][] background = scene(23, -1, -1);
    List<int[][]> frames = new ArrayList<>();
    for (int frame = 0; frame < 32; frame++) {
      int[][] image = copy(background);
      int x = frame < 12 ? 90 + frame * 10 : 200;
      drawArrow(image, x, frame < 12 ? 90 : 150);
      frames.add(downscale(image));
    }
    List<MouseVideoCursorTracker.Context> calibration = new ArrayList<>();
    for (int frame = 0; frame < 12; frame++) {
      calibration.add(alone(frames.get(frame)));
    }
    tracker.calibrate(calibration, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);

    boolean rechecked = false;
    for (int frame = 0; frame < 32; frame++) {
      MouseVideoCursorTracker.Detection detection = tracker.track(context(frames, frame));
      rechecked |= Boolean.TRUE.equals(detection.evidence().get("rechecked"));
      assertThat(detection.visible()).as("frame %d", frame).isTrue();
      int expectedX = frame < 12 ? 90 + frame * 10 : 200;
      int expectedY = frame < 12 ? 90 : 150;
      assertThat(Math.abs(detection.x() - expectedX))
          .as("frame %d x", frame)
          .isLessThanOrEqualTo(2);
      assertThat(Math.abs(detection.y() - expectedY))
          .as("frame %d y", frame)
          .isLessThanOrEqualTo(2);
    }
    assertThat(rechecked).isTrue();
  }

  @Test
  void aHeldStaticMarkYieldsToAMovingCursorAtTheNextRecheck() {
    // The Langsamt s3 trap: the cursor rests on a cursor-like interface mark and leaves. The mark
    // then continues the track (it differs from the neighbour that still shows the cursor, then
    // holds), and the windowed search never looks at the real cursor. The 2 s whole-frame re-check
    // must hand the track over.
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    // 0.25 s samples, so the re-check falls on every eighth windowed sample: frames 8, 16, 24.
    tracker.setSampleIntervalSeconds(0.25d);
    int[][] background = scene(41, -1, -1);
    drawMark(background, MARK_X, MARK_Y);
    List<int[][]> frames = new ArrayList<>();
    List<int[]> truth = new ArrayList<>();
    for (int frame = 0; frame < 32; frame++) {
      int[][] image = copy(background);
      // Arrives over frames 0-4, rests on the mark for one second, then leaves for good.
      int x = frame <= 8 ? MARK_X - Math.max(0, 4 - frame) * 15 : 150 + (frame - 9) * 10;
      int y = frame <= 8 ? MARK_Y - Math.max(0, 4 - frame) * 8 : 80 + (frame - 9) * 5;
      drawArrow(image, x, y);
      frames.add(downscale(image));
      truth.add(new int[] {x, y});
    }
    List<MouseVideoCursorTracker.Context> calibration = new ArrayList<>();
    for (int frame = 0; frame < 9; frame++) {
      calibration.add(alone(frames.get(frame)));
    }
    tracker.calibrate(calibration, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);

    List<MouseVideoCursorTracker.Detection> detections = new ArrayList<>();
    for (int frame = 0; frame < 32; frame++) {
      detections.add(tracker.track(context(frames, frame)));
    }
    // Precondition: the trap is real. Between re-checks the track sits on the mark.
    for (int frame = 10; frame < 16; frame++) {
      MouseVideoCursorTracker.Detection detection = detections.get(frame);
      assertThat(detection.visible()).as("held mark at frame %d", frame).isTrue();
      assertThat(Math.abs(detection.x() - MARK_X)).as("frame %d", frame).isLessThanOrEqualTo(2);
      assertThat(detection.evidence().get("rule")).as("frame %d", frame).isEqualTo("held");
    }
    MouseVideoCursorTracker.Detection handover = detections.get(16);
    assertThat(handover.evidence())
        .containsEntry("rechecked", true)
        .containsEntry("takeover", true)
        .containsEntry("continuation", false);
    for (int frame = 16; frame < 32; frame++) {
      MouseVideoCursorTracker.Detection detection = detections.get(frame);
      assertThat(detection.visible()).as("cursor at frame %d", frame).isTrue();
      assertThat(Math.abs(detection.x() - truth.get(frame)[0]))
          .as("frame %d x", frame)
          .isLessThanOrEqualTo(2);
      assertThat(Math.abs(detection.y() - truth.get(frame)[1]))
          .as("frame %d y", frame)
          .isLessThanOrEqualTo(2);
    }
  }

  @Test
  void aRecheckDoesNotMoveARestingCursorToAWeakerPlacement() {
    // A resting cursor and a moving look-alike that scores below it (but would pass the minimum):
    // the re-check runs but must keep the resting track, and never turn it into a miss.
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(arrow(), new MouseVideoProperties());
    tracker.setSampleIntervalSeconds(0.25d);
    int[][] background = scene(43, -1, -1);
    List<int[][]> frames = new ArrayList<>();
    for (int frame = 0; frame < 32; frame++) {
      int[][] image = copy(background);
      int x = frame < 8 ? 300 + frame * 15 : 405;
      drawArrow(image, x, 260);
      if (frame >= 9) {
        drawMark(image, 120 + (frame - 9) * 10, 60);
      }
      frames.add(downscale(image));
    }
    List<MouseVideoCursorTracker.Context> calibration = new ArrayList<>();
    for (int frame = 0; frame < 8; frame++) {
      calibration.add(alone(frames.get(frame)));
    }
    tracker.calibrate(calibration, SOURCE_WIDTH, SOURCE_HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT);

    boolean rechecked = false;
    for (int frame = 0; frame < 32; frame++) {
      MouseVideoCursorTracker.Detection detection = tracker.track(context(frames, frame));
      rechecked |= Boolean.TRUE.equals(detection.evidence().get("rechecked"));
      assertThat(detection.visible()).as("frame %d", frame).isTrue();
      assertThat(detection.evidence()).as("frame %d", frame).containsEntry("takeover", false);
      assertThat(Math.abs(detection.x() - (frame < 8 ? 300 + frame * 15 : 405)))
          .as("frame %d x", frame)
          .isLessThanOrEqualTo(2);
    }
    assertThat(rechecked).isTrue();
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

  /** The frame with its samples 1.5 s before and after (4 samples at the test cadence). */
  private static MouseVideoCursorTracker.Context context(List<int[][]> frames, int index) {
    List<int[][]> neighbors = new ArrayList<>();
    if (index >= 4) {
      neighbors.add(frames.get(index - 4));
    }
    if (index + 4 < frames.size()) {
      neighbors.add(frames.get(index + 4));
    }
    return new MouseVideoCursorTracker.Context(
        frames.get(index), neighbors, index > 0 ? frames.get(index - 1) : null);
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

  /**
   * A cursor-like interface mark: the arrow outline with a dimmed, striped fill, so it matches the
   * template above the minimum score but clearly below a real cursor.
   */
  private static void drawMark(int[][] image, int x, int y) {
    for (int row = 0; row < ARROW.length; row++) {
      for (int column = 0; column < ARROW[row].length(); column++) {
        char cell = ARROW[row].charAt(column);
        if (cell == '.') {
          continue;
        }
        image[y + row][x + column] = cell == 'B' ? 0 : row % 3 == 0 ? 110 : 235;
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

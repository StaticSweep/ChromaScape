package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Deterministic, bounded visual producer over sampled source frames.
 *
 * <p>Every observation keeps its exact source ordinal/PTS and a confidence, and every unsupported
 * capability is reported as an explicit unknown rather than invented. The producer never claims a
 * physical click, a native tick, world coordinates, polling rate or hidden cursor position. Cursor
 * tracking requires a reviewed template pack; without one the analysis reports that limitation.
 */
@Component
public class MouseVideoVisualAnalyzer {

  public static final String OBSERVATIONS_SCHEMA = "observations/v1";
  public static final String QUALITY_SCHEMA = "quality/v1";
  public static final String ALGORITHM_ID = "visual/v1";

  /**
   * Absolute frame-difference cut, plus a moderate isolated spike: at least {@code cutSpikeRatio}
   * times every other sampled pair difference within {@link #CUT_WINDOW} samples. A window rather
   * than the two adjacent pairs keeps duplicated-frame stutter during camera motion from looking
   * like a run of spikes.
   */
  static final String CUT_METHOD = "scene-diff/v3";

  static final int CUT_WINDOW = 5;

  private static final int GRID_COLUMNS = 12;
  private static final int GRID_ROWS = 8;

  private final MouseVideoProperties properties;

  public MouseVideoVisualAnalyzer(MouseVideoProperties properties) {
    this.properties = properties;
  }

  /** One sampled frame plus the source ordinal it was decoded from. */
  public record Sample(long ordinal, MouseVideoFrameIndexService.Frame frame) {}

  /** The produced observations and quality documents plus a truthful cursor capability flag. */
  public record Result(
      List<Map<String, Object>> observations,
      Map<String, Object> quality,
      int sampledFrames,
      int step,
      boolean cursorTemplateUsed) {}

  /**
   * Analyzes the already-extracted grayscale PNG sequence. File names are emitted by ffmpeg as
   * {@code %06d.png}; sample {@code i} maps to source ordinal {@code i * step}.
   */
  public Result analyze(
      List<MouseVideoFrameIndexService.Frame> index,
      Path framesDirectory,
      int step,
      int sourceWidth,
      int sourceHeight,
      double sourceDurationSeconds,
      Path cursorTemplate)
      throws IOException {
    List<Sample> samples = samples(index, framesDirectory, step);
    List<Map<String, Object>> observations = new ArrayList<>();
    int[][] previous = null;
    double previousBrightness = 0.0d;
    long pauseStart = -1L;
    double pauseStartSeconds = 0.0d;
    double pauseAccumulated = 0.0d;
    boolean pauseEmitted = false;
    List<Map<String, Object>> gaps = new ArrayList<>();
    long previousOrdinal = -1L;
    int cutCandidates = 0;
    int activityCandidates = 0;
    int indicatorCandidates = 0;
    int cursorVisible = 0;
    int cursorAmbiguous = 0;

    BufferedImage template = loadTemplate(cursorTemplate);
    boolean cursorAvailable = template != null;
    MouseVideoCursorTracker tracker = null;
    Map<String, Object> calibration = null;
    int neighborOffset = neighborOffset(samples);
    if (cursorAvailable) {
      tracker = new MouseVideoCursorTracker(template, properties);
      calibration =
          calibrate(
              tracker, framesDirectory, samples.size(), neighborOffset, sourceWidth, sourceHeight);
    }

    double[] pairDeltas = pairDeltas(framesDirectory, samples.size());
    // Where each sample's forward cursor observation sits in the list, and whether it is visible,
    // so the backward pass can upgrade a forward miss in place without reordering observations.
    int[] cursorSlots = new int[samples.size()];
    boolean[] forwardVisible = new boolean[samples.size()];
    Arrays.fill(cursorSlots, -1);
    for (int position = 0; position < samples.size(); position++) {
      Sample sample = samples.get(position);
      int[][] current = loadSample(framesDirectory, position);
      if (current == null) {
        gaps.add(gap(sample.ordinal(), "decoded frame could not be read"));
        resetPause(
            observations, index, pauseStart, pauseStartSeconds, pauseAccumulated, pauseEmitted);
        pauseStart = -1L;
        pauseAccumulated = 0.0d;
        pauseEmitted = false;
        previous = null;
        previousOrdinal = sample.ordinal();
        continue;
      }
      if (previousOrdinal >= 0 && sample.ordinal() - previousOrdinal > step) {
        gaps.add(
            gap(
                previousOrdinal,
                "sampling gap: " + (sample.ordinal() - previousOrdinal) + " source frames"));
        resetPause(
            observations, index, pauseStart, pauseStartSeconds, pauseAccumulated, pauseEmitted);
        pauseStart = -1L;
        pauseAccumulated = 0.0d;
        pauseEmitted = false;
      }
      if (previous != null
          && previous.length == current.length
          && previous[0].length == current[0].length) {
        double delta = meanDelta(previous, current);
        double seconds = seconds(sample.frame());
        double previousSeconds =
            seconds(index.get((int) Math.min(previousOrdinal, index.size() - 1)));
        double intervalSeconds = Math.max(0.0d, seconds - previousSeconds);
        double brightness = mean(current);
        double localMax = localMaximum(pairDeltas, position);
        boolean spike =
            delta >= properties.getCutSpikeMinDelta()
                && delta >= properties.getCutSpikeRatio() * localMax;
        if (delta >= properties.getCutMinDelta() || spike) {
          cutCandidates++;
          Map<String, Object> evidence = new LinkedHashMap<>();
          evidence.put("beforeFrameOrdinal", Math.max(0L, sample.ordinal() - 1));
          evidence.put("afterFrameOrdinal", sample.ordinal());
          evidence.put("samplingStep", step);
          evidence.put("meanDelta", round(delta));
          evidence.put("windowMaxDelta", round(localMax));
          evidence.put("window", CUT_WINDOW);
          evidence.put("rule", delta >= properties.getCutMinDelta() ? "absolute" : "spike");
          observations.add(
              observation(
                  "SCENE_CUT",
                  sample.ordinal(),
                  sample.frame(),
                  "VISIBLE",
                  clamp(1.0d - properties.getCutMinDelta() + delta, 0.0d, 1.0d),
                  evidence,
                  null,
                  CUT_METHOD));
          resetPause(
              observations, index, pauseStart, pauseStartSeconds, pauseAccumulated, pauseEmitted);
          pauseStart = -1L;
          pauseAccumulated = 0.0d;
          pauseEmitted = false;
        } else {
          if (delta <= properties.getPauseMaxDelta()) {
            if (pauseStart < 0L) {
              pauseStart = previousOrdinal;
              pauseStartSeconds = previousSeconds;
              pauseAccumulated = 0.0d;
              pauseEmitted = false;
            }
            pauseAccumulated += intervalSeconds;
            if (!pauseEmitted
                && accumulatedMillis(pauseAccumulated) >= properties.getPauseMinMillis()) {
              pauseEmitted = true;
              emitPause(
                  observations,
                  index,
                  pauseStart,
                  pauseStartSeconds,
                  pauseAccumulated,
                  sample.ordinal());
            }
          } else {
            resetPause(
                observations, index, pauseStart, pauseStartSeconds, pauseAccumulated, pauseEmitted);
            pauseStart = -1L;
            pauseAccumulated = 0.0d;
            pauseEmitted = false;
          }
          if (delta >= properties.getActivityMinDelta()) {
            activityCandidates++;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("meanDelta", round(delta));
            evidence.put("samplingStep", step);
            observations.add(
                observation(
                    "CAMERA_ACTIVITY",
                    sample.ordinal(),
                    sample.frame(),
                    "VISIBLE",
                    clamp(delta, 0.0d, 1.0d),
                    evidence,
                    regionEvidence(previous, current, sample, sourceWidth, sourceHeight),
                    "regional-motion/v1"));
            Map<String, Object> indicator =
                indicator(
                    previous,
                    current,
                    sample,
                    previousBrightness,
                    brightness,
                    sourceWidth,
                    sourceHeight);
            if (indicator != null) {
              indicatorCandidates++;
              observations.add(indicator);
            }
          }
        }
        previousBrightness = brightness;
      }
      if (cursorAvailable) {
        Map<String, Object> cursor =
            cursor(
                tracker,
                tracker.track(
                    new MouseVideoCursorTracker.Context(
                        current,
                        neighbors(framesDirectory, position, neighborOffset, samples.size()),
                        previous)),
                sample,
                "forward");
        cursorSlots[position] = observations.size();
        observations.add(cursor);
        if ("VISIBLE".equals(cursor.get("visibility"))) {
          forwardVisible[position] = true;
          cursorVisible++;
        } else {
          cursorAmbiguous++;
        }
      }
      previous = current;
      previousOrdinal = sample.ordinal();
    }
    if (!pauseEmitted) {
      emitPauseIfLongEnough(observations, index, pauseStart, pauseStartSeconds, pauseAccumulated);
    }
    if (cursorAvailable && tracker.calibrated()) {
      int recovered =
          backwardPass(
              tracker,
              framesDirectory,
              samples,
              neighborOffset,
              cursorSlots,
              forwardVisible,
              observations);
      cursorVisible += recovered;
      cursorAmbiguous -= recovered;
    }

    if (!cursorAvailable) {
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("method", MouseVideoCursorTracker.METHOD);
      observations.add(
          observation(
              "CURSOR_VISIBILITY",
              0L,
              index.isEmpty() ? null : index.get(0),
              "UNSUPPORTED",
              1.0d,
              evidence,
              null,
              MouseVideoCursorTracker.METHOD,
              "No reviewed cursor template pack is configured; cursor position is unknown"));
    }
    observations.add(
        observation(
            "TARGET_REGION",
            0L,
            index.isEmpty() ? null : index.get(0),
            "UNSUPPORTED",
            1.0d,
            Map.of("method", "target-region/v1"),
            null,
            "target-region/v1",
            "No reviewed target detector is configured; game entity and world coordinates are"
                + " unknown"));

    Map<String, Object> quality =
        quality(
            index,
            samples,
            step,
            sourceWidth,
            sourceHeight,
            sourceDurationSeconds,
            cursorAvailable,
            calibration,
            cursorVisible,
            cursorAmbiguous,
            cutCandidates,
            activityCandidates,
            indicatorCandidates,
            gaps);
    return new Result(List.copyOf(observations), quality, samples.size(), step, cursorAvailable);
  }

  private List<Sample> samples(
      List<MouseVideoFrameIndexService.Frame> index, Path directory, int step) throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    long extracted;
    try (var files = Files.list(directory)) {
      extracted = files.filter(path -> path.getFileName().toString().endsWith(".png")).count();
    }
    int bounded = (int) Math.min(extracted, Math.max(0, properties.getMaxVisualFrames()));
    List<Sample> samples = new ArrayList<>(bounded);
    for (int position = 0; position < bounded; position++) {
      long ordinal = (long) position * step;
      if (ordinal >= index.size()) {
        break;
      }
      samples.add(new Sample(ordinal, index.get((int) ordinal)));
    }
    return samples;
  }

  /**
   * Calibrates the displayed cursor scale from up to {@link
   * MouseVideoCursorTracker#calibrationSamples()} evenly spaced extracted samples.
   */
  private static Map<String, Object> calibrate(
      MouseVideoCursorTracker tracker,
      Path framesDirectory,
      int sampleCount,
      int neighborOffset,
      int sourceWidth,
      int sourceHeight)
      throws IOException {
    List<MouseVideoCursorTracker.Context> frames = new ArrayList<>();
    int wanted = Math.min(sampleCount, MouseVideoCursorTracker.calibrationSamples());
    int width = -1;
    int height = -1;
    for (int position = 0; position < wanted; position++) {
      int sample = (int) ((long) position * sampleCount / Math.max(1, wanted));
      int[][] frame = loadSample(framesDirectory, sample);
      if (frame == null) {
        continue;
      }
      if (width < 0) {
        width = frame[0].length;
        height = frame.length;
      }
      if (frame.length == height && frame[0].length == width) {
        frames.add(
            new MouseVideoCursorTracker.Context(
                frame, neighbors(framesDirectory, sample, neighborOffset, sampleCount), null));
      }
    }
    if (frames.isEmpty()) {
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("method", MouseVideoCursorTracker.METHOD);
      evidence.put("calibrated", false);
      return evidence;
    }
    return tracker.calibrate(frames, sourceWidth, sourceHeight, width, height);
  }

  /**
   * Mean difference of each sample to the one before it ({@code NaN} when either is unreadable or
   * the sizes differ), decoding each sample once.
   */
  private static double[] pairDeltas(Path framesDirectory, int sampleCount) throws IOException {
    double[] deltas = new double[sampleCount];
    int[][] before = null;
    for (int position = 0; position < sampleCount; position++) {
      int[][] frame = loadSample(framesDirectory, position);
      deltas[position] =
          before != null
                  && frame != null
                  && before.length == frame.length
                  && before[0].length == frame[0].length
              ? meanDelta(before, frame)
              : Double.NaN;
      before = frame;
    }
    return deltas;
  }

  private static double localMaximum(double[] deltas, int position) {
    double max = 0.0d;
    int from = Math.max(0, position - CUT_WINDOW);
    int to = Math.min(deltas.length - 1, position + CUT_WINDOW);
    for (int other = from; other <= to; other++) {
      if (other != position && !Double.isNaN(deltas[other])) {
        max = Math.max(max, deltas[other]);
      }
    }
    return max;
  }

  /** Samples about {@link MouseVideoCursorTracker#NEIGHBOR_SECONDS} apart, at least one. */
  private static int neighborOffset(List<Sample> samples) {
    if (samples.size() < 2) {
      return 1;
    }
    double span =
        seconds(samples.get(samples.size() - 1).frame()) - seconds(samples.get(0).frame());
    double interval = span / (samples.size() - 1);
    if (!(interval > 0.0d)) {
      return 1;
    }
    return (int) Math.max(1L, Math.round(MouseVideoCursorTracker.NEIGHBOR_SECONDS / interval));
  }

  private static List<int[][]> neighbors(
      Path framesDirectory, int position, int offset, int sampleCount) throws IOException {
    List<int[][]> neighbors = new ArrayList<>(2);
    for (int candidate : new int[] {position - offset, position + offset}) {
      if (candidate >= 0 && candidate < sampleCount) {
        int[][] frame = loadSample(framesDirectory, candidate);
        if (frame != null) {
          neighbors.add(frame);
        }
      }
    }
    return neighbors;
  }

  private static int[][] loadSample(Path framesDirectory, int position) throws IOException {
    return loadGrayscale(
        framesDirectory.resolve(String.format(Locale.ROOT, "%06d.png", position + 1)));
  }

  /**
   * Tracks the samples again from last to first and upgrades a forward miss to the backward
   * detection when that detection continues a backward track (see {@link MouseVideoCursorTracker}).
   * In this direction the sample after a position plays the role of the preceding sample. Returns
   * how many samples were upgraded.
   */
  private int backwardPass(
      MouseVideoCursorTracker tracker,
      Path framesDirectory,
      List<Sample> samples,
      int neighborOffset,
      int[] cursorSlots,
      boolean[] forwardVisible,
      List<Map<String, Object>> observations)
      throws IOException {
    tracker.restartTrack();
    int recovered = 0;
    int[][] later = null;
    for (int position = samples.size() - 1; position >= 0; position--) {
      int[][] current = loadSample(framesDirectory, position);
      if (current == null) {
        tracker.restartTrack();
        later = null;
        continue;
      }
      MouseVideoCursorTracker.Detection detection =
          tracker.track(
              new MouseVideoCursorTracker.Context(
                  current,
                  neighbors(framesDirectory, position, neighborOffset, samples.size()),
                  later));
      later = current;
      if (forwardVisible[position]
          || cursorSlots[position] < 0
          || !detection.visible()
          || !Boolean.TRUE.equals(detection.evidence().get("continuation"))) {
        continue;
      }
      Map<String, Object> forward = observations.get(cursorSlots[position]);
      Map<String, Object> upgraded = cursor(tracker, detection, samples.get(position), "backward");
      @SuppressWarnings("unchecked")
      Map<String, Object> forwardEvidence = (Map<String, Object>) forward.get("evidence");
      if (forwardEvidence != null) {
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) upgraded.get("evidence");
        evidence.put("forwardReason", forwardEvidence.get("reason"));
      }
      observations.set(cursorSlots[position], upgraded);
      recovered++;
    }
    return recovered;
  }

  private Map<String, Object> cursor(
      MouseVideoCursorTracker tracker,
      MouseVideoCursorTracker.Detection detection,
      Sample sample,
      String pass) {
    if (!detection.visible()) {
      return observation(
          "CURSOR_VISIBILITY",
          sample.ordinal(),
          sample.frame(),
          "AMBIGUOUS",
          round(clamp(detection.score(), 0.0d, 1.0d)),
          detection.evidence(),
          null,
          MouseVideoCursorTracker.METHOD,
          tracker.calibrated()
              ? "Cursor template did not match above the reviewed threshold"
              : "Cursor scale could not be calibrated from this source");
    }
    Map<String, Object> evidence = new LinkedHashMap<>(detection.evidence());
    evidence.put("pass", pass);
    evidence.put("x", detection.x());
    evidence.put("y", detection.y());
    Map<String, Object> observation =
        observation(
            "CURSOR_VISIBILITY",
            sample.ordinal(),
            sample.frame(),
            "VISIBLE",
            round(clamp(detection.score(), 0.0d, 1.0d)),
            evidence,
            null,
            MouseVideoCursorTracker.METHOD,
            null);
    observation.put("x", detection.x());
    observation.put("y", detection.y());
    return observation;
  }

  private Map<String, Object> indicator(
      int[][] previous,
      int[][] current,
      Sample sample,
      double previousBrightness,
      double brightness,
      int sourceWidth,
      int sourceHeight) {
    int band = Math.max(2, current.length / 8);
    double topDelta = bandDelta(previous, current, 0, band);
    double centreDelta = bandDelta(previous, current, band, current.length - band);
    if (topDelta < Math.max(properties.getActivityMinDelta(), centreDelta * 1.6d)) {
      return null;
    }
    String indicator;
    if (brightness - previousBrightness > 0.02d) {
      indicator = "HIGHLIGHT_ON";
    } else if (previousBrightness - brightness > 0.02d) {
      indicator = "HIGHLIGHT_OFF";
    } else {
      indicator = "CURSOR_STATE_CHANGE";
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("topBandDelta", round(topDelta));
    evidence.put("centreDelta", round(centreDelta));
    evidence.put("indicator", indicator);
    evidence.put("visibleOnly", true);
    return observation(
        "ACTION_INDICATOR",
        sample.ordinal(),
        sample.frame(),
        "VISIBLE",
        round(clamp(topDelta, 0.0d, 1.0d)),
        evidence,
        null,
        "ui-change/v1");
  }

  private static Map<String, Object> regionEvidence(
      int[][] previous, int[][] current, Sample sample, int sourceWidth, int sourceHeight) {
    int rows = previous.length;
    int columns = previous[0].length;
    int cellRows = Math.max(1, rows / GRID_ROWS);
    int cellColumns = Math.max(1, columns / GRID_COLUMNS);
    int minX = columns;
    int minY = rows;
    int maxX = -1;
    int maxY = -1;
    for (int cellY = 0; cellY < GRID_ROWS; cellY++) {
      for (int cellX = 0; cellX < GRID_COLUMNS; cellX++) {
        int startX = cellX * cellColumns;
        int startY = cellY * cellRows;
        int endX = Math.min(columns, startX + cellColumns);
        int endY = Math.min(rows, startY + cellRows);
        if (startX >= endX || startY >= endY) {
          continue;
        }
        double delta = regionDelta(previous, current, startX, startY, endX, endY);
        if (delta >= 0.04d) {
          minX = Math.min(minX, startX);
          minY = Math.min(minY, startY);
          maxX = Math.max(maxX, endX);
          maxY = Math.max(maxY, endY);
        }
      }
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    if (maxX <= minX || maxY <= minY) {
      evidence.put("region", "full-frame");
      return evidence;
    }
    double scaleX = sourceWidth / (double) columns;
    double scaleY = sourceHeight / (double) rows;
    evidence.put("x", (int) Math.floor(minX * scaleX));
    evidence.put("y", (int) Math.floor(minY * scaleY));
    evidence.put("width", Math.max(1, (int) Math.ceil((maxX - minX) * scaleX)));
    evidence.put("height", Math.max(1, (int) Math.ceil((maxY - minY) * scaleY)));
    evidence.put("activity", "REGION_CHANGE");
    return evidence;
  }

  private Map<String, Object> quality(
      List<MouseVideoFrameIndexService.Frame> index,
      List<Sample> samples,
      int step,
      int sourceWidth,
      int sourceHeight,
      double durationSeconds,
      boolean cursorAvailable,
      Map<String, Object> cursorCalibration,
      int cursorVisible,
      int cursorAmbiguous,
      int cutCandidates,
      int activityCandidates,
      int indicatorCandidates,
      List<Map<String, Object>> gaps) {
    Map<String, Object> quality = new LinkedHashMap<>();
    quality.put("schema", QUALITY_SCHEMA);
    quality.put("algorithmId", ALGORITHM_ID);
    quality.put("toolchainPin", properties.getVisualToolchainPin());
    Map<String, Object> coverage = new LinkedHashMap<>();
    coverage.put("sourceFrames", index.size());
    coverage.put("sampledFrames", samples.size());
    coverage.put("sampleStep", step);
    coverage.put("sourceDurationSeconds", round(durationSeconds));
    coverage.put("sourceWidth", sourceWidth);
    coverage.put("sourceHeight", sourceHeight);
    coverage.put("samplingGaps", gaps);
    quality.put("coverage", coverage);
    Map<String, Object> methods = new LinkedHashMap<>();
    methods.put("pause", "motion/v1");
    methods.put("cut", CUT_METHOD);
    methods.put("activity", "regional-motion/v1");
    methods.put("indicator", "ui-change/v1");
    methods.put("cursor", MouseVideoCursorTracker.METHOD);
    methods.put("targetRegion", "target-region/v1");
    quality.put("methods", methods);
    Map<String, Object> thresholds = new LinkedHashMap<>();
    thresholds.put("pauseMaxDelta", properties.getPauseMaxDelta());
    thresholds.put("pauseMinMillis", properties.getPauseMinMillis());
    thresholds.put("cutMinDelta", properties.getCutMinDelta());
    thresholds.put("cutSpikeMinDelta", properties.getCutSpikeMinDelta());
    thresholds.put("cutSpikeRatio", properties.getCutSpikeRatio());
    thresholds.put("activityMinDelta", properties.getActivityMinDelta());
    thresholds.put("cursorMinScore", properties.getVisualCursorMinScore());
    thresholds.put("cursorStartScore", properties.getVisualCursorStartScore());
    thresholds.put("cursorMinGain", properties.getVisualCursorMinGain());
    thresholds.put("cursorMaxGain", properties.getVisualCursorMaxGain());
    thresholds.put("cursorMinContrast", properties.getVisualCursorMinContrast());
    thresholds.put("cursorAmbiguityMargin", properties.getVisualCursorAmbiguityMargin());
    thresholds.put("cursorStaticMaxDelta", properties.getVisualCursorStaticMaxDelta());
    quality.put("thresholds", thresholds);
    Map<String, Object> counts = new LinkedHashMap<>();
    counts.put("cutCandidates", cutCandidates);
    counts.put("activityCandidates", activityCandidates);
    counts.put("indicatorCandidates", indicatorCandidates);
    counts.put("cursorVisible", cursorVisible);
    counts.put("cursorAmbiguous", cursorAmbiguous);
    quality.put("counts", counts);
    if (cursorCalibration != null) {
      quality.put("cursorCalibration", cursorCalibration);
    }
    List<String> unknowns = new ArrayList<>();
    if (!cursorAvailable) {
      unknowns.add("cursor: no reviewed cursor template pack is configured");
    } else if (cursorCalibration == null
        || !Boolean.TRUE.equals(cursorCalibration.get("calibrated"))) {
      unknowns.add("cursor: displayed cursor scale could not be calibrated; positions unknown");
    }
    unknowns.add(
        "target-region: no reviewed target detector; game entity/world coordinates unknown");
    unknowns.add("physical-input: never inferred from video");
    unknowns.add("native-ticks: unavailable (SC-2151)");
    unknowns.add("polling-rate: never inferred from video");
    quality.put("unknowns", unknowns);
    return quality;
  }

  private static long accumulatedMillis(double seconds) {
    return Math.round(seconds * 1000.0d);
  }

  private void resetPause(
      List<Map<String, Object>> observations,
      List<MouseVideoFrameIndexService.Frame> index,
      long startOrdinal,
      double startSeconds,
      double accumulatedSeconds,
      boolean alreadyEmitted) {
    if (!alreadyEmitted) {
      emitPauseIfLongEnough(observations, index, startOrdinal, startSeconds, accumulatedSeconds);
    }
  }

  private void emitPauseIfLongEnough(
      List<Map<String, Object>> observations,
      List<MouseVideoFrameIndexService.Frame> index,
      long startOrdinal,
      double startSeconds,
      double accumulatedSeconds) {
    if (startOrdinal < 0L
        || accumulatedMillis(accumulatedSeconds) < properties.getPauseMinMillis()) {
      return;
    }
    emitPause(
        observations,
        index,
        startOrdinal,
        startSeconds,
        accumulatedSeconds,
        startOrdinal + lastOffset(index, startOrdinal, startSeconds, accumulatedSeconds));
  }

  private static long lastOffset(
      List<MouseVideoFrameIndexService.Frame> index,
      long startOrdinal,
      double startSeconds,
      double accumulatedSeconds) {
    double endSeconds = startSeconds + accumulatedSeconds;
    long last = startOrdinal;
    for (int ordinal = (int) startOrdinal; ordinal < index.size(); ordinal++) {
      if (seconds(index.get(ordinal)) > endSeconds + 1e-9d) {
        break;
      }
      last = ordinal;
    }
    return last - startOrdinal;
  }

  private void emitPause(
      List<Map<String, Object>> observations,
      List<MouseVideoFrameIndexService.Frame> index,
      long startOrdinal,
      double startSeconds,
      double accumulatedSeconds,
      long endOrdinal) {
    if (startOrdinal < 0L
        || accumulatedMillis(accumulatedSeconds) < properties.getPauseMinMillis()) {
      return;
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("startFrameOrdinal", startOrdinal);
    evidence.put("endFrameOrdinal", Math.max(startOrdinal, endOrdinal));
    evidence.put("startSeconds", round(startSeconds));
    evidence.put("endSeconds", round(startSeconds + accumulatedSeconds));
    evidence.put("durationSeconds", round(accumulatedSeconds));
    observations.add(
        observation(
            "PAUSE",
            startOrdinal,
            index.isEmpty() ? null : index.get((int) Math.min(startOrdinal, index.size() - 1)),
            "VISIBLE",
            round(
                clamp(
                    accumulatedMillis(accumulatedSeconds)
                        / (double) Math.max(1L, properties.getPauseMinMillis()),
                    0.0d,
                    1.0d)),
            evidence,
            null,
            "motion/v1"));
  }

  private static Map<String, Object> observation(
      String kind,
      long ordinal,
      MouseVideoFrameIndexService.Frame frame,
      String visibility,
      double confidence,
      Map<String, Object> evidence,
      Map<String, Object> region,
      String method) {
    return observation(
        kind, ordinal, frame, visibility, confidence, evidence, region, method, null);
  }

  private static Map<String, Object> observation(
      String kind,
      long ordinal,
      MouseVideoFrameIndexService.Frame frame,
      String visibility,
      double confidence,
      Map<String, Object> evidence,
      Map<String, Object> region,
      String method,
      String unknownReason) {
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("kind", kind);
    observation.put("frameOrdinal", ordinal);
    if (frame != null) {
      if (frame.pts() != null) {
        observation.put("pts", frame.pts());
      }
      if (frame.ptsTime() != null) {
        observation.put("ptsTime", frame.ptsTime());
      }
    }
    observation.put("visibility", visibility);
    observation.put("confidence", confidence);
    observation.put("method", method);
    observation.put("methodVersion", "1");
    observation.put("evidence", evidence == null ? Map.of() : evidence);
    if (region != null) {
      region.forEach(observation::putIfAbsent);
    }
    if (unknownReason != null) {
      observation.put("unknownReason", unknownReason);
    }
    return observation;
  }

  private static Map<String, Object> gap(long ordinal, String reason) {
    Map<String, Object> gap = new LinkedHashMap<>();
    gap.put("afterFrameOrdinal", ordinal);
    gap.put("reason", reason);
    return gap;
  }

  private static double seconds(MouseVideoFrameIndexService.Frame frame) {
    if (frame == null) {
      return 0.0d;
    }
    if (frame.ptsTime() != null) {
      try {
        return new BigDecimal(frame.ptsTime()).doubleValue();
      } catch (NumberFormatException ignored) {
        // fall through
      }
    }
    return 0.0d;
  }

  private static int[][] loadGrayscale(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return null;
    }
    BufferedImage image = ImageIO.read(file.toFile());
    return image == null ? null : grayscale(image);
  }

  private static int[][] grayscale(BufferedImage image) {
    int[][] values = new int[image.getHeight()][image.getWidth()];
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int rgb = image.getRGB(x, y);
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        values[y][x] = (red * 299 + green * 587 + blue * 114) / 1000;
      }
    }
    return values;
  }

  private static BufferedImage loadTemplate(Path template) {
    if (template == null || !Files.isRegularFile(template)) {
      return null;
    }
    try {
      return ImageIO.read(template.toFile());
    } catch (IOException unreadable) {
      return null;
    }
  }

  private static double meanDelta(int[][] previous, int[][] current) {
    long total = 0L;
    long count = 0L;
    for (int y = 0; y < previous.length; y++) {
      for (int x = 0; x < previous[y].length; x++) {
        total += Math.abs(previous[y][x] - current[y][x]);
        count++;
      }
    }
    return count == 0L ? 0.0d : total / (count * 255.0d);
  }

  private static double regionDelta(
      int[][] previous, int[][] current, int startX, int startY, int endX, int endY) {
    long total = 0L;
    long count = 0L;
    for (int y = startY; y < endY; y++) {
      for (int x = startX; x < endX; x++) {
        total += Math.abs(previous[y][x] - current[y][x]);
        count++;
      }
    }
    return count == 0L ? 0.0d : total / (count * 255.0d);
  }

  private static double bandDelta(int[][] previous, int[][] current, int startY, int endY) {
    return regionDelta(previous, current, 0, startY, previous[0].length, endY);
  }

  private static double mean(int[][] image) {
    long total = 0L;
    long count = 0L;
    for (int[] row : image) {
      for (int value : row) {
        total += value;
        count++;
      }
    }
    return count == 0L ? 0.0d : total / (count * 255.0d);
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private static double round(double value) {
    return Math.round(value * 10_000.0d) / 10_000.0d;
  }
}

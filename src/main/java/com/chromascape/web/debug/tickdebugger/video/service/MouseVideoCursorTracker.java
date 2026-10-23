package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cursor-template/v5}: locates one reviewed cursor template in downscaled grayscale samples.
 *
 * <p>The template is rendered at the scale it appears in the analysis frame, with sub-pixel phase,
 * by exact area coverage of its opaque pixels. Only analysis pixels the sprite fully covers are
 * compared, so transparent template pixels and the unknown background never enter the score. The
 * score is zero-mean normalized cross-correlation, which tolerates the luma range and gain changes
 * introduced by encoding; a gain bound and minimum patch contrast reject flat look-alikes.
 *
 * <p>Static interface art can resemble a cursor as closely as the cursor itself, so each placement
 * is compared with the samples about {@link #NEIGHBOR_SECONDS} before and after it, within each
 * difference map's own noise floor. Unchanged versus every neighbour is background unless it is
 * exactly where the cursor was detected in the previous sample and also unchanged since that sample
 * (a cursor that stopped; a cursor that vanished changes those pixels and so ends the hold).
 * Unchanged versus only one neighbour is a cursor arriving or leaving, or art on the far side of a
 * scene cut, so it is accepted only near the previous detection and only if it changed since the
 * previous sample or is that held detection. Changed versus all neighbours is foreground.
 *
 * <p>Tracking uses hysteresis: a detection that does not continue the previous sample's detection
 * (within the arrival radius) must reach the stricter start score, while a continuation needs only
 * the minimum score. Changing art that resembles the cursor therefore cannot start a track on one
 * marginal match.
 *
 * <p>The displayed cursor size is calibrated once per analysis from evenly spaced samples by the
 * summed Fisher z of each sample's best confident match, which weighs correlation by the number of
 * compared pixels: a smaller-than-true sprite correlates as well on fewer pixels, a larger one
 * correlates worse. Calibration that settles on the smallest renderable scale is rejected, because
 * without a smaller scale to bracket it the true cursor may be too small to render, and every frame
 * is reported ambiguous instead of tracked at a wrong size.
 *
 * <p>The analyzer runs the tracker twice over the same samples: forward, then backward from the
 * last sample with the track restarted ({@link #restartTrack()}). A sample the forward pass missed
 * takes the backward detection only when that detection continues a backward track, so it needs the
 * minimum score exactly as a forward continuation does; this recovers the start of a track, which
 * forward needed the stricter start score to reach, and bridges short gaps from their far side. A
 * forward detection is never replaced.
 *
 * <p>Every frame is searched at the calibrated scale at phase zero over every pixel (only near the
 * previous detection while a track exists, falling back to the whole frame when nothing there
 * reaches the minimum score) and then at every sub-pixel phase around each peak. A summed-area
 * bound on each change map skips placements that cannot have changed versus any neighbour. A frame
 * whose best match is below threshold, or whose runner-up at a distinct location scores almost as
 * well, is ambiguous rather than guessed. Detection never implies a click or a physical input
 * event.
 */
final class MouseVideoCursorTracker {
  static final String METHOD = "cursor-template/v5";

  /** Offset of the temporal neighbours used to recognise static look-alikes. */
  static final double NEIGHBOR_SECONDS = 1.5d;

  /** Ratio between adjacent calibration scales (2^(1/8)). */
  private static final double SCALE_STEP = Math.pow(2.0d, 0.125d);

  /** Displayed sprite pixel size relative to source pixels, searched during calibration. */
  private static final double[] SOURCE_SCALES = scales(0.5d, 2.83d);

  private static final int PHASES = 4;
  private static final int MIN_MASK_PIXELS = 12;
  private static final double FULL_COVERAGE = 0.95d;
  private static final double MIN_IN_BOUNDS_FRACTION = 0.6d;
  private static final int CALIBRATION_SAMPLES = 24;
  private static final int CANDIDATES = 6;
  private static final double MAX_NOISE_ALLOWANCE = 10.0d;

  /** Largest move, as a fraction of analysis width, that still links two detections. */
  private static final double ARRIVAL_RADIUS = 0.12d;

  /** Fine calibration steps either side of the chosen coarse scale, each 2^(1/32). */
  private static final int FINE_STEPS = 3;

  /** Consecutive missed samples after which a track is forgotten. */
  private static final int MAX_GAP_SAMPLES = 8;

  private final int templateWidth;
  private final int templateHeight;
  private final double[] templateGray;
  private final double[] templateAlpha;
  private final int hotspotX;
  private final int hotspotY;
  private final double minScore;
  private final double startScore;
  private final double minGain;
  private final double maxGain;
  private final double minContrast;
  private final double ambiguityMargin;
  private final double staticMaxDelta;

  private int analysisWidth;
  private int analysisHeight;
  private double factorX;
  private double factorY;
  private double scale = Double.NaN;
  private Candidate last;
  private int missed;
  private final Map<Double, Patch[]> patches = new LinkedHashMap<>();

  /**
   * One sample, the samples about {@link #NEIGHBOR_SECONDS} before/after it, and the immediately
   * preceding sample ({@code null} when unavailable).
   */
  record Context(int[][] frame, List<int[][]> neighbors, int[][] previous) {}

  /** Result of tracking one frame; coordinates are source pixels of the hotspot pixel. */
  record Detection(boolean visible, double score, int x, int y, Map<String, Object> evidence) {}

  /**
   * One sample flattened row-major, with per-neighbour absolute change maps, their summed-area
   * tables and stationary thresholds, and the change since the preceding sample.
   */
  private record Frame(
      int[] luma,
      int width,
      int height,
      int[][] deltas,
      long[][] integrals,
      double[] thresholds,
      int[] previous,
      double previousThreshold) {}

  /**
   * One rendered sprite at one scale and phase: mask offsets, their row-major offsets at the
   * analysis width, and zero-mean expected luma.
   */
  private record Patch(
      double sourceScale,
      double phaseX,
      double phaseY,
      int[] dx,
      int[] dy,
      int[] offsets,
      double[] value,
      double sumSquares,
      int spanX,
      int spanY) {}

  /** One placement of a patch: its corner in analysis pixels and its match statistics. */
  private record Candidate(int x, int y, Patch patch, double score, double gain) {}

  /** Per-scale calibration totals over the unambiguous confident best matches. */
  private record Evidence(int[] confident, double[] total, double[] fisherZ) {
    int best() {
      int chosen = -1;
      for (int index = 0; index < fisherZ.length; index++) {
        if (confident[index] > 0 && (chosen < 0 || fisherZ[index] > fisherZ[chosen])) {
          chosen = index;
        }
      }
      return chosen;
    }
  }

  MouseVideoCursorTracker(BufferedImage template, MouseVideoProperties properties) {
    this.templateWidth = template.getWidth();
    this.templateHeight = template.getHeight();
    this.templateGray = new double[templateWidth * templateHeight];
    this.templateAlpha = new double[templateWidth * templateHeight];
    boolean hasAlpha = template.getColorModel().hasAlpha();
    for (int y = 0; y < templateHeight; y++) {
      for (int x = 0; x < templateWidth; x++) {
        int argb = template.getRGB(x, y);
        int red = (argb >> 16) & 0xff;
        int green = (argb >> 8) & 0xff;
        int blue = argb & 0xff;
        templateGray[y * templateWidth + x] = (red * 299 + green * 587 + blue * 114) / 1000.0d;
        templateAlpha[y * templateWidth + x] = hasAlpha ? ((argb >>> 24) & 0xff) / 255.0d : 1.0d;
      }
    }
    this.hotspotX = properties.getVisualCursorHotspotX();
    this.hotspotY = properties.getVisualCursorHotspotY();
    this.minScore = properties.getVisualCursorMinScore();
    this.startScore = Math.max(minScore, properties.getVisualCursorStartScore());
    this.minGain = properties.getVisualCursorMinGain();
    this.maxGain = properties.getVisualCursorMaxGain();
    this.minContrast = properties.getVisualCursorMinContrast();
    this.ambiguityMargin = properties.getVisualCursorAmbiguityMargin();
    this.staticMaxDelta = properties.getVisualCursorStaticMaxDelta();
  }

  static int calibrationSamples() {
    return CALIBRATION_SAMPLES;
  }

  /**
   * Whether a cursor displayed at {@code sourceScale} source pixels per template pixel, in an
   * analysis frame {@code analysisFactor} times the source width, is resolvable: at every sub-pixel
   * phase, it and the next smaller calibration scale both render enough fully covered pixels, which
   * is exactly what calibration requires. This is the geometric supported-case rule, independent of
   * any frame content.
   */
  static boolean resolvable(BufferedImage template, double sourceScale, double analysisFactor) {
    MouseVideoCursorTracker tracker =
        new MouseVideoCursorTracker(template, new MouseVideoProperties());
    tracker.factorX = analysisFactor;
    tracker.factorY = analysisFactor;
    tracker.analysisWidth = 1;
    return tracker.renderAllPhases(sourceScale)
        && tracker.renderAllPhases(sourceScale / SCALE_STEP);
  }

  private boolean renderAllPhases(double sourceScale) {
    for (int py = 0; py < PHASES; py++) {
      for (int px = 0; px < PHASES; px++) {
        if (render(sourceScale, px / (double) PHASES, py / (double) PHASES) == null) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Fixes the analysis geometry and the displayed cursor scale. Returns the calibration evidence;
   * when no scale yields a confident foreground match the tracker stays uncalibrated and reports
   * every frame ambiguous.
   */
  Map<String, Object> calibrate(
      List<Context> samples, int sourceWidth, int sourceHeight, int width, int height) {
    this.analysisWidth = width;
    this.analysisHeight = height;
    this.factorX = width / (double) sourceWidth;
    this.factorY = height / (double) sourceHeight;
    this.last = null;
    this.missed = 0;
    this.patches.clear();
    Evidence coarse = evidence(samples, SOURCE_SCALES);
    int chosen = coarse.best();
    boolean bracketed = chosen >= 0 && patchSet(SOURCE_SCALES[chosen] / SCALE_STEP) != null;
    double[] fine = new double[2 * FINE_STEPS + 1];
    Evidence refined = null;
    scale = Double.NaN;
    if (chosen >= 0 && bracketed) {
      // The coarse grid can sit several percent off the true size, which costs correlation on
      // every frame; refine between the neighbouring coarse scales with the same criterion.
      for (int step = -FINE_STEPS; step <= FINE_STEPS; step++) {
        fine[step + FINE_STEPS] =
            round(SOURCE_SCALES[chosen] * Math.pow(2.0d, step / (8.0d * (FINE_STEPS + 1))));
      }
      refined = evidence(samples, fine);
      int best = refined.best();
      scale =
          best >= 0 && patchSet(fine[best] / SCALE_STEP) != null
              ? fine[best]
              : SOURCE_SCALES[chosen];
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("method", METHOD);
    evidence.put("calibrationSamples", samples.size());
    evidence.put("calibrated", calibrated());
    evidence.put("tooSmallToResolve", chosen >= 0 && !bracketed);
    evidence.put("sourceScale", calibrated() ? scale : null);
    evidence.put("confidentByScale", counts(coarse));
    evidence.put("meanScoreByScale", means(coarse));
    evidence.put("fisherZByScale", rounded(coarse.fisherZ()));
    if (refined != null) {
      evidence.put("fineScales", rounded(fine));
      evidence.put("fisherZByFineScale", rounded(refined.fisherZ()));
    }
    return evidence;
  }

  /**
   * Summed Fisher z of each sample's best confident match at every scale. A match counts only when
   * no distinct placement scores within the ambiguity margin: a sprite rendered too small matches
   * many unrelated edges about equally well, so its evidence is ambiguous rather than confident.
   */
  private Evidence evidence(List<Context> samples, double[] scales) {
    int[] confident = new int[scales.length];
    double[] total = new double[scales.length];
    double[] fisherZ = new double[scales.length];
    for (Context sample : samples) {
      if (sample.frame().length != analysisHeight || sample.frame()[0].length != analysisWidth) {
        continue;
      }
      Frame frame = frame(sample);
      for (int index = 0; index < scales.length; index++) {
        Patch[] set = patchSet(scales[index]);
        if (set == null) {
          continue;
        }
        List<Candidate> foreground = foreground(frame, set, null);
        if (foreground.isEmpty() || foreground.get(0).score() < minScore) {
          continue;
        }
        Candidate best = foreground.get(0);
        if (foreground.size() > 1 && foreground.get(1).score() >= best.score() - ambiguityMargin) {
          continue;
        }
        confident[index]++;
        total[index] += best.score();
        fisherZ[index] +=
            atanh(Math.min(best.score(), 0.9999d))
                * Math.sqrt(Math.max(1, best.patch().dx().length - 3));
      }
    }
    return new Evidence(confident, total, fisherZ);
  }

  private static List<Integer> counts(Evidence evidence) {
    List<Integer> counts = new ArrayList<>();
    for (int count : evidence.confident()) {
      counts.add(count);
    }
    return counts;
  }

  private static List<Double> means(Evidence evidence) {
    List<Double> means = new ArrayList<>();
    for (int index = 0; index < evidence.confident().length; index++) {
      int count = evidence.confident()[index];
      means.add(count == 0 ? null : round(evidence.total()[index] / count));
    }
    return means;
  }

  private static List<Double> rounded(double[] values) {
    List<Double> result = new ArrayList<>();
    for (double value : values) {
      result.add(round(value));
    }
    return result;
  }

  boolean calibrated() {
    return !Double.isNaN(scale);
  }

  /** Forgets the current track but keeps the calibration, before a pass in the other direction. */
  void restartTrack() {
    last = null;
    missed = 0;
  }

  /** Tracks one sample. Samples must be supplied in source order for the hold and hysteresis. */
  Detection track(Context sample) {
    int[][] luma = sample.frame();
    Map<String, Object> evidence = new LinkedHashMap<>();
    if (!calibrated() || luma.length != analysisHeight || luma[0].length != analysisWidth) {
      evidence.put("reason", !calibrated() ? "uncalibrated" : "frame size changed");
      last = null;
      missed = 0;
      return new Detection(false, 0.0d, 0, 0, evidence);
    }
    Frame frame = frame(sample);
    Patch[] set = patchSet(scale);
    List<Candidate> foreground = List.of();
    boolean windowed = false;
    if (last != null) {
      // While tracking, search only near the previous detection; fall back to the whole frame
      // when nothing there reaches the minimum score, so a jump is still found this sample.
      int radius = (int) Math.ceil(ARRIVAL_RADIUS * analysisWidth);
      foreground =
          foreground(
              frame,
              set,
              last,
              last.x() - radius,
              last.y() - radius,
              last.x() + radius,
              last.y() + radius);
      windowed = !foreground.isEmpty() && foreground.get(0).score() >= minScore;
    }
    if (!windowed) {
      foreground = foreground(frame, set, last, 0, 0, frame.width() - 1, frame.height() - 1);
    }
    evidence.put("windowed", windowed);
    if (foreground.isEmpty()) {
      evidence.put("reason", "no foreground placement");
      miss();
      return new Detection(false, 0.0d, 0, 0, evidence);
    }
    Candidate best = foreground.get(0);
    Candidate runnerUp = foreground.size() > 1 ? foreground.get(1) : null;
    if (last != null && !near(best, last)) {
      // A continuation of the track within the ambiguity margin outranks a distant placement.
      for (Candidate candidate : foreground) {
        if (near(candidate, last) && candidate.score() >= best.score() - ambiguityMargin) {
          runnerUp = best;
          best = candidate;
          break;
        }
      }
    }
    boolean continuation = last != null && near(best, last);
    evidence.put("score", round(best.score()));
    evidence.put("gain", round(best.gain()));
    evidence.put("sourceScale", best.patch().sourceScale());
    evidence.put("phaseX", best.patch().phaseX());
    evidence.put("phaseY", best.patch().phaseY());
    evidence.put("maskPixels", best.patch().dx().length);
    evidence.put("runnerUpScore", runnerUp == null ? null : round(runnerUp.score()));
    evidence.put("continuation", continuation);
    evidence.put("missedSamples", missed);
    // While tracking, a distinct placement away from the track does not make the continuation
    // ambiguous; it would have to win outright.
    boolean ambiguous =
        runnerUp != null
            && runnerUp.score() >= best.score() - ambiguityMargin
            && !(continuation && !near(runnerUp, last));
    double required = continuation ? minScore : startScore;
    if (best.score() < required || ambiguous) {
      evidence.put(
          "reason",
          ambiguous
              ? "competing placement"
              : continuation ? "below reviewed threshold" : "below track start threshold");
      miss();
      return new Detection(false, best.score(), 0, 0, evidence);
    }
    last = best;
    missed = 0;
    double spriteScale = best.patch().sourceScale();
    double cornerX = (best.x() + best.patch().phaseX()) / factorX;
    double cornerY = (best.y() + best.patch().phaseY()) / factorY;
    int x = (int) Math.round(cornerX + spriteScale * (hotspotX + 0.5d) - 0.5d);
    int y = (int) Math.round(cornerY + spriteScale * (hotspotY + 0.5d) - 0.5d);
    return new Detection(true, best.score(), x, y, evidence);
  }

  /** Records a missed sample; the track survives a short run of misses. */
  private void miss() {
    missed++;
    if (missed > MAX_GAP_SAMPLES) {
      last = null;
      missed = 0;
    }
  }

  private boolean near(Candidate candidate, Candidate anchor) {
    return Math.hypot(candidate.x() - anchor.x(), candidate.y() - anchor.y())
        <= ARRIVAL_RADIUS * analysisWidth;
  }

  /**
   * Distinct refined placements, best first, excluding stationary background look-alikes. A
   * stationary placement is kept only when it is where {@code held} was detected in the previous
   * sample, so a cursor that stops is not lost while static art never becomes a detection.
   */
  private List<Candidate> foreground(Frame frame, Patch[] set, Candidate held) {
    return foreground(frame, set, held, 0, 0, frame.width() - 1, frame.height() - 1);
  }

  /** {@link #foreground(Frame, Patch[], Candidate)} restricted to corners in the given box. */
  private List<Candidate> foreground(
      Frame frame, Patch[] set, Candidate held, int x0, int y0, int x1, int y1) {
    List<Candidate> result = new ArrayList<>();
    for (Candidate coarse : top(frame, set[0], held, x0, y0, x1, y1)) {
      result.add(refineAround(frame, set, coarse, held));
    }
    result.sort((a, b) -> Double.compare(b.score(), a.score()));
    // Refinement can pull two coarse peaks onto one location; keep only the stronger one so a
    // single cursor never competes with itself.
    List<Candidate> distinct = new ArrayList<>();
    for (Candidate candidate : result) {
      boolean overlaps = false;
      for (Candidate kept : distinct) {
        if (Math.abs(kept.x() - candidate.x()) < kept.patch().spanX()
            && Math.abs(kept.y() - candidate.y()) < kept.patch().spanY()) {
          overlaps = true;
          break;
        }
      }
      if (!overlaps) {
        distinct.add(candidate);
      }
    }
    return distinct;
  }

  private Frame frame(Context sample) {
    int[][] source = sample.frame();
    int height = source.length;
    int width = source[0].length;
    int[] luma = flatten(source);
    List<int[]> deltas = new ArrayList<>();
    List<long[]> integrals = new ArrayList<>();
    List<Double> thresholds = new ArrayList<>();
    for (int[][] neighbor : sample.neighbors()) {
      if (neighbor.length != height || neighbor[0].length != width) {
        continue;
      }
      int[] delta = new int[width * height];
      int[] histogram = new int[256];
      long[] integral = new long[(width + 1) * (height + 1)];
      for (int y = 0; y < height; y++) {
        int[] row = neighbor[y];
        long running = 0L;
        for (int x = 0; x < width; x++) {
          int value = Math.abs(luma[y * width + x] - row[x]);
          delta[y * width + x] = value;
          histogram[value]++;
          running += value;
          integral[(y + 1) * (width + 1) + x + 1] = integral[y * (width + 1) + x + 1] + running;
        }
      }
      int half = width * height / 2;
      int median = 0;
      for (int seen = 0; median < 255 && seen + histogram[median] <= half; median++) {
        seen += histogram[median];
      }
      deltas.add(delta);
      integrals.add(integral);
      thresholds.add(staticMaxDelta + Math.min(2.0d * median, MAX_NOISE_ALLOWANCE));
    }
    double[] values = new double[thresholds.size()];
    for (int index = 0; index < values.length; index++) {
      values[index] = thresholds.get(index);
    }
    int[][] previous = sample.previous();
    int[] flatPrevious =
        previous != null && previous.length == height && previous[0].length == width
            ? flatten(previous)
            : null;
    return new Frame(
        luma,
        width,
        height,
        deltas.toArray(new int[0][]),
        integrals.toArray(new long[0][]),
        values,
        flatPrevious,
        staticMaxDelta + MAX_NOISE_ALLOWANCE);
  }

  private static int[] flatten(int[][] image) {
    int width = image[0].length;
    int[] flat = new int[width * image.length];
    for (int y = 0; y < image.length; y++) {
      System.arraycopy(image[y], 0, flat, y * width, width);
    }
    return flat;
  }

  private static boolean holds(Candidate held, int x, int y) {
    return held != null && Math.abs(x - held.x()) <= 1 && Math.abs(y - held.y()) <= 1;
  }

  /**
   * Whether a placement unchanged versus {@code unchanged} of {@code neighbors} neighbours is
   * admissible; {@code sincePrevious} is whether it changed since the preceding sample, or {@code
   * null} when that sample is unavailable.
   */
  private boolean admissible(
      int unchanged, int neighbors, Boolean sincePrevious, Candidate held, int x, int y) {
    if (unchanged == 0) {
      return true;
    }
    if (held == null || sincePrevious == null) {
      return false;
    }
    boolean staying = holds(held, x, y) && !sincePrevious;
    if (unchanged == neighbors) {
      return staying;
    }
    double radius = ARRIVAL_RADIUS * analysisWidth;
    return Math.hypot(x - held.x(), y - held.y()) <= radius && (sincePrevious || staying);
  }

  private Candidate refineAround(Frame frame, Patch[] set, Candidate coarse, Candidate held) {
    Candidate refined = coarse;
    for (Patch patch : set) {
      for (int y = coarse.y() - 1; y <= coarse.y() + 1; y++) {
        for (int x = coarse.x() - 1; x <= coarse.x() + 1; x++) {
          Candidate candidate = evaluate(frame, patch, x, y, held);
          if (candidate != null && candidate.score() > refined.score()) {
            refined = candidate;
          }
        }
      }
    }
    return refined;
  }

  /** Up to {@link #CANDIDATES} best phase-0 placements that do not overlap one another. */
  private List<Candidate> top(
      Frame frame, Patch patch, Candidate held, int x0, int y0, int x1, int y1) {
    int spanX = patch.spanX();
    int spanY = patch.spanY();
    List<Candidate> best = new ArrayList<>(CANDIDATES + 1);
    for (int y = Math.max(0, y0); y <= Math.min(frame.height() - 1, y1); y++) {
      for (int x = Math.max(0, x0); x <= Math.min(frame.width() - 1, x1); x++) {
        Candidate candidate = evaluate(frame, patch, x, y, held);
        if (candidate == null) {
          continue;
        }
        if (best.size() == CANDIDATES && candidate.score() <= best.get(best.size() - 1).score()) {
          continue;
        }
        boolean dominated = false;
        for (int index = best.size() - 1; index >= 0; index--) {
          Candidate other = best.get(index);
          if (Math.abs(other.x() - x) < spanX && Math.abs(other.y() - y) < spanY) {
            if (other.score() >= candidate.score()) {
              dominated = true;
              break;
            }
            best.remove(index);
          }
        }
        if (dominated) {
          continue;
        }
        int position = 0;
        while (position < best.size() && best.get(position).score() >= candidate.score()) {
          position++;
        }
        best.add(position, candidate);
        if (best.size() > CANDIDATES) {
          best.remove(best.size() - 1);
        }
      }
    }
    return best;
  }

  /**
   * Match statistics with the patch corner at {@code (x, y)}, or {@code null} if inadmissible: out
   * of bounds, too little of the sprite in frame, too flat, implausible gain, or stationary versus
   * a neighbour without being the held detection.
   */
  private Candidate evaluate(Frame frame, Patch patch, int x, int y, Candidate held) {
    int width = frame.width();
    int height = frame.height();
    if (x < 0 || y < 0 || x >= width || y >= height) {
      return null;
    }
    int count = patch.dx().length;
    boolean inside = x + patch.spanX() <= width && y + patch.spanY() <= height;
    int[][] deltas = frame.deltas();
    if (inside && deltas.length > 0 && !holds(held, x, y)) {
      // The bounding-box change bounds the masked change from above, so a box that barely
      // changed versus every neighbour is certainly stationary.
      int stride = width + 1;
      int x1 = x + patch.spanX();
      int y1 = y + patch.spanY();
      boolean stationary = true;
      for (int index = 0; index < deltas.length && stationary; index++) {
        long[] integral = frame.integrals()[index];
        long box =
            integral[y1 * stride + x1]
                - integral[y * stride + x1]
                - integral[y1 * stride + x]
                + integral[y * stride + x];
        stationary = box <= frame.thresholds()[index] * count;
      }
      if (stationary) {
        return null;
      }
    }
    int[] luma = frame.luma();
    int[] previous = frame.previous();
    double[] value = patch.value();
    long sumI = 0L;
    long sumSquaresI = 0L;
    double sumIt = 0.0d;
    double sumT = 0.0d;
    double sumTt = 0.0d;
    long delta0 = 0L;
    long delta1 = 0L;
    long sumPrevious = 0L;
    int n = 0;
    int base = y * width + x;
    int[] offsets = patch.offsets();
    int[] dx = patch.dx();
    int[] dy = patch.dy();
    for (int k = 0; k < count; k++) {
      int pixel;
      if (inside) {
        pixel = base + offsets[k];
      } else {
        int px = x + dx[k];
        int py = y + dy[k];
        if (px >= width || py >= height) {
          continue;
        }
        pixel = py * width + px;
      }
      int intensity = luma[pixel];
      double t = value[k];
      sumI += intensity;
      sumSquaresI += (long) intensity * intensity;
      sumIt += intensity * t;
      sumT += t;
      sumTt += t * t;
      if (deltas.length > 0) {
        delta0 += deltas[0][pixel];
        if (deltas.length > 1) {
          delta1 += deltas[1][pixel];
        }
      }
      if (previous != null) {
        sumPrevious += Math.abs(intensity - previous[pixel]);
      }
      n++;
    }
    if (n < MIN_MASK_PIXELS || n < Math.ceil(count * MIN_IN_BOUNDS_FRACTION)) {
      return null;
    }
    int unchanged = 0;
    if (deltas.length > 0 && delta0 / (double) n <= frame.thresholds()[0]) {
      unchanged++;
    }
    if (deltas.length > 1 && delta1 / (double) n <= frame.thresholds()[1]) {
      unchanged++;
    }
    Boolean sincePrevious =
        previous == null ? null : sumPrevious / (double) n > frame.previousThreshold();
    if (!admissible(unchanged, deltas.length, sincePrevious, held, x, y)) {
      return null;
    }
    double varianceI = sumSquaresI - (double) sumI * sumI / n;
    double varianceT = inside ? patch.sumSquares() : sumTt - sumT * sumT / n;
    double covariance = inside ? sumIt : sumIt - sumI * sumT / n;
    if (varianceT <= 1.0d || varianceI / n < minContrast * minContrast) {
      return null;
    }
    double gain = covariance / varianceT;
    if (gain < minGain || gain > maxGain) {
      return null;
    }
    return new Candidate(x, y, patch, covariance / Math.sqrt(varianceI * varianceT), gain);
  }

  /** Rendered phases for one scale, or {@code null} when the sprite is too small to compare. */
  private Patch[] patchSet(double sourceScale) {
    return patches.computeIfAbsent(
        sourceScale,
        key -> {
          Patch[] set = new Patch[PHASES * PHASES];
          for (int py = 0; py < PHASES; py++) {
            for (int px = 0; px < PHASES; px++) {
              Patch patch = render(key, px / (double) PHASES, py / (double) PHASES);
              if (patch == null) {
                return null;
              }
              set[py * PHASES + px] = patch;
            }
          }
          return set;
        });
  }

  private Patch render(double sourceScale, double phaseX, double phaseY) {
    double kx = sourceScale * factorX;
    double ky = sourceScale * factorY;
    int width = (int) Math.ceil(phaseX + kx * templateWidth);
    int height = (int) Math.ceil(phaseY + ky * templateHeight);
    List<int[]> cells = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    for (int j = 0; j < height; j++) {
      for (int i = 0; i < width; i++) {
        double coverage = 0.0d;
        double luma = 0.0d;
        int u0 = Math.max(0, (int) Math.floor((i - phaseX) / kx));
        int u1 = Math.min(templateWidth - 1, (int) Math.floor((i + 1 - phaseX) / kx));
        int v0 = Math.max(0, (int) Math.floor((j - phaseY) / ky));
        int v1 = Math.min(templateHeight - 1, (int) Math.floor((j + 1 - phaseY) / ky));
        for (int v = v0; v <= v1; v++) {
          double overlapY = overlap(j, j + 1, phaseY + ky * v, phaseY + ky * (v + 1));
          if (overlapY <= 0.0d) {
            continue;
          }
          for (int u = u0; u <= u1; u++) {
            double overlapX = overlap(i, i + 1, phaseX + kx * u, phaseX + kx * (u + 1));
            if (overlapX <= 0.0d) {
              continue;
            }
            double weight = overlapX * overlapY * templateAlpha[v * templateWidth + u];
            coverage += weight;
            luma += weight * templateGray[v * templateWidth + u];
          }
        }
        if (coverage >= FULL_COVERAGE) {
          cells.add(new int[] {i, j});
          values.add(luma / coverage);
        }
      }
    }
    if (cells.size() < MIN_MASK_PIXELS) {
      return null;
    }
    int[] dx = new int[cells.size()];
    int[] dy = new int[cells.size()];
    int[] offsets = new int[cells.size()];
    double[] value = new double[cells.size()];
    double sum = 0.0d;
    int spanX = 0;
    int spanY = 0;
    for (int index = 0; index < cells.size(); index++) {
      dx[index] = cells.get(index)[0];
      dy[index] = cells.get(index)[1];
      offsets[index] = dy[index] * analysisWidth + dx[index];
      value[index] = values.get(index);
      sum += value[index];
      spanX = Math.max(spanX, dx[index] + 1);
      spanY = Math.max(spanY, dy[index] + 1);
    }
    double mean = sum / value.length;
    double squares = 0.0d;
    for (int index = 0; index < value.length; index++) {
      value[index] -= mean;
      squares += value[index] * value[index];
    }
    if (squares < 1.0d) {
      return null;
    }
    return new Patch(sourceScale, phaseX, phaseY, dx, dy, offsets, value, squares, spanX, spanY);
  }

  private static double[] scales(double from, double to) {
    int count = (int) Math.floor(Math.log(to / from) / Math.log(SCALE_STEP) + 1e-9) + 1;
    double[] values = new double[count];
    for (int index = 0; index < count; index++) {
      values[index] = Math.round(from * Math.pow(SCALE_STEP, index) * 1000.0d) / 1000.0d;
    }
    return values;
  }

  private static double atanh(double value) {
    return 0.5d * Math.log((1.0d + value) / (1.0d - value));
  }

  private static double overlap(double a0, double a1, double b0, double b1) {
    return Math.max(0.0d, Math.min(a1, b1) - Math.max(a0, b0));
  }

  private static double round(double value) {
    return Math.round(value * 10_000.0d) / 10_000.0d;
  }
}

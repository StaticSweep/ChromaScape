package com.chromascape.web.recording.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.bytedeco.javacv.FFmpegFrameGrabber;

/** Finalized physical-frame index, bound to exact media bytes. Contains timings, never pixels. */
public final class MeasuredVideoIndex {
  public record Submission(
      long mediaMicros,
      double capturedEpochMillis,
      double presentedEpochMillis,
      double encodeLagMillis,
      double imageAgeMillis) {}

  public record PhysicalFrame(
      int index,
      long mediaMicros,
      double capturedEpochMillis,
      double presentedEpochMillis,
      double encodeLagMillis,
      double imageAgeMillis) {}

  public record Index(
      String schema,
      String recordingId,
      String mediaSha256,
      boolean finalized,
      List<String> qualityCodes,
      List<PhysicalFrame> frames,
      long mediaDurationMicros,
      @com.fasterxml.jackson.annotation.JsonIgnore List<PhysicalFrame> captureFrames) {
    public Index {
      frames = frames == null ? null : List.copyOf(frames);
      // Presentation order is physical media order. Capture callbacks can arrive out of order,
      // and cadence-fill frames can repeat an older image. Build this lookup once, not per tick.
      captureFrames =
          frames == null
              ? List.of()
              : frames.stream()
                  .sorted(
                      Comparator.comparingDouble(PhysicalFrame::capturedEpochMillis)
                          .thenComparingDouble(PhysicalFrame::imageAgeMillis)
                          .thenComparingInt(PhysicalFrame::index))
                  .toList();
    }

    public Index(
        String schema,
        String recordingId,
        String mediaSha256,
        boolean finalized,
        List<String> qualityCodes,
        List<PhysicalFrame> frames,
        long mediaDurationMicros) {
      this(
          schema,
          recordingId,
          mediaSha256,
          finalized,
          qualityCodes,
          frames,
          mediaDurationMicros,
          null);
    }

    public Index(
        String schema,
        String recordingId,
        String mediaSha256,
        boolean finalized,
        List<String> qualityCodes,
        List<PhysicalFrame> frames) {
      this(
          schema,
          recordingId,
          mediaSha256,
          finalized,
          qualityCodes,
          frames,
          frames == null || frames.isEmpty() ? 0 : frames.get(frames.size() - 1).mediaMicros());
    }
  }

  public record Mapping(
      String status,
      List<String> qualityCodes,
      Integer frameIndex,
      Double mediaTimeMillis,
      Double residualMillis,
      Double uncertaintyMillis,
      Double imageAgeMillis,
      Double encodeLagMillis) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private MeasuredVideoIndex() {}

  public static Index finalizeIndex(
      Path video, String recordingId, List<Submission> submissions, List<String> qualityCodes)
      throws Exception {
    List<PhysicalFrame> physical = new ArrayList<>();
    List<String> codes = new ArrayList<>(qualityCodes);
    long mediaDurationMicros;
    if (submissions.isEmpty()) {
      codes.add("FRAME_TIMING_MISSING");
    }
    try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video.toFile())) {
      grabber.start();
      mediaDurationMicros = grabber.getLengthInTime();
      int cursor = 0;
      while (grabber.grabImage() != null) {
        long media = grabber.getTimestamp();
        if (physical.size() >= 500000) {
          throw new IllegalArgumentException("Frame index exceeds bound");
        }
        while (cursor + 1 < submissions.size()
            && Math.abs(submissions.get(cursor + 1).mediaMicros() - media)
                < Math.abs(submissions.get(cursor).mediaMicros() - media)) {
          cursor++;
        }
        Submission s =
            submissions.isEmpty() ? new Submission(media, 0, 0, 0, 0) : submissions.get(cursor);
        if (Math.abs(s.mediaMicros() - media) > 2000) {
          codes.add("MEDIA_TIMESTAMP_MISMATCH");
        }
        physical.add(
            new PhysicalFrame(
                physical.size(),
                media,
                s.capturedEpochMillis(),
                s.presentedEpochMillis(),
                s.encodeLagMillis(),
                s.imageAgeMillis()));
      }
    }
    if (physical.isEmpty()) {
      codes.add("MEDIA_EMPTY");
    }
    if (physical.size() != submissions.size()) {
      codes.add("PHYSICAL_FRAME_COUNT_MISMATCH");
    }
    Index index =
        new Index(
            "measured-video-index/v1",
            recordingId,
            digest(video),
            true,
            codes.stream().distinct().toList(),
            List.copyOf(physical),
            mediaDurationMicros);
    Path destination = sidecar(video);
    Path pending = destination.resolveSibling(destination.getFileName() + ".pending");
    MAPPER.writeValue(pending.toFile(), index);
    Files.move(
        pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    return index;
  }

  public static Index read(Path video, String recordingId) throws Exception {
    Path file = sidecar(video);
    if (Files.size(file) > 67108864) {
      throw new IllegalArgumentException("Frame index exceeds bound");
    }
    Index index = MAPPER.readValue(file.toFile(), Index.class);
    if (!"measured-video-index/v1".equals(index.schema())
        || !recordingId.equals(index.recordingId())
        || !index.finalized()
        || !digest(video).equals(index.mediaSha256())
        || index.qualityCodes() == null
        || index.frames() == null
        || index.frames().isEmpty()
        || index.frames().size() > 500000
        || index.mediaDurationMicros()
            < index.frames().get(index.frames().size() - 1).mediaMicros()) {
      throw new IllegalArgumentException("Invalid video index binding");
    }
    long previousMedia = -1;
    double previousPresented = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < index.frames().size(); i++) {
      PhysicalFrame f = index.frames().get(i);
      if (f.index() != i
          || f.mediaMicros() <= previousMedia
          || !Double.isFinite(f.capturedEpochMillis())
          || !Double.isFinite(f.presentedEpochMillis())
          || f.presentedEpochMillis() < previousPresented
          || !Double.isFinite(f.encodeLagMillis())
          || f.encodeLagMillis() < 0
          || !Double.isFinite(f.imageAgeMillis())
          || f.imageAgeMillis() < 0) {
        throw new IllegalArgumentException("Invalid video frame order/timing");
      }
      previousMedia = f.mediaMicros();
      previousPresented = f.presentedEpochMillis();
    }
    return index;
  }

  public static Mapping map(Index index, double serverTickMillis, double clockUncertainty) {
    if (index == null || !index.finalized() || index.frames() == null || index.frames().isEmpty()) {
      return unavailable("VIDEO_INDEX_MISSING");
    }
    if (!index.qualityCodes().isEmpty()) {
      return new Mapping("UNAVAILABLE", index.qualityCodes(), null, null, null, null, null, null);
    }
    if (!Double.isFinite(serverTickMillis)
        || !Double.isFinite(clockUncertainty)
        || clockUncertainty < 0
        || clockUncertainty > 50) {
      return unavailable("CLOCK_UNCERTAINTY_EXCEEDED");
    }
    List<PhysicalFrame> frames = index.frames();
    PhysicalFrame last = frames.get(frames.size() - 1);
    double finalPresentationEnd =
        last.presentedEpochMillis()
            + Math.max(0, index.mediaDurationMicros() - last.mediaMicros()) / 1000.0;
    if (serverTickMillis < frames.get(0).presentedEpochMillis()
        || serverTickMillis > finalPresentationEnd) {
      return unavailable("TICK_OUTSIDE_VIDEO_RANGE");
    }
    List<PhysicalFrame> captured = index.captureFrames();
    int low = 0, high = captured.size() - 1;
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (captured.get(mid).capturedEpochMillis() < serverTickMillis) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    int closest = low;
    if (low > 0
        && Math.abs(captured.get(low - 1).capturedEpochMillis() - serverTickMillis)
            <= Math.abs(captured.get(low).capturedEpochMillis() - serverTickMillis)) {
      closest--;
    }
    // lower_bound also selects the original/freshest physical copy of a repeated image.
    double selectedCapture = captured.get(closest).capturedEpochMillis();
    low = 0;
    high = closest;
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (captured.get(mid).capturedEpochMillis() < selectedCapture) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    PhysicalFrame frame = captured.get(low);
    double residual = serverTickMillis - frame.capturedEpochMillis();
    List<String> codes =
        Math.abs(residual) + clockUncertainty > 100
            ? List.of("FRAME_ALIGNMENT_UNCERTAINTY")
            : List.of();
    return new Mapping(
        codes.isEmpty() ? "MEASURED" : "UNAVAILABLE",
        codes,
        frame.index(),
        frame.mediaMicros() / 1000.0,
        residual,
        clockUncertainty,
        frame.imageAgeMillis(),
        frame.encodeLagMillis());
  }

  public static Mapping unavailable(String code) {
    return new Mapping("UNAVAILABLE", List.of(code), null, null, null, null, null, null);
  }

  public static Path sidecar(Path video) {
    return video.resolveSibling(video.getFileName() + ".timing.json");
  }

  private static String digest(Path path) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = in.read(buffer)) >= 0) {
        hash.update(buffer, 0, count);
      }
    }
    return HexFormat.of().formatHex(hash.digest());
  }
}

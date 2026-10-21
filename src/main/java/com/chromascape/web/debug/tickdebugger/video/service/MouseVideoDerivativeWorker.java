package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Bounded derivative worker for SC-2150. Produces a browser-playable proxy, a thumbnail and a
 * bounded original PTS/frame index with the server-owned ffmpeg/ffprobe binaries. All tool choices
 * are configuration, never caller input, and every child process is bounded by an independent
 * wall-clock watchdog, drained concurrently and terminated (with its descendants) on completion,
 * timeout or cancellation.
 */
@Component
public class MouseVideoDerivativeWorker {

  private static final String FRAME_INDEX_SCHEMA = "frame-index/v2";
  private static final int MAX_TOOL_OUTPUT_BYTES = 64 * 1024 * 1024;

  private final MouseVideoProperties properties;
  private final MouseVideoByteStore byteStore;
  private final MouseVideoProcessRunner runner;
  private final AtomicReference<Boolean> ffmpegStatus = new AtomicReference<>();
  private final AtomicReference<String> proxyEncoderStatus = new AtomicReference<>();

  public MouseVideoDerivativeWorker(
      MouseVideoProperties properties,
      MouseVideoByteStore byteStore,
      MouseVideoProcessRunner runner) {
    this.properties = properties;
    this.byteStore = byteStore;
    this.runner = runner;
  }

  /** One published derivative artifact. */
  public record DerivedArtifact(String role, String checksum, long sizeBytes, String mediaType) {}

  /** Bounded derivatives: proxy/thumbnail presence and the indexed frame count. */
  public record Derivatives(
      boolean hasProxy,
      boolean hasThumbnail,
      long indexedFrames,
      List<DerivedArtifact> artifacts) {}

  public boolean ffmpegAvailable() {
    Boolean cached = ffmpegStatus.get();
    if (cached != null) {
      return cached;
    }
    boolean available = binaryAvailable(properties.getFfmpeg());
    ffmpegStatus.set(available);
    return available;
  }

  public Derivatives derive(Path workspace, Path media, MouseVideoCancellation cancellation)
      throws IOException, InterruptedException {
    Files.createDirectories(workspace);
    List<DerivedArtifact> artifacts = new ArrayList<>();
    List<MouseVideoByteStore.StagedArtifact> pending = new ArrayList<>();
    try {
      boolean hasProxy = false;
      boolean hasThumbnail = false;
      long indexedFrames = 0L;
      if (!ffmpegAvailable()
          || properties.getFfmpeg() == null
          || properties.getFfmpeg().isBlank()) {
        return new Derivatives(false, false, 0L, artifacts);
      }
      Path index = deriveFrameIndex(workspace, media, cancellation);
      if (index != null) {
        checkCancelled(cancellation);
        MouseVideoByteStore.StagedArtifact staged =
            byteStore.stage(Files.newInputStream(index), Files.size(index) + 1L);
        pending.add(staged);
        artifacts.add(
            new DerivedArtifact(
                MouseVideoArtifactRef.ROLE_FRAME_INDEX,
                staged.checksum(),
                staged.sizeBytes(),
                "application/json"));
        indexedFrames = countFrames(index);
      }
      Path thumbnail = workspace.resolve("thumbnail.png");
      checkCancelled(cancellation);
      if (runFfmpeg(
          cancellation,
          "-y",
          "-threads",
          "2",
          "-filter_threads",
          "1",
          "-i",
          media.toString(),
          "-frames:v",
          "1",
          "-vf",
          "scale=-2:min(ih\\,360)",
          thumbnail.toString())) {
        MouseVideoByteStore.StagedArtifact staged =
            byteStore.stage(Files.newInputStream(thumbnail), Files.size(thumbnail) + 1L);
        pending.add(staged);
        artifacts.add(
            new DerivedArtifact(
                MouseVideoArtifactRef.ROLE_THUMBNAIL,
                staged.checksum(),
                staged.sizeBytes(),
                "image/png"));
        hasThumbnail = true;
      }
      Path proxy = workspace.resolve("proxy.mp4");
      checkCancelled(cancellation);
      if (runFfmpeg(
          cancellation,
          "-y",
          "-threads",
          "2",
          "-filter_threads",
          "1",
          "-i",
          media.toString(),
          "-vf",
          "scale=-2:" + Math.max(144, properties.getProxyMaxHeight()),
          "-c:v",
          proxyEncoder(cancellation),
          "-preset",
          "veryfast",
          "-crf",
          "26",
          "-pix_fmt",
          "yuv420p",
          "-movflags",
          "+faststart",
          "-an",
          proxy.toString())) {
        MouseVideoByteStore.StagedArtifact staged =
            byteStore.stage(Files.newInputStream(proxy), Files.size(proxy) + 1L);
        pending.add(staged);
        artifacts.add(
            new DerivedArtifact(
                MouseVideoArtifactRef.ROLE_PROXY,
                staged.checksum(),
                staged.sizeBytes(),
                "video/mp4"));
        hasProxy = true;
      }
      for (var staged : pending) byteStore.publish(staged);
      return new Derivatives(hasProxy, hasThumbnail, indexedFrames, artifacts);
    } finally {
      for (var staged : pending) byteStore.discard(staged);
    }
  }

  /**
   * Streams a bounded, named-field frame list rather than buffering or positionally guessing
   * ffprobe output. ffprobe's compact writer emits {@code name=value} pairs, so a key-frame flag
   * can never be mistaken for a presentation timestamp. The configured frame cap halts consumption
   * and marks the index incomplete rather than silently treating a truncated list as whole.
   */
  private Path deriveFrameIndex(Path workspace, Path media, MouseVideoCancellation cancellation)
      throws IOException, InterruptedException {
    Path output = workspace.resolve("frame-index.ndjson");
    List<String> frames = new ArrayList<>();
    AtomicReference<String> timeBase = new AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean missingTiming =
        new java.util.concurrent.atomic.AtomicBoolean();
    long cap = Math.max(1L, properties.getMaxIndexedFrames());
    MouseVideoProcessRunner.Result result =
        runner.run(
            List.of(
                properties.getFfprobe(),
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=time_base:frame=best_effort_timestamp,best_effort_timestamp_time,pts,pts_time,key_frame,duration_time,pkt_duration_time",
                "-of",
                "compact=p=0:nk=0",
                media.toAbsolutePath().toString()),
            TimeUnit.SECONDS.toMillis(properties.getProbeBudgetSeconds()),
            cancellation,
            line -> {
              Map<String, String> values = parseCompactLine(line);
              if (values.containsKey("time_base")) {
                timeBase.set(values.get("time_base"));
                return true;
              }
              if (!values.containsKey("key_frame")) {
                return true;
              }
              String pts = firstPresent(values, "best_effort_timestamp", "pts");
              String ptsTime = firstPresent(values, "best_effort_timestamp_time", "pts_time");
              String duration = firstPresent(values, "duration_time", "pkt_duration_time");
              boolean timingValid =
                  (pts != null || ptsTime != null)
                      && (pts == null || isInteger(pts))
                      && (ptsTime == null || isNumeric(ptsTime))
                      && (duration == null
                          || (isNumeric(duration)
                              && new BigDecimal(duration.trim()).signum() >= 0));
              if (!timingValid) {
                missingTiming.set(true);
              }
              frames.add(frameJson(values));
              return frames.size() < cap;
            },
            MAX_TOOL_OUTPUT_BYTES);
    if (!result.outputComplete()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED,
          "Frame index output did not drain completely; refusing partial evidence");
    }
    if (result.exitCode() != 0 && !result.halted()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED,
          "ffprobe could not index the source frames (exit " + result.exitCode() + ")");
    }
    if (frames.isEmpty()) {
      return null;
    }
    boolean complete =
        result.exitCode() == 0
            && result.outputComplete()
            && !result.halted()
            && !result.stdoutTruncated()
            && !result.readFailed()
            && !result.callbackFailed()
            && !result.drainTimedOut()
            && validTimeBase(timeBase.get())
            && !missingTiming.get();
    String timeBaseValue = validTimeBase(timeBase.get()) ? timeBase.get().trim() : null;
    StringBuilder document = new StringBuilder(256 + frames.size() * 72);
    document
        .append("{\"schema\":\"")
        .append(FRAME_INDEX_SCHEMA)
        .append("\",\"complete\":")
        .append(complete)
        .append(",\"timeBase\":")
        .append(timeBaseValue == null ? "null" : jsonString(timeBaseValue))
        .append(",\"frames\":[");
    for (int index = 0; index < frames.size(); index++) {
      if (index > 0) {
        document.append(',');
      }
      document.append(frames.get(index));
    }
    document.append("]}");
    Files.writeString(output, document.toString(), StandardCharsets.UTF_8);
    return output;
  }

  private boolean runFfmpeg(MouseVideoCancellation cancellation, String... arguments)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(properties.getFfmpeg());
    command.addAll(List.of(arguments));
    MouseVideoProcessRunner.Result result =
        runner.run(
            command,
            TimeUnit.SECONDS.toMillis(properties.getAnalysisBudgetSeconds()),
            cancellation,
            null,
            0);
    return result.exitCode() == 0;
  }

  private long countFrames(Path index) throws IOException {
    String content = Files.readString(index, StandardCharsets.UTF_8);
    int frames = 0;
    int position = 0;
    while ((position = content.indexOf("{\"pts\"", position)) >= 0) {
      frames++;
      position++;
    }
    return frames;
  }

  private static void checkCancelled(MouseVideoCancellation cancellation)
      throws InterruptedException {
    if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Derivative generation cancelled");
    }
  }

  private static Map<String, String> parseCompactLine(String line) {
    Map<String, String> values = new LinkedHashMap<>();
    if (line == null || line.isBlank()) {
      return values;
    }
    for (String item : line.trim().split("\\|", -1)) {
      int separator = item.indexOf('=');
      if (separator > 0) {
        values.put(item.substring(0, separator), item.substring(separator + 1));
      }
    }
    return values;
  }

  private static String frameJson(Map<String, String> values) {
    String pts = firstPresent(values, "best_effort_timestamp", "pts");
    String ptsTime = firstPresent(values, "best_effort_timestamp_time", "pts_time");
    String duration = firstPresent(values, "duration_time", "pkt_duration_time");
    boolean key = "1".equals(values.getOrDefault("key_frame", "0").trim());
    return "{\"pts\":"
        + nullableInteger(pts)
        + ",\"ptsTime\":"
        + nullableNumber(ptsTime)
        + ",\"duration\":"
        + nullableNumber(duration)
        + ",\"key\":"
        + key
        + '}';
  }

  private static String firstPresent(Map<String, String> values, String... names) {
    for (String name : names) {
      String value = values.get(name);
      if (value != null && !value.isBlank() && !"N/A".equalsIgnoreCase(value.trim())) {
        return value;
      }
    }
    return null;
  }

  private static String nullableNumber(String value) {
    if (value == null || value.isBlank()) {
      return "null";
    }
    String trimmed = value.trim().toLowerCase(Locale.ROOT);
    return isNumeric(trimmed) ? new BigDecimal(trimmed).toString() : "null";
  }

  private static boolean isNumeric(String value) {
    try {
      if (!Double.isFinite(Double.parseDouble(value.trim()))) {
        return false;
      }
      new BigDecimal(value.trim());
      return true;
    } catch (NumberFormatException notNumeric) {
      return false;
    }
  }

  private static String nullableInteger(String value) {
    return value != null && isInteger(value) ? Long.toString(Long.parseLong(value.trim())) : "null";
  }

  private static boolean isInteger(String value) {
    try {
      Long.parseLong(value.trim());
      return true;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static boolean validTimeBase(String value) {
    if (value == null) {
      return false;
    }
    String[] parts = value.trim().split("/", -1);
    if (parts.length != 2 || !parts[0].matches("[0-9]+") || !parts[1].matches("[0-9]+")) {
      return false;
    }
    try {
      return Long.parseLong(parts[0]) > 0 && Long.parseLong(parts[1]) > 0;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static String jsonString(String value) {
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  private String proxyEncoder(MouseVideoCancellation cancellation)
      throws IOException, InterruptedException {
    String configured = properties.getProxyEncoder();
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    String cached = proxyEncoderStatus.get();
    if (cached != null) {
      return cached;
    }
    String detected = detectEncoder(cancellation);
    proxyEncoderStatus.set(detected);
    return detected;
  }

  private String detectEncoder(MouseVideoCancellation cancellation)
      throws IOException, InterruptedException {
    MouseVideoProcessRunner.Result result =
        runner.run(
            List.of(properties.getFfmpeg(), "-hide_banner", "-encoders"),
            5_000L,
            cancellation,
            null,
            256 * 1024);
    String text = result.stdout();
    for (String candidate : new String[] {"libx264", "libopenh264", "h264"}) {
      if (text.contains(" " + candidate + " ")) {
        return candidate;
      }
    }
    return "libx264";
  }

  private boolean binaryAvailable(String binary) {
    if (binary == null || binary.isBlank()) {
      return false;
    }
    try {
      MouseVideoProcessRunner.Result result =
          runner.run(
              List.of(binary, "-version"), 5_000L, new MouseVideoCancellation(), null, 64 * 1024);
      return result.exitCode() == 0 && result.outputComplete();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return false;
    } catch (IOException | RuntimeException failure) {
      return false;
    }
  }
}

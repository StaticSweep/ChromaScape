package com.chromascape.web.recording.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Writes {@code <recording>.frames.jsonl} next to an MP4: one line per encoded frame with the
 * capture time of the image it holds.
 *
 * <p>The encoder stamps frames at an even CFR cadence and fills capture gaps by repeating the last
 * image, so a frame's presentation time is not when it was captured. This sidecar keeps the real
 * capture clock ({@link System#nanoTime()} plus the epoch/nanosecond pair at recording start), so
 * external per-frame traces from the same machine, such as micro6's mouse trace, can be aligned to
 * exact frames. A write failure disables the sidecar for the rest of the recording and never
 * affects the video.
 */
final class RecordingFrameTimingWriter implements AutoCloseable {
  static final String SCHEMA = "chromascape-recording-frames/v1";

  private static final Logger logger = LogManager.getLogger(RecordingFrameTimingWriter.class);

  private final Path file;
  private BufferedWriter out;

  private RecordingFrameTimingWriter(Path file, BufferedWriter out) {
    this.file = file;
    this.out = out;
  }

  /** Sidecar path for a recording file. */
  static Path sidecarFor(Path recording) {
    String name = recording.getFileName().toString();
    String base = name.endsWith(".mp4") ? name.substring(0, name.length() - 4) : name;
    return recording.resolveSibling(base + ".frames.jsonl");
  }

  /** Opens the sidecar and writes its header, or returns {@code null} when it cannot be created. */
  static RecordingFrameTimingWriter open(
      Path recording, String recordingId, long startEpochMs, long startNanos, int fps) {
    Path file = sidecarFor(recording);
    try {
      BufferedWriter out =
          Files.newBufferedWriter(
              file,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
      RecordingFrameTimingWriter writer = new RecordingFrameTimingWriter(file, out);
      writer.write(
          String.format(
              Locale.ROOT,
              "{\"schema\":\"%s\",\"kind\":\"header\",\"recordingId\":\"%s\",\"startEpochMs\":%d,"
                  + "\"startNanos\":%d,\"fps\":%d,\"ptsMicros\":\"per frame\"}",
              SCHEMA,
              recordingId.replaceAll("[^A-Za-z0-9_.-]", "_"),
              startEpochMs,
              startNanos,
              fps));
      return writer;
    } catch (IOException e) {
      logger.warn("Frame timing sidecar disabled: cannot create {}: {}", file, e.getMessage());
      return null;
    }
  }

  Path file() {
    return file;
  }

  /** Records encoded frame {@code frame}; {@code duplicate} marks a CFR fill of the last image. */
  /**
   * One encoded frame: its encode ordinal, capture clock and media time. The encoder places frames
   * on capture-time slots and skips slots it has no new frame for, so the ordinal does not imply
   * the media time; {@code ptsMicros} is written per frame.
   */
  void frame(long frame, long captureNanos, boolean duplicate, long ptsMicros) {
    write(
        String.format(
            Locale.ROOT,
            "{\"frame\":%d,\"captureNanos\":%d,\"duplicate\":%b,\"ptsMicros\":%d}",
            frame,
            captureNanos,
            duplicate,
            ptsMicros));
  }

  private void write(String line) {
    if (out == null) {
      return;
    }
    try {
      out.write(line);
      out.newLine();
    } catch (IOException e) {
      logger.warn("Frame timing sidecar disabled after write failure: {}", e.getMessage());
      closeQuietly();
    }
  }

  @Override
  public void close() {
    if (out == null) {
      return;
    }
    try {
      out.flush();
    } catch (IOException e) {
      logger.warn("Frame timing sidecar flush failed: {}", e.getMessage());
    }
    closeQuietly();
  }

  private void closeQuietly() {
    try {
      out.close();
    } catch (IOException ignored) {
      // Already reported, or nothing more to lose.
    }
    out = null;
  }
}

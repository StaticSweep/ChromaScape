package com.chromascape.web.recording.service;

import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.web.recording.config.RecordingConfig;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for recording game sessions to video files for later replay.
 *
 * <p>This service captures the game window at a configured FPS rate and encodes it to MP4 format
 * using FFmpeg. Recordings are stored with metadata that can be synchronized with logs for replay
 * analysis.
 */
@Service
public class RecordingService {

  private static final Logger logger = LogManager.getLogger(RecordingService.class);

  private final RecordingConfig config;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean recording = new AtomicBoolean(false);
  private final AtomicLong frameCount = new AtomicLong(0);
  private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();

  private FFmpegFrameRecorder recorder;
  private File currentRecordingFile;
  private long recordingStartTime;
  private String currentRecordingId;

  @Autowired
  public RecordingService(RecordingConfig config) {
    this.config = config;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "RecordingService");
              t.setDaemon(true);
              return t;
            });

    // Ensure output directory exists
    try {
      Path outputDir = Paths.get(config.getOutputDirectory());
      if (!Files.exists(outputDir)) {
        Files.createDirectories(outputDir);
        logger.info("Created recordings directory: {}", outputDir.toAbsolutePath());
      }
    } catch (Exception e) {
      logger.error("Failed to create recordings directory: {}", e.getMessage());
    }
  }

  /**
   * Starts a new recording session.
   *
   * <p>Creates a new MP4 file and begins capturing frames at the configured FPS.
   *
   * @return The recording ID for this session
   */
  public String startRecording() {
    if (recording.get()) {
      logger.warn("Recording is already in progress");
      return currentRecordingId;
    }

    if (!config.isEnabled()) {
      logger.warn("Recording is disabled");
      return null;
    }

    try {
      // Generate recording ID and filename
      recordingStartTime = System.currentTimeMillis();
      currentRecordingId = "recording_" + recordingStartTime;
      String filename = currentRecordingId + ".mp4";
      currentRecordingFile = Paths.get(config.getOutputDirectory(), filename).toFile();

      // Initialize FFmpeg recorder
      int width = 800; // Will be updated on first frame
      int height = 600;

      recorder =
          new FFmpegFrameRecorder(currentRecordingFile, width, height, 0); // No audio channels

      recorder.setVideoCodecName("libx264"); // H.264 codec
      recorder.setFormat("mp4");
      recorder.setFrameRate(config.getFps());
      recorder.setVideoBitrate((int) (config.getVideoQuality() * 10000)); // Quality-based bitrate
      recorder.setVideoQuality(config.getVideoQuality());

      recorder.start();

      frameCount.set(0);
      recording.set(true);

      logger.info(
          "Started recording: {} at {} FPS", currentRecordingFile.getName(), config.getFps());

      // Start frame capture loop
      long intervalMs = (long) (1000.0 / config.getFps());
      scheduler.scheduleAtFixedRate(this::captureFrame, 0, intervalMs, TimeUnit.MILLISECONDS);

      return currentRecordingId;

    } catch (Exception e) {
      logger.error("Failed to start recording: {}", e.getMessage());
      cleanup();
      return null;
    }
  }

  /**
   * Stops the current recording session.
   *
   * <p>Finalizes the video file and returns recording metadata.
   *
   * @return Recording metadata including duration and file path
   */
  public RecordingMetadata stopRecording() {
    if (!recording.getAndSet(false)) {
      logger.warn("No recording in progress");
      return null;
    }

    logger.info("Stopping recording...");

    try {
      // Shutdown scheduler
      scheduler.shutdown();
      if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }

      // Finalize recording
      if (recorder != null) {
        recorder.stop();
        recorder.release();
        recorder = null;
      }

      long duration = System.currentTimeMillis() - recordingStartTime;
      long frames = frameCount.get();

      logger.info(
          "Recording saved: {} ({} frames, {} ms)",
          currentRecordingFile.getName(),
          frames,
          duration);

      return new RecordingMetadata(
          currentRecordingId,
          currentRecordingFile.getAbsolutePath(),
          recordingStartTime,
          duration,
          frames,
          config.getFps());

    } catch (Exception e) {
      logger.error("Failed to stop recording: {}", e.getMessage());
      cleanup();
      return null;
    }
  }

  /** Captures a single frame and adds it to the recording. */
  private void captureFrame() {
    if (!recording.get() || recorder == null) {
      return;
    }

    try {
      BufferedImage capturedImage = ScreenManager.captureWindow();

      if (capturedImage != null) {
        // First frame - set dimensions
        if (frameCount.get() == 0) {
          recorder.setImageWidth(capturedImage.getWidth());
          recorder.setImageHeight(capturedImage.getHeight());
        }

        // Convert and record frame
        recorder.record(frameConverter.convert(capturedImage));
        frameCount.incrementAndGet();

        // Check max duration
        if (config.getMaxDurationMinutes() > 0) {
          long elapsedMs = System.currentTimeMillis() - recordingStartTime;
          long maxMs = config.getMaxDurationMinutes() * 60 * 1000L;
          if (elapsedMs >= maxMs) {
            logger.info("Max recording duration reached, stopping");
            stopRecording();
          }
        }
      }
    } catch (Exception e) {
      logger.warn("Failed to capture frame: {}", e.getMessage());
    }
  }

  /** Returns whether a recording is currently in progress. */
  public boolean isRecording() {
    return recording.get();
  }

  /** Returns the current recording ID if recording, or null otherwise. */
  public String getCurrentRecordingId() {
    return recording.get() ? currentRecordingId : null;
  }

  /** Cleanup resources. */
  @PreDestroy
  public void cleanup() {
    if (recording.get()) {
      stopRecording();
    }
  }

  /** Metadata about a recording session. */
  public static class RecordingMetadata {
    private final String id;
    private final String filePath;
    private final long startTime;
    private final long duration;
    private final long frameCount;
    private final int fps;

    public RecordingMetadata(
        String id, String filePath, long startTime, long duration, long frameCount, int fps) {
      this.id = id;
      this.filePath = filePath;
      this.startTime = startTime;
      this.duration = duration;
      this.frameCount = frameCount;
      this.fps = fps;
    }

    public String getId() {
      return id;
    }

    public String getFilePath() {
      return filePath;
    }

    public long getStartTime() {
      return startTime;
    }

    public long getDuration() {
      return duration;
    }

    public long getFrameCount() {
      return frameCount;
    }

    public int getFps() {
      return fps;
    }
  }
}

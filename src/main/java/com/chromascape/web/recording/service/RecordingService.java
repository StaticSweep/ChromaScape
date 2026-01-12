package com.chromascape.web.recording.service;

import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.exception.FrameCaptureException;
import com.chromascape.web.recording.exception.RecordingAlreadyInProgressException;
import com.chromascape.web.recording.exception.RecordingDisabledException;
import com.chromascape.web.recording.exception.RecordingException;
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
  private int initialWidth;
  private int initialHeight;
  private static final String[] VIDEO_CODEC_PREFERENCES =
      new String[] {"libx264", "libopenh264", "mpeg4"};

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
      logger.warn("Recording is already in progress: {}", currentRecordingId);
      throw new RecordingAlreadyInProgressException(currentRecordingId);
    }

    if (!config.isEnabled()) {
      logger.warn("Recording is disabled");
      throw new RecordingDisabledException();
    }

    try {
      // Generate recording ID and filename
      recordingStartTime = System.currentTimeMillis();
      currentRecordingId = "recording_" + recordingStartTime;
      String filename = currentRecordingId + ".mp4";
      currentRecordingFile = Paths.get(config.getOutputDirectory(), filename).toFile();

      // Try to capture first frame to get actual dimensions
      BufferedImage firstFrame = null;
      String lastCaptureError = null;
      int maxAttempts = 5;
      for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
          firstFrame = ScreenManager.captureWindow();
          if (firstFrame != null) {
            break;
          }
        } catch (RuntimeException e) {
          lastCaptureError = e.getMessage();
          logger.warn(
              "Failed to capture frame for recording (attempt {}/{}): {}",
              attempt,
              maxAttempts,
              e.getMessage());
        }
        if (attempt < maxAttempts) {
          logger.warn(
              "Failed to capture frame for recording (attempt {}/{}), retrying...",
              attempt,
              maxAttempts);
          try {
            Thread.sleep(100);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Recording start interrupted while waiting for initial frame");
            break;
          }
        }
      }

      if (firstFrame == null) {
        logger.error("Failed to capture first frame after {} attempts", maxAttempts);
        throw new FrameCaptureException(maxAttempts, lastCaptureError);
      }

      BufferedImage normalizedFirstFrame = normalizeFrameDimensions(firstFrame);
      int width = normalizedFirstFrame.getWidth();
      int height = normalizedFirstFrame.getHeight();

      // Store initial dimensions for validation during capture
      initialWidth = width;
      initialHeight = height;

      logger.info("Initializing recorder with captured dimensions: {}x{}", width, height);

      // Initialize FFmpeg recorder with dimensions and codec fallback
      startRecorderWithFallbacks(width, height);

      // Record the first frame
      recorder.record(frameConverter.convert(normalizedFirstFrame));
      frameCount.set(1);

      recording.set(true);

      logger.info(
          "Started recording: {} at {} FPS", currentRecordingFile.getName(), config.getFps());

      // Start frame capture loop
      long intervalMs = (long) (1000.0 / config.getFps());
      scheduler.scheduleAtFixedRate(this::captureFrame, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

      return currentRecordingId;

    } catch (RecordingException e) {
      // Re-throw our custom exceptions
      cleanupRecording();
      throw e;
    } catch (Exception e) {
      logger.error("Failed to start recording: {}", e.getMessage(), e);
      cleanupRecording();
      throw new RecordingException(
          "Unexpected error during recording initialization: " + e.getMessage(),
          "An unexpected error occurred while starting the recording. Please try again or check the logs.");
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
      // Finalize recording
      if (recorder != null) {
        // CRITICAL: Flush buffered frames before stopping
        logger.debug("Flushing recorder buffers...");
        recorder.flush();

        logger.debug("Stopping recorder...");
        recorder.stop();

        logger.debug("Releasing recorder resources...");
        recorder.release();
        recorder = null;

        // Give file system time to complete I/O
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }

        // Verify file was written
        if (!currentRecordingFile.exists() || currentRecordingFile.length() == 0) {
          logger.error("Recording file not created or empty after finalization");
          return null;
        }

        logger.debug("Recording file finalized: {} bytes", currentRecordingFile.length());
      }

      long duration = System.currentTimeMillis() - recordingStartTime;
      long frames = frameCount.get();

      logger.info(
          "Recording saved: {} ({} frames, {} ms, {} bytes)",
          currentRecordingFile.getName(),
          frames,
          duration,
          currentRecordingFile.length());

      return new RecordingMetadata(
          currentRecordingId,
          currentRecordingFile.getAbsolutePath(),
          recordingStartTime,
          duration,
          frames,
          config.getFps());

    } catch (Exception e) {
      logger.error("Failed to stop recording: {}", e.getMessage(), e);
      cleanup();
      return null;
    }
  }

  /**
   * Start recording with auto-start trigger (called when script starts).
   *
   * @param scriptName The name of the script that triggered recording
   * @return The recording ID, or null if recording failed
   */
  public String startRecordingAuto(String scriptName) {
    if (recording.get()) {
      logger.warn("Recording is already in progress, ignoring auto-start request");
      return currentRecordingId;
    }

    if (!config.isEnabled()) {
      logger.info("Recording is disabled, ignoring auto-start request");
      return null;
    }

    logger.info("Auto-starting recording for script: {}", scriptName);

    // Use the existing startRecording method
    return startRecording();
  }

  /** Captures a single frame and adds it to the recording. */
  private void captureFrame() {
    if (!recording.get() || recorder == null) {
      return;
    }

    try {
      BufferedImage capturedImage = ScreenManager.captureWindow();

      if (capturedImage != null) {
        capturedImage = normalizeFrameDimensions(capturedImage);
        // CRITICAL: Validate dimensions match initial dimensions
        // Changing dimensions after recorder.start() corrupts MP4 metadata
        int frameWidth = capturedImage.getWidth();
        int frameHeight = capturedImage.getHeight();

        if (frameWidth != initialWidth || frameHeight != initialHeight) {
          logger.warn(
              "Frame dimension mismatch detected! Expected: {}x{}, Got: {}x{}. Dropping frame to preserve video integrity.",
              initialWidth,
              initialHeight,
              frameWidth,
              frameHeight);
          // Drop this frame - do not record it
          return;
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

  /** Returns the recording start time if recording, or null otherwise. */
  public Long getRecordingStartTime() {
    return recording.get() ? recordingStartTime : null;
  }

  /**
   * Force reset the recording state.
   *
   * <p>This is a recovery mechanism for when the recording state gets stuck. Use with caution as it
   * may leave incomplete recordings.
   *
   * @return true if a recording was in progress and was reset, false otherwise
   */
  public boolean forceResetRecordingState() {
    boolean wasRecording = recording.getAndSet(false);

    if (wasRecording) {
      logger.warn("Force resetting recording state. Recording ID: {}", currentRecordingId);

      // Clean up resources
      try {
        if (recorder != null) {
          recorder.stop();
          recorder.release();
          recorder = null;
        }
      } catch (Exception e) {
        logger.warn("Error while force-stopping recorder: {}", e.getMessage());
      }

      currentRecordingId = null;
      recordingStartTime = 0;
      frameCount.set(0);
    }

    return wasRecording;
  }

  /**
   * Cleanup recording resources without shutting down the scheduler.
   *
   * <p>Use this for error recovery, not application shutdown. This method cleans up the recorder
   * and resets recording state but keeps the scheduler running so recording can be attempted
   * again.
   */
  private void cleanupRecording() {
    logger.debug("Cleaning up recording resources");

    try {
      if (recorder != null) {
        recorder.stop();
        recorder.release();
        recorder = null;
      }
    } catch (Exception e) {
      logger.warn("Error while cleaning up recorder: {}", e.getMessage());
    }

    // Reset state but don't shutdown scheduler
    recording.set(false);
    currentRecordingId = null;
    currentRecordingFile = null;
    recordingStartTime = 0;
    frameCount.set(0);
    initialWidth = 0;
    initialHeight = 0;
  }

  private void startRecorderWithFallbacks(int width, int height) {
    Exception lastException = null;

    for (String codecName : VIDEO_CODEC_PREFERENCES) {
      FFmpegFrameRecorder candidate = null;
      try {
        if (currentRecordingFile.exists() && !currentRecordingFile.delete()) {
          logger.warn("Failed to delete existing recording file: {}", currentRecordingFile);
        }
        candidate = createRecorder(codecName, width, height);
        candidate.start();
        recorder = candidate;
        logger.info("Recording codec initialized: {}", codecName);
        return;
      } catch (Exception e) {
        lastException = e;
        logger.warn(
            "Failed to start recorder with codec {}: {}", codecName, e.getMessage());
        try {
          if (candidate != null) {
            candidate.release();
          }
        } catch (Exception releaseError) {
          logger.debug("Failed to release recorder after codec failure: {}", releaseError.getMessage());
        }
      }
    }

    String detail = lastException != null ? lastException.getMessage() : "unknown error";
    throw new RecordingException(
        "Failed to start recorder with available codecs: " + detail,
        "Failed to start video recording. Please check FFmpeg codec availability.");
  }

  private FFmpegFrameRecorder createRecorder(String codecName, int width, int height) {
    FFmpegFrameRecorder candidate = new FFmpegFrameRecorder(currentRecordingFile, width, height, 0);

    candidate.setVideoCodecName(codecName);
    candidate.setFormat("mp4");
    candidate.setFrameRate(config.getFps());
    candidate.setVideoBitrate((int) (config.getVideoQuality() * 10000));

    // Browser compatibility settings
    candidate.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);

    if ("libx264".equals(codecName)) {
      candidate.setVideoOption("preset", "ultrafast");
      candidate.setVideoOption("profile", "main");
    }

    // Container-level option for web streaming
    candidate.setOption("movflags", "+faststart");
    return candidate;
  }

  private BufferedImage normalizeFrameDimensions(BufferedImage frame) {
    int width = frame.getWidth();
    int height = frame.getHeight();
    int evenWidth = width - (width % 2);
    int evenHeight = height - (height % 2);

    if (evenWidth <= 0 || evenHeight <= 0) {
      throw new IllegalArgumentException(
          "Captured frame dimensions are invalid: " + width + "x" + height);
    }

    if (evenWidth == width && evenHeight == height) {
      return frame;
    }

    logger.warn(
        "Captured frame dimensions {}x{} are not even; trimming to {}x{} for recorder compatibility.",
        width,
        height,
        evenWidth,
        evenHeight);
    return frame.getSubimage(0, 0, evenWidth, evenHeight);
  }

  /**
   * Cleanup resources on application shutdown.
   *
   * <p>WARNING: This method shuts down the scheduler and should ONLY be called during application
   * termination (@PreDestroy). For error recovery during recording, use cleanupRecording()
   * instead.
   */
  @PreDestroy
  public void cleanup() {
    logger.info("RecordingService cleanup - shutting down scheduler");
    if (recording.get()) {
      stopRecording();
    }
    // Shutdown scheduler only during application shutdown
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
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

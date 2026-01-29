package com.chromascape.web.recording.service;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameListener;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.exception.FrameCaptureException;
import com.chromascape.web.recording.exception.RecordingAlreadyInProgressException;
import com.chromascape.web.recording.exception.RecordingDisabledException;
import com.chromascape.web.recording.exception.RecordingException;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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
 * <p>This service subscribes to the {@link UnifiedFrameCaptureService} to receive captured frames
 * and encodes them to MP4 format using FFmpeg. Recordings are stored with metadata that can be
 * synchronized with logs for replay analysis.
 */
@Service
public class RecordingService implements FrameListener {

  private static final Logger logger = LogManager.getLogger(RecordingService.class);

  private final RecordingConfig config;
  private final UnifiedCaptureConfig unifiedConfig;
  private final UnifiedFrameCaptureService unifiedCaptureService;
  private final ExecutorService encoderThread;
  private final AtomicBoolean recording = new AtomicBoolean(false);
  private final AtomicLong frameCount = new AtomicLong(0);
  private final AtomicLong droppedFrameCount = new AtomicLong(0);
  private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();

  // Frame buffer for producer-consumer pattern
  private static final int FRAME_BUFFER_SIZE =
      120; // ~4 seconds at 30 FPS (increased for stability)
  private final BlockingQueue<BufferedImage> frameBuffer =
      new LinkedBlockingQueue<>(FRAME_BUFFER_SIZE);
  private volatile boolean encoderRunning = false;
  private long encodedFrameCount = 0; // Track encoded frames for timestamp calculation

  private FFmpegFrameRecorder recorder;
  private File currentRecordingFile;
  private long recordingStartTime;
  private String currentRecordingId;
  private int initialWidth;
  private int initialHeight;
  private static final String[] VIDEO_CODEC_PREFERENCES =
      new String[] {"libx264", "libopenh264", "mpeg4"};

  @Autowired
  public RecordingService(
      RecordingConfig config,
      UnifiedCaptureConfig unifiedConfig,
      UnifiedFrameCaptureService unifiedCaptureService) {
    this.config = config;
    this.unifiedConfig = unifiedConfig;
    this.unifiedCaptureService = unifiedCaptureService;

    // Encoder thread - consumes frames from buffer and encodes them
    this.encoderThread =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "RecordingEncoder");
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
      // Generate recording ID using UUID (decouple from timestamp)
      currentRecordingId = "recording_" + UUID.randomUUID().toString().substring(0, 8);
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

      // Clear any leftover frames in buffer
      frameBuffer.clear();
      droppedFrameCount.set(0);

      // CAPTURE START TIME HERE - immediately before first frame queued
      // This ensures minimal drift between video timeline and log timestamps
      recordingStartTime = System.currentTimeMillis();

      // Add first frame to buffer
      frameBuffer.offer(normalizedFirstFrame);
      frameCount.set(1);

      recording.set(true);
      encoderRunning = true;

      logger.info(
          "Started recording: {} at {} FPS (buffer size: {})",
          currentRecordingFile.getName(),
          unifiedConfig.getFps(),
          FRAME_BUFFER_SIZE);

      // Start encoder thread (consumer)
      encoderThread.submit(this::encoderLoop);

      // Register with unified capture service to receive frames
      if (unifiedConfig.isEnabled()) {
        unifiedCaptureService.registerSubscriber(this);
        logger.info("Registered with unified capture service for recording");
      } else {
        logger.warn(
            "Unified capture is disabled, recording will not receive frames (legacy mode not implemented)");
      }

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
      // Unregister from unified capture service
      if (unifiedConfig.isEnabled()) {
        unifiedCaptureService.unregisterSubscriber(this);
        logger.info("Unregistered from unified capture service");
      }

      // Signal encoder thread to finish (it will drain remaining frames)
      encoderRunning = false;

      // Wait for encoder to drain buffer (up to 5 seconds)
      int waitCount = 0;
      while (!frameBuffer.isEmpty() && waitCount < 50) {
        Thread.sleep(100);
        waitCount++;
      }

      if (!frameBuffer.isEmpty()) {
        logger.warn(
            "Encoder did not drain buffer in time, {} frames remaining", frameBuffer.size());
      }

      // Give encoder a moment to finish encoding last frames
      Thread.sleep(200);

      // Finalize recording
      if (recorder != null) {
        logger.debug("Flushing recorder buffers...");
        recorder.flush();

        logger.debug("Stopping recorder...");
        recorder.stop();

        logger.debug("Releasing recorder resources...");
        recorder.release();
        recorder = null;

        // Give file system time to complete I/O
        Thread.sleep(200);

        // Verify file was written
        if (!currentRecordingFile.exists() || currentRecordingFile.length() == 0) {
          logger.error("Recording file not created or empty after finalization");
          return null;
        }

        logger.debug("Recording file finalized: {} bytes", currentRecordingFile.length());
      }

      long duration = System.currentTimeMillis() - recordingStartTime;
      long frames = frameCount.get();
      long dropped = droppedFrameCount.get();

      logger.info(
          "Recording saved: {} ({} frames captured, {} dropped, {} ms, {} bytes)",
          currentRecordingFile.getName(),
          frames,
          dropped,
          duration,
          currentRecordingFile.length());

      return new RecordingMetadata(
          currentRecordingId,
          currentRecordingFile.getAbsolutePath(),
          recordingStartTime,
          duration,
          frames,
          unifiedConfig.getFps());

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.error("Recording stop interrupted: {}", e.getMessage());
      cleanup();
      return null;
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

  /**
   * Called by the unified capture service when a new frame is captured.
   *
   * <p>Normalizes the frame and adds it to the encoding buffer.
   */
  @Override
  public void onFrameCaptured(FrameEvent event) {
    if (!recording.get()) {
      return;
    }

    try {
      BufferedImage capturedImage = event.getFrame();

      if (capturedImage != null) {
        capturedImage = normalizeFrameDimensions(capturedImage);

        // CRITICAL: Validate dimensions match initial dimensions
        int frameWidth = capturedImage.getWidth();
        int frameHeight = capturedImage.getHeight();

        if (frameWidth != initialWidth || frameHeight != initialHeight) {
          logger.warn(
              "Frame dimension mismatch detected! Expected: {}x{}, Got: {}x{}. Dropping frame.",
              initialWidth,
              initialHeight,
              frameWidth,
              frameHeight);
          return;
        }

        // Try to add to buffer (non-blocking)
        boolean added = frameBuffer.offer(capturedImage);
        if (added) {
          frameCount.incrementAndGet();
        } else {
          // Buffer full - drop frame to prevent memory issues
          long dropped = droppedFrameCount.incrementAndGet();
          if (dropped % 30 == 1) {
            logger.warn(
                "Frame buffer full, dropping frames. Total dropped: {} (buffer size: {})",
                dropped,
                frameBuffer.size());
          }
        }

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
      logger.warn("Failed to process frame for recording: {}", e.getMessage());
    }
  }

  /**
   * Returns whether this listener is active and wants to receive frames.
   *
   * <p>Always returns true while recording is active.
   */
  @Override
  public boolean isActive() {
    return recording.get();
  }

  /** Encoder loop that consumes frames from the buffer (consumer). */
  private void encoderLoop() {
    logger.info("Encoder thread started");
    encodedFrameCount = 0;
    BufferedImage lastFrame = null;

    while (encoderRunning || !frameBuffer.isEmpty()) {
      try {
        // Wait for frame with timeout
        BufferedImage frame = frameBuffer.poll(100, TimeUnit.MILLISECONDS);

        if (frame != null) {
          lastFrame = frame;
        } else if (encoderRunning && lastFrame != null) {
          // Buffer empty but still recording - duplicate last frame to maintain timeline
          frame = lastFrame;
        }

        if (frame != null && recorder != null) {
          // Set explicit timestamp based on frame index and FPS
          long timestampMicros = (encodedFrameCount * 1_000_000L) / unifiedConfig.getFps();
          recorder.setTimestamp(timestampMicros);
          recorder.record(frameConverter.convert(frame));
          encodedFrameCount++;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.info("Encoder thread interrupted");
        break;
      } catch (Exception e) {
        logger.warn("Failed to encode frame: {}", e.getMessage());
      }
    }

    // Drain remaining frames
    logger.info("Draining {} remaining frames from buffer", frameBuffer.size());
    BufferedImage frame;
    while ((frame = frameBuffer.poll()) != null) {
      try {
        if (recorder != null) {
          long timestampMicros = (encodedFrameCount * 1_000_000L) / unifiedConfig.getFps();
          recorder.setTimestamp(timestampMicros);
          recorder.record(frameConverter.convert(frame));
          encodedFrameCount++;
        }
      } catch (Exception e) {
        logger.warn("Failed to encode remaining frame: {}", e.getMessage());
      }
    }

    logger.info("Encoder thread finished (encoded {} frames)", encodedFrameCount);
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
   * Returns the recording start time as LocalDateTime for database storage.
   *
   * @return The recording start time as LocalDateTime, or null if not recording
   */
  public LocalDateTime getRecordingStartTimeAsLocalDateTime() {
    if (!recording.get() || recordingStartTime == 0) {
      return null;
    }
    return LocalDateTime.ofInstant(
        Instant.ofEpochMilli(recordingStartTime), ZoneId.systemDefault());
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

      // Stop encoder
      encoderRunning = false;

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

      // Clear buffer
      frameBuffer.clear();

      currentRecordingId = null;
      recordingStartTime = 0;
      frameCount.set(0);
      droppedFrameCount.set(0);
      encodedFrameCount = 0;
    }

    return wasRecording;
  }

  /**
   * Cleanup recording resources without shutting down the schedulers.
   *
   * <p>Use this for error recovery, not application shutdown. This method cleans up the recorder
   * and resets recording state but keeps the schedulers running so recording can be attempted
   * again.
   */
  private void cleanupRecording() {
    logger.debug("Cleaning up recording resources");

    // Stop encoder
    encoderRunning = false;

    try {
      if (recorder != null) {
        recorder.stop();
        recorder.release();
        recorder = null;
      }
    } catch (Exception e) {
      logger.warn("Error while cleaning up recorder: {}", e.getMessage());
    }

    // Clear buffer
    frameBuffer.clear();

    // Reset state but don't shutdown schedulers
    recording.set(false);
    currentRecordingId = null;
    currentRecordingFile = null;
    recordingStartTime = 0;
    frameCount.set(0);
    droppedFrameCount.set(0);
    encodedFrameCount = 0;
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
        logger.warn("Failed to start recorder with codec {}: {}", codecName, e.getMessage());
        try {
          if (candidate != null) {
            candidate.release();
          }
        } catch (Exception releaseError) {
          logger.debug(
              "Failed to release recorder after codec failure: {}", releaseError.getMessage());
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
    candidate.setFrameRate(unifiedConfig.getFps());

    // Codec-agnostic quality settings (apply to ALL codecs)
    candidate.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
    candidate.setVideoBitrate((int) (config.getVideoQuality() * 10000));
    candidate.setOption("movflags", "+faststart");

    // Codec-specific optimizations
    if ("libx264".equals(codecName)) {
      // Use "faster" preset instead of "ultrafast" for better quality
      candidate.setVideoOption("preset", "faster");
      candidate.setVideoOption("tune", "zerolatency");
      candidate.setVideoOption("profile", "main");
    }

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
   * <p>WARNING: This method shuts down the schedulers and should ONLY be called during application
   * termination (@PreDestroy). For error recovery during recording, use cleanupRecording() instead.
   */
  @PreDestroy
  public void cleanup() {
    logger.info("RecordingService cleanup - shutting down threads");
    if (recording.get()) {
      stopRecording();
    }

    // Shutdown encoder thread
    encoderThread.shutdown();
    try {
      if (!encoderThread.awaitTermination(2, TimeUnit.SECONDS)) {
        encoderThread.shutdownNow();
      }
    } catch (InterruptedException e) {
      encoderThread.shutdownNow();
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

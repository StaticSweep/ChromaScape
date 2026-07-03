package com.chromascape.web.recording.service;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameListener;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  private final AtomicLong duplicatedFrameCount = new AtomicLong(0);
  private volatile int queueHighWaterMark = 0;
  private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();

  // Frame buffer for producer-consumer pattern
  private static final int FRAME_BUFFER_SIZE = 300; // ~10 seconds at 30 FPS
  private final BlockingQueue<TimestampedFrame> frameBuffer =
      new ArrayBlockingQueue<>(FRAME_BUFFER_SIZE);
  private volatile boolean encoderRunning = false;
  private long encodedFrameCount = 0; // Track encoded frames for timestamp calculation

  private FFmpegFrameRecorder recorder;
  private File currentRecordingFile;
  private long recordingStartTime;
  private volatile long recordingStartTimeNano; // Store nano time at start
  private String currentRecordingId;
  private volatile RecordingTriggerSource currentTriggerSource;
  private String currentVideoCodec; // Track which codec was successfully used
  private int initialWidth;
  private int initialHeight;
  private long framesRecorded = 0; // Track total frames recorded for metrics
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
    return startRecording(RecordingTriggerSource.MANUAL);
  }

  /**
   * Starts a new recording session with an explicit trigger source.
   *
   * @param triggerSource what initiated this recording
   * @return The recording ID for this session
   */
  public String startRecording(RecordingTriggerSource triggerSource) {
    if (recording.get()) {
      logger.warn("Recording is already in progress: {}", currentRecordingId);
      throw new RecordingAlreadyInProgressException(currentRecordingId);
    }

    if (!config.isEnabled()) {
      logger.warn("Recording is disabled");
      throw new RecordingDisabledException();
    }

    currentTriggerSource = triggerSource;

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
      framesRecorded = 0;

      // CAPTURE START TIME HERE - immediately before first frame queued
      // This ensures minimal drift between video timeline and log timestamps
      recordingStartTime = System.currentTimeMillis();
      recordingStartTimeNano = System.nanoTime();

      // Add first frame to buffer (capture time is recording start time)
      frameBuffer.offer(new TimestampedFrame(normalizedFirstFrame, recordingStartTimeNano));
      frameCount.set(1);
      framesRecorded = 1;

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

      // Wait for encoder to drain buffer (dynamic timeout based on queue size)
      int waitCount = 0;
      long maxWaitMs =
          Math.min(5000L, (long) (frameBuffer.size() * 2L * (1000.0 / unifiedConfig.getFps())));
      while (!frameBuffer.isEmpty() && waitCount < (maxWaitMs / 100)) {
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
      long duplicated = duplicatedFrameCount.get();

      // Calculate actual FPS
      double actualCaptureFps = (frames * 1000.0) / duration;
      double expectedDurationSec = (double) encodedFrameCount / unifiedConfig.getFps();

      logger.info(
          """
        Recording saved: {}
        Duration: {} ms (expected ~{} sec based on {} frames @ {} FPS)
        Frames captured: {}
        Frames dropped: {} ({:.1f}%)
        Frames duplicated (CFR fill): {} ({:.1f}%)
        Frames encoded: {}
        Queue high-water mark: {}
        Actual capture FPS: {:.2f}
        Configured FPS: {}
        """,
          currentRecordingFile.getName(),
          duration,
          String.format("%.1f", expectedDurationSec),
          encodedFrameCount,
          unifiedConfig.getFps(),
          frames,
          dropped,
          dropped * 100.0 / (frames + dropped),
          duplicated,
          encodedFrameCount > 0 ? duplicated * 100.0 / encodedFrameCount : 0.0,
          encodedFrameCount,
          queueHighWaterMark,
          actualCaptureFps,
          unifiedConfig.getFps());

      return new RecordingMetadata(
          currentRecordingId,
          currentRecordingFile.getAbsolutePath(),
          recordingStartTime,
          duration,
          frames,
          unifiedConfig.getFps(),
          dropped,
          currentVideoCodec);

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
   * Get current recording metrics for monitoring.
   *
   * <p>Exposed via /api/recording/metrics endpoint.
   *
   * @return Map containing recording metrics
   */
  public java.util.Map<String, Object> getMetrics() {
    java.util.Map<String, Object> metrics = new java.util.HashMap<>();
    metrics.put("isRecording", recording.get());
    metrics.put("frameBufferSize", FRAME_BUFFER_SIZE);
    metrics.put("frameBufferDepth", frameBuffer.size());
    metrics.put("frameBufferCapacity", frameBuffer.remainingCapacity());
    metrics.put("droppedFrames", droppedFrameCount.get());
    metrics.put("duplicatedFrames", duplicatedFrameCount.get());
    metrics.put("queueHighWaterMark", queueHighWaterMark);
    metrics.put("framesRecorded", framesRecorded);
    metrics.put("currentRecordingId", currentRecordingId);
    metrics.put(
        "recordingDurationMs",
        recording.get() ? System.currentTimeMillis() - recordingStartTime : 0);
    return metrics;
  }

  /**
   * Start recording with auto-start trigger (called when script starts).
   *
   * @param scriptName The name of the script that triggered recording
   * @return The recording ID, or null if recording failed
   */
  public String startRecordingAuto(String scriptName) {
    return startRecordingAuto(scriptName, RecordingTriggerSource.TASK_AUTO);
  }

  /**
   * Start recording with auto-start trigger and an explicit source.
   *
   * @param scriptName The name of the script that triggered recording
   * @param triggerSource what initiated this recording
   * @return The recording ID, or null if recording failed
   */
  public String startRecordingAuto(String scriptName, RecordingTriggerSource triggerSource) {
    if (recording.get()) {
      logger.warn("Recording is already in progress, ignoring auto-start request");
      return currentRecordingId;
    }

    if (!config.isEnabled()) {
      logger.info("Recording is disabled, ignoring auto-start request");
      return null;
    }
    if (!config.isAutoRecord()) {
      logger.warn(
          "Auto-recording is disabled (recording.autoRecord=false), ignoring auto-start"
              + " request: source={}",
          triggerSource);
      return null;
    }

    logger.info("Auto-starting recording for script: {} (source: {})", scriptName, triggerSource);

    return startRecording(triggerSource);
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

        // Store with capture timestamp
        TimestampedFrame timestampedFrame =
            new TimestampedFrame(capturedImage, event.getCaptureTimeNanos());

        // Drop-oldest policy: if full, remove oldest then add newest
        if (frameBuffer.remainingCapacity() == 0) {
          frameBuffer.poll(); // Drop oldest frame
          long dropped = droppedFrameCount.incrementAndGet();
          if (dropped % 30 == 1) {
            logger.warn(
                "Frame buffer full, dropped {} total frames (buffer size: {})",
                dropped,
                frameBuffer.size());
          }
        }
        frameBuffer.offer(timestampedFrame);
        frameCount.incrementAndGet();
        framesRecorded++;

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
    duplicatedFrameCount.set(0);
    queueHighWaterMark = 0;
    TimestampedFrame lastFrame = null;
    final int targetFps = unifiedConfig.getFps();

    while (encoderRunning || !frameBuffer.isEmpty()) {
      try {
        // Wait for frame with timeout
        TimestampedFrame timestampedFrame = frameBuffer.poll(100, TimeUnit.MILLISECONDS);

        if (timestampedFrame != null) {
          lastFrame = timestampedFrame;
        } else if (encoderRunning && lastFrame != null) {
          // No frame received within poll window — duplicate last frame to maintain CFR cadence
          timestampedFrame = lastFrame;
          duplicatedFrameCount.incrementAndGet();
        }

        if (timestampedFrame != null && recorder != null) {
          // CFR timestamp: each frame slot gets an evenly-spaced timestamp regardless of
          // actual capture wall-clock time. This prevents VFR artifacts in the output.
          long timestampMicros = encodedFrameCount * 1_000_000L / targetFps;
          recorder.setTimestamp(timestampMicros);
          recorder.record(frameConverter.convert(timestampedFrame.image));
          encodedFrameCount++;

          // Track queue high-water mark
          int queueDepth = frameBuffer.size();
          if (queueDepth > queueHighWaterMark) {
            queueHighWaterMark = queueDepth;
          }

          // Detect encoder lag
          if (queueDepth > FRAME_BUFFER_SIZE * 0.8) {
            logger.warn(
                "Encoder lag detected: queue depth {} ({}% full)",
                queueDepth, Math.round((queueDepth / (double) FRAME_BUFFER_SIZE) * 100));
          }

          // Periodic logging (every 30 frames with queue depth)
          if (encodedFrameCount % 30 == 0) {
            logger.info(
                "Encode progress: {} frames, queue depth: {}, dropped: {}, duplicated: {}",
                encodedFrameCount,
                queueDepth,
                droppedFrameCount.get(),
                duplicatedFrameCount.get());
          }
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
    TimestampedFrame frame;
    while ((frame = frameBuffer.poll()) != null) {
      try {
        if (recorder != null) {
          long timestampMicros = encodedFrameCount * 1_000_000L / targetFps;
          recorder.setTimestamp(timestampMicros);
          recorder.record(frameConverter.convert(frame.image));
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

  /** Returns whether auto-recording is enabled per configuration. */
  public boolean isAutoRecordEnabled() {
    return config.isAutoRecord();
  }

  /** Returns whether task-bound auto-recording is enabled per configuration. */
  public boolean isTaskAutoRecordEnabled() {
    return config.isAutoRecordTaskSessions();
  }

  /** Returns the current recording ID if recording, or null otherwise. */
  public String getCurrentRecordingId() {
    return recording.get() ? currentRecordingId : null;
  }

  /** Returns the trigger source for the active recording, or null if not recording. */
  public RecordingTriggerSource getTriggerSource() {
    return recording.get() ? currentTriggerSource : null;
  }

  /** Returns the video codec used for the current recording, or null if not recording. */
  public String getCurrentVideoCodec() {
    return recording.get() ? currentVideoCodec : null;
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
      currentTriggerSource = null;
      recordingStartTime = 0;
      frameCount.set(0);
      droppedFrameCount.set(0);
      duplicatedFrameCount.set(0);
      queueHighWaterMark = 0;
      encodedFrameCount = 0;
      framesRecorded = 0;
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
    currentVideoCodec = null;
    currentTriggerSource = null;
    recordingStartTime = 0;
    frameCount.set(0);
    droppedFrameCount.set(0);
    duplicatedFrameCount.set(0);
    queueHighWaterMark = 0;
    encodedFrameCount = 0;
    framesRecorded = 0;
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
        currentVideoCodec = codecName;
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
    candidate.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
    candidate.setOption("movflags", "+faststart");

    // Codec-specific quality settings
    if ("libx264".equals(codecName)) {
      // Use CRF for quality-based VBR (lower = better quality, 18-28 typical range)
      // quality 70 -> CRF 14, quality 100 -> CRF 8
      int crfValue = Math.max(0, 28 - (config.getVideoQuality() / 5));
      candidate.setVideoOption("crf", String.valueOf(crfValue));
      candidate.setVideoOption("preset", config.getEncodingPreset());
      // Removed tune=zerolatency (disables B-frames, inappropriate for file recording)
      candidate.setVideoOption("profile", "high");
    } else {
      // Fallback codecs: use bitrate-based encoding (80kbps per quality point for reasonable
      // output)
      candidate.setVideoBitrate((int) (config.getVideoQuality() * 80000));
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

  /**
   * Inner class to store a frame with its capture timestamp.
   *
   * <p>This enables accurate video timing regardless of encoding speed or frame drops.
   */
  private static class TimestampedFrame {
    final BufferedImage image;
    final long captureNanoTime;

    TimestampedFrame(BufferedImage image, long captureNanoTime) {
      this.image = image;
      this.captureNanoTime = captureNanoTime;
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
    private final long droppedFrames;
    private final String videoCodec;

    public RecordingMetadata(
        String id,
        String filePath,
        long startTime,
        long duration,
        long frameCount,
        int fps,
        long droppedFrames,
        String videoCodec) {
      this.id = id;
      this.filePath = filePath;
      this.startTime = startTime;
      this.duration = duration;
      this.frameCount = frameCount;
      this.fps = fps;
      this.droppedFrames = droppedFrames;
      this.videoCodec = videoCodec;
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

    public long getDroppedFrames() {
      return droppedFrames;
    }

    public String getVideoCodec() {
      return videoCodec;
    }
  }
}

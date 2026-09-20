package com.chromascape.web.recording.service;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameListener;
import com.chromascape.utils.core.screen.FrameSourceBinding;
import com.chromascape.web.recording.capture.WindowFrameCapture;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.exception.FrameCaptureException;
import com.chromascape.web.recording.exception.RecordingAlreadyInProgressException;
import com.chromascape.web.recording.exception.RecordingDisabledException;
import com.chromascape.web.recording.exception.RecordingException;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import jakarta.annotation.PreDestroy;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
  private final WindowFrameCapture windowFrameCapture;
  private final ExecutorService encoderThread;
  private final AtomicBoolean recording = new AtomicBoolean(false);
  private final AtomicBoolean recordingStarting = new AtomicBoolean(false);
  private final AtomicLong frameCount = new AtomicLong(0);
  private final AtomicLong droppedFrameCount = new AtomicLong(0);
  private final AtomicLong duplicatedFrameCount = new AtomicLong(0);
  private final AtomicLong scaledFrameCount = new AtomicLong(0);
  private final AtomicLong dimensionChangeCount = new AtomicLong(0);
  private final Set<String> observedSizePairs = ConcurrentHashMap.newKeySet();
  private final AtomicLong sourceMismatchFrameCount = new AtomicLong(0);
  private volatile int queueHighWaterMark = 0;
  private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();

  // Frame buffer for producer-consumer pattern
  private static final int FRAME_BUFFER_SIZE = 300; // ~10 seconds at 30 FPS
  private static final long MAX_BUFFERED_BYTES = 192L * 1024 * 1024;
  private final BoundedFrameBuffer<TimestampedFrame> frameBuffer =
      new BoundedFrameBuffer<>(FRAME_BUFFER_SIZE, MAX_BUFFERED_BYTES, frame -> frame.bytes);
  private volatile boolean encoderRunning = false;
  private long encodedFrameCount = 0; // Track encoded frames for timestamp calculation
  private volatile long encodedMediaMicros = 0; // Last encoded media timestamp (real elapsed time)

  private FFmpegFrameRecorder recorder;
  private File currentRecordingFile;
  private java.util.concurrent.Future<?> encoderFuture;
  private volatile long initialFrameCaptureNanos;
  private final java.util.List<MeasuredVideoIndex.Submission> frameTimings =
      new java.util.ArrayList<>();
  private final java.util.Set<String> timingQualityCodes = new java.util.LinkedHashSet<>();
  private long lastMediaSlot = -1;

  /**
   * SC-2150 per-frame capture-time sidecar of the current recording, or {@code null} when disabled
   * or not creatable. Written only where a frame is actually encoded, with its real media time.
   */
  private RecordingFrameTimingWriter frameTiming;
  private long recordingStartTime;
  private volatile long recordingStartTimeNano; // Store nano time at start
  private String currentRecordingId;
  private volatile RecordingTriggerSource currentTriggerSource;
  private String currentVideoCodec; // Track which codec was successfully used
  private int initialWidth;
  private int initialHeight;
  private volatile FrameSourceBinding recordingSourceBinding;
  private long framesRecorded = 0; // Track total frames recorded for metrics
  private static final String[] VIDEO_CODEC_PREFERENCES =
      new String[] {"libx264", "libopenh264", "mpeg4"};

  /** Creates a recording service over the shared, source-fenced frame pipeline. */
  @Autowired
  public RecordingService(
      RecordingConfig config,
      UnifiedCaptureConfig unifiedConfig,
      UnifiedFrameCaptureService unifiedCaptureService,
      WindowFrameCapture windowFrameCapture) {
    this.config = config;
    this.unifiedConfig = unifiedConfig;
    this.unifiedCaptureService = unifiedCaptureService;
    this.windowFrameCapture = windowFrameCapture;

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
  public synchronized String startRecording(RecordingTriggerSource triggerSource) {
    return startRecordingWithId(null, triggerSource, null, null);
  }

  /**
   * Starts a recording bound to a caller-supplied, human-approved recording ID (SC-1498).
   *
   * <p>The recording ID is already part of the approved execution envelope, so this service must
   * never generate a replacement ID. The call is idempotent only when the exact same recording is
   * already active; any different active recording is rejected before any state changes.
   *
   * @param expectedRecordingId the preapproved recording ID to bind
   * @param trigger what initiated this recording
   * @param accountId owning account identity for the bootstrap session row (may be {@code null})
   * @param taskName task name for the bootstrap session row (may be {@code null})
   * @return the exact start timestamp and output path of the active recording
   * @throws IllegalArgumentException when the expected recording ID is blank or malformed
   * @throws RecordingAlreadyInProgressException when a different process-wide recording is active
   */
  public synchronized RecordingStart startRecording(
      String expectedRecordingId,
      RecordingTriggerSource trigger,
      String accountId,
      String taskName) {
    if (expectedRecordingId == null
        || expectedRecordingId.isBlank()
        || !expectedRecordingId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")) {
      throw new IllegalArgumentException(
          "A valid preapproved recordingId is required (got " + expectedRecordingId + ")");
    }
    if (recording.get()) {
      if (expectedRecordingId.equals(currentRecordingId)) {
        // Idempotent restart of the exact same recording: report its live start facts.
        return new RecordingStart(
            currentRecordingId,
            currentRecordingFile.getAbsolutePath(),
            recordingStartTime,
            trigger == null ? currentTriggerSource : trigger,
            accountId,
            taskName,
            true);
      }
      logger.warn(
          "Requested bootstrap recording {} but {} is already active",
          expectedRecordingId,
          currentRecordingId);
      throw new RecordingAlreadyInProgressException(currentRecordingId);
    }
    String startedId = startRecordingWithId(expectedRecordingId, trigger, accountId, taskName);
    return new RecordingStart(
        startedId,
        currentRecordingFile.getAbsolutePath(),
        recordingStartTime,
        trigger == null ? currentTriggerSource : trigger,
        accountId,
        taskName,
        false);
  }

  private synchronized String startRecordingWithId(
      String expectedRecordingId,
      RecordingTriggerSource triggerSource,
      String accountId,
      String taskName) {
    if (!config.isEnabled()) {
      logger.warn("Recording is disabled");
      throw new RecordingDisabledException();
    }

    if (!recording.get() && recorder != null) {
      // A bounded stop may time out while the encoder drains. Once its owner has
      // terminated, release the leftover native handle without deleting the media.
      // Never close a recorder that may still be inside record().
      if (encoderFuture != null && !encoderFuture.isDone()) {
        throw new RecordingException(
            "Previous recording was not finalized",
            "The previous recording is still finishing. Wait before starting another recording.");
      }
      try {
        recorder.stop();
        recorder.release();
        recorder = null;
        logger.warn(
            "Released previous recorder after its encoder finished: {}. "
                + "Existing media retained; calibrated finalization is not asserted.",
            currentRecordingId);
      } catch (Exception e) {
        throw new RecordingException(
            "Previous recording was not finalized",
            "The previous recorder could not be released. Restart the recording service.");
      }
    }

    if (recording.get()) {
      logger.warn("Recording is already in progress: {}", currentRecordingId);
      throw new RecordingAlreadyInProgressException(currentRecordingId);
    }

    if (!recordingStarting.compareAndSet(false, true)) {
      logger.warn("Recording is already starting: {}", currentRecordingId);
      throw new RecordingAlreadyInProgressException(currentRecordingId);
    }

    currentTriggerSource = triggerSource;

    try {
      // SC-1498: when a preapproved recording id is bound it is used verbatim. The recording id
      // is already a member of the human-approved execution envelope, so this service must never
      // substitute a locally generated id for it.
      if (expectedRecordingId != null && !expectedRecordingId.isBlank()) {
        currentRecordingId = expectedRecordingId;
      } else {
        // Generate recording ID using UUID (decouple from timestamp)
        currentRecordingId = "recording_" + UUID.randomUUID().toString().substring(0, 8);
      }
      String filename = currentRecordingId + ".mp4";
      currentRecordingFile = Paths.get(config.getOutputDirectory(), filename).toFile();

      // Wait (bounded) for RuneLite's canvas to expose capturable dimensions. The SunAwtCanvas
      // may exist while its Win32 client rectangle is still 0x0, and the canvas can pass through
      // several transient startup sizes, so the acquisition is gated on a stable, plausibly-sized
      // canvas rather than the first frame that happens to arrive.
      BufferedImage firstFrame = waitForStableInitialFrame();

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
      duplicatedFrameCount.set(0);
      scaledFrameCount.set(0);
      dimensionChangeCount.set(0);
      observedSizePairs.clear();
      sourceMismatchFrameCount.set(0);
      framesRecorded = 0;

      // CAPTURE START TIME HERE - immediately before first frame queued
      // This ensures minimal drift between video timeline and log timestamps
      recordingStartTime = System.currentTimeMillis();
      recordingStartTimeNano = System.nanoTime();

      frameTimings.clear();
      timingQualityCodes.clear();
      lastMediaSlot = -1;
      // Keep the actual first-image capture time as well as its presentation origin.
      if (!frameBuffer
          .offer(new TimestampedFrame(normalizedFirstFrame, initialFrameCaptureNanos))
          .accepted()) {
        throw new RecordingException(
            "FRAME_MEMORY_BUDGET_EXCEEDED",
            "The captured image exceeds the recording memory budget. Reduce the canvas size.");
      }
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
      encoderFuture = encoderThread.submit(this::encoderLoop);

      // Register with unified capture service to receive frames
      if (unifiedConfig.isEnabled()) {
        unifiedCaptureService.registerSubscriber(this);
        logger.info("Registered with unified capture service for recording");
      } else {
        logger.warn(
            "Unified capture is disabled, recording will not receive frames (legacy mode not"
                + " implemented)");
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
          "An unexpected error occurred while starting the recording. Please try again or check the"
              + " logs.");
    } finally {
      recordingStarting.set(false);
    }
  }

  /**
   * Polls {@link WindowFrameCapture} until a valid, stable, plausibly-sized canvas is observed or
   * the configured readiness deadline expires.
   *
   * <p>RuneLite's game canvas passes through several transient sizes while the client boots (for
   * example 200x274 while loading, then intermediate sizes). Locking the video encoder onto the
   * first transient size would make every later frame fail the dimension check, so a size is only
   * accepted once it has been stable for {@code recording.stability-window-ms} and is at least
   * {@code recording.min-capture-width} x {@code recording.min-capture-height}. When the deadline
   * expires with at least one valid frame observed, the most recent frame is used so recording
   * still starts (the encoder scales later frames if the canvas keeps changing).
   *
   * <p>Only one thread may perform readiness capture because {@code recordingStarting} is set
   * before this method runs. On interruption the interrupt flag is restored and startup aborts.
   *
   * @return a valid frame, preferring one with a stable, sufficiently large canvas
   * @throws FrameCaptureException if no valid frame arrives before the deadline or the thread is
   *     interrupted
   */
  private BufferedImage waitForStableInitialFrame() {
    long timeoutMs = config.getInitialFrameTimeoutMs();
    long retryIntervalMs = config.getInitialFrameRetryIntervalMs();
    long stabilityWindowMs = config.getStabilityWindowMs();
    long minWidth = config.getMinCaptureWidth();
    long minHeight = config.getMinCaptureHeight();
    long startNanos = System.nanoTime();
    long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    String lastError = null;
    boolean firstFailureLogged = false;
    long lastProgressLogNanos = 0;
    int attempts = 0;

    BufferedImage candidate = null;
    int candidateWidth = -1;
    int candidateHeight = -1;
    long candidateFirstSeenNanos = 0;
    String lastObservedDimensions = null;

    while (true) {
      attempts++;
      String captureError = null;
      try {
        FrameEvent capturedEvent = windowFrameCapture.captureEvent();
        BufferedImage captured = capturedEvent != null ? capturedEvent.getFrame() : null;
        if (capturedEvent == null) {
          // Mockito/custom implementations written against the pre-SC-1666 interface can return
          // null from the new default method; retain their legacy capture path.
          captured = windowFrameCapture.capture();
        }
        if (captured == null) {
          captureError = "capture returned no frame";
        } else if (captured.getWidth() <= 0 || captured.getHeight() <= 0) {
          captureError =
              String.format("invalid dimensions %dx%d", captured.getWidth(), captured.getHeight());
        } else {
          BufferedImage normalized = normalizeFrameDimensions(captured);
          int width = normalized.getWidth();
          int height = normalized.getHeight();
          String dimensions = width + "x" + height;

          if (!dimensions.equals(lastObservedDimensions)) {
            if (lastObservedDimensions == null) {
              logger.info("Initial canvas dimensions observed: {}", dimensions);
            } else {
              logger.info(
                  "Canvas dimensions changed: {} -> {}", lastObservedDimensions, dimensions);
            }
            lastObservedDimensions = dimensions;
          }

          if (width != candidateWidth || height != candidateHeight) {
            candidateWidth = width;
            candidateHeight = height;
            candidateFirstSeenNanos = System.nanoTime();
          }
          candidate = normalized;
          initialFrameCaptureNanos =
              capturedEvent == null ? 0 : capturedEvent.getCaptureTimeNanos();

          boolean largeEnough = width >= minWidth && height >= minHeight;
          long stableNanos = System.nanoTime() - candidateFirstSeenNanos;
          if (largeEnough && stableNanos >= TimeUnit.MILLISECONDS.toNanos(stabilityWindowMs)) {
            recordingSourceBinding =
                capturedEvent != null ? capturedEvent.getSourceBinding() : null;
            long waitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.info(
                "Stable canvas dimensions {}x{} selected after {} attempts in {} ms "
                    + "(unchanged for {} ms)",
                width,
                height,
                attempts,
                waitMs,
                TimeUnit.NANOSECONDS.toMillis(stableNanos));
            return candidate;
          }
        }
      } catch (RuntimeException e) {
        captureError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      }

      if (captureError != null) {
        lastError = captureError;
        if (!firstFailureLogged) {
          firstFailureLogged = true;
          logger.warn("Failed to capture initial frame (attempt {}): {}", attempts, lastError);
        }
      }

      long nowNanos = System.nanoTime();
      long remainingNanos = deadlineNanos - nowNanos;
      if (remainingNanos <= 0) {
        break;
      }

      if (nowNanos - lastProgressLogNanos > TimeUnit.SECONDS.toNanos(2)) {
        lastProgressLogNanos = nowNanos;
        logger.info(
            "Waiting for a stable RuneLite canvas size (attempt {}, elapsed {} ms, last size"
                + " {})...",
            attempts,
            TimeUnit.NANOSECONDS.toMillis(nowNanos - startNanos),
            lastObservedDimensions != null ? lastObservedDimensions : "unknown");
      }

      long sleepMs = Math.min(retryIntervalMs, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
      if (sleepMs <= 0) {
        break;
      }
      try {
        Thread.sleep(sleepMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        logger.warn("Recording start interrupted while waiting for initial frame");
        throw new FrameCaptureException(attempts, timeoutMs, elapsedMs, lastError, true);
      }
    }

    if (candidate != null) {
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      logger.warn(
          "No stable canvas size reached within {} ms (last size {}); using the most recent frame "
              + "so recording can start. Later frames will be scaled into {}x{}.",
          elapsedMs,
          lastObservedDimensions != null ? lastObservedDimensions : "unknown",
          candidateWidth,
          candidateHeight);
      return candidate;
    }

    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    logger.error(
        "Timed out waiting for initial frame after {} attempts over {} ms: {}",
        attempts,
        elapsedMs,
        lastError);
    throw new FrameCaptureException(attempts, timeoutMs, elapsedMs, lastError, false);
  }

  /**
   * Stops the current recording session.
   *
   * <p>Finalizes the video file and returns recording metadata.
   *
   * @return Recording metadata including duration and file path
   */
  public synchronized RecordingMetadata stopRecording() {
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

      // Queue emptiness does not prove the final recorder call finished. Join the owner before
      // closing FFmpeg.
      if (encoderFuture != null) {
        encoderFuture.get(15, TimeUnit.SECONDS);
      }

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
      try {
        if (droppedFrameCount.get() > 0) {
          timingQualityCodes.add("FRAME_BUFFER_OVERFLOW");
        }
        MeasuredVideoIndex.Index index =
            MeasuredVideoIndex.finalizeIndex(
                currentRecordingFile.toPath(),
                currentRecordingId,
                java.util.List.copyOf(frameTimings),
                java.util.List.copyOf(timingQualityCodes));
        frames = index.frames().size();
      } catch (Exception indexFailure) {
        logger.warn(
            "Recording finalized without calibrated video index: {}", indexFailure.getMessage());
      }
      long dropped = droppedFrameCount.get();
      long duplicated = duplicatedFrameCount.get();
      long scaled = scaledFrameCount.get();

      // Calculate actual FPS
      double actualCaptureFps = duration > 0 ? (frames * 1000.0) / duration : 0.0;
      double encodedMediaSec = encodedMediaMicros / 1_000_000.0;
      double droppedPercent = frames + dropped > 0 ? dropped * 100.0 / (frames + dropped) : 0.0;
      double duplicatedPercent =
          encodedFrameCount > 0 ? duplicated * 100.0 / encodedFrameCount : 0.0;

      logger.info(
          "Recording saved: {}\n"
              + "Duration: {} ms (encoded media ~{} sec, {} frames @ {} FPS)\n"
              + "Frames captured: {}\n"
              + "Frames scaled (canvas size changes): {} ({} distinct size transitions)\n"
              + "Frames dropped (buffer full): {} ({}%)\n"
              + "Frames duplicated (cadence fill): {} ({}%)\n"
              + "Frames encoded: {}\n"
              + "Queue high-water mark: {}\n"
              + "Actual capture FPS: {}\n"
              + "Configured FPS: {}",
          currentRecordingFile.getName(),
          duration,
          String.format("%.1f", encodedMediaSec),
          encodedFrameCount,
          unifiedConfig.getFps(),
          frames,
          scaled,
          dimensionChangeCount.get(),
          dropped,
          String.format("%.1f", droppedPercent),
          duplicated,
          String.format("%.1f", duplicatedPercent),
          encodedFrameCount,
          queueHighWaterMark,
          String.format("%.2f", actualCaptureFps),
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

    } catch (java.util.concurrent.TimeoutException e) {
      // The encoder still owns the native recorder. Closing it here would race a record call.
      logger.error(
          "Encoder did not finish; media remains unfinalized and no calibrated index is published");
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.error("Recording stop interrupted: {}", e.getMessage());
      if (encoderFuture == null || encoderFuture.isDone()) {
        cleanup();
      }
      return null;
    } catch (Exception e) {
      logger.error("Failed to stop recording: {}", e.getMessage(), e);
      cleanup();
      return null;
    }
  }

  /**
   * Stops a recording only when it is still the recording identified by {@code recordingId}. This
   * prevents rollback for one session from stopping a newer process-wide recording.
   */
  public synchronized boolean stopRecordingIfCurrent(String recordingId) {
    if (recordingId == null || recordingId.isBlank() || !recording.get()) {
      return false;
    }
    if (!recordingId.equals(currentRecordingId)) {
      return false;
    }
    stopRecording();
    return true;
  }

  /**
   * Atomic exact-stop contract for the SC-1498 bootstrap finalization (SC-1498).
   *
   * <p>Stops the process-wide recording only when it is still the recording identified by {@code
   * expectedRecordingId}, and returns that recording's final metadata in the same synchronized
   * section so a concurrent start of a different recording can never be observed between the
   * identity check and the stop. When a different recording is active, or none is, nothing is
   * stopped and {@code null} is returned — the caller must treat that as a reconciliation case,
   * never silently accept a foreign recording's bytes.
   *
   * @param expectedRecordingId the preapproved recording id that must still be active
   * @return the stopped recording's metadata, or {@code null} when the exact recording is not the
   *     active one
   * @throws IllegalArgumentException when the expected recording id is blank
   */
  public synchronized RecordingMetadata stopRecordingIfCurrentWithMetadata(
      String expectedRecordingId) {
    if (expectedRecordingId == null || expectedRecordingId.isBlank()) {
      throw new IllegalArgumentException("A non-blank expectedRecordingId is required");
    }
    if (!recording.get() || !expectedRecordingId.equals(currentRecordingId)) {
      logger.warn(
          "Refusing to stop recording {}: active recording is {}",
          expectedRecordingId,
          recording.get() ? currentRecordingId : "<none>");
      return null;
    }
    return stopRecording();
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
    metrics.put("frameBufferBytes", frameBuffer.bytes());
    metrics.put("frameBufferCapacity", frameBuffer.remainingCapacity());
    metrics.put("droppedFrames", droppedFrameCount.get());
    metrics.put("duplicatedFrames", duplicatedFrameCount.get());
    metrics.put("scaledFrames", scaledFrameCount.get());
    metrics.put("dimensionChanges", dimensionChangeCount.get());
    metrics.put("sourceMismatchFrames", sourceMismatchFrameCount.get());
    metrics.put(
        "sourceBinding",
        recordingSourceBinding != null ? recordingSourceBinding.toDisplayString() : null);
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
    if (!isAutoStartAllowed(triggerSource)) {
      logger.info(
          "Auto-recording is disabled for source={} (autoRecord={}, autoRecordTaskSessions={})",
          triggerSource,
          config.isAutoRecord(),
          config.isAutoRecordTaskSessions());
      return null;
    }

    logger.info("Auto-starting recording for script: {} (source: {})", scriptName, triggerSource);

    return startRecording(triggerSource);
  }

  /**
   * Returns whether auto-start is permitted for the given trigger source, considering the
   * per-source config flags. The global {@code enabled} flag must be checked separately.
   *
   * <p>This honors the documented {@code recording.auto-record-task-sessions} flag so that
   * task-bound auto sessions (TASK_AUTO) start even when generic {@code recording.auto-record} is
   * off, matching the per-source gating already used by {@code DebugSessionService}.
   *
   * @param triggerSource what initiated this auto-start
   * @return true if this source is permitted to auto-start
   */
  boolean isAutoStartAllowed(RecordingTriggerSource triggerSource) {
    return switch (triggerSource) {
      case TASK_AUTO -> config.isAutoRecordTaskSessions();
      case MICRO6_AUTO -> config.isAutoRecord();
      case MANUAL -> true;
        // The bootstrap recording is started explicitly by the SC-1498 coordinator through the
        // exact-ID start contract, never through auto-start; permit it here for completeness.
      case BOOTSTRAP_RECORDING -> true;
    };
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

    FrameSourceBinding expectedSource = recordingSourceBinding;
    if (expectedSource != null && !expectedSource.equals(event.getSourceBinding())) {
      long rejected = sourceMismatchFrameCount.incrementAndGet();
      if (rejected == 1 || rejected % 30 == 0) {
        logger.warn(
            "Dropped {} frame(s) from a source other than the recording start target", rejected);
      }
      return;
    }

    try {
      BufferedImage capturedImage = event.getFrame();

      if (capturedImage != null) {
        capturedImage = normalizeFrameDimensions(capturedImage);

        // The encoder's output size is fixed once the recorder starts. If the game canvas
        // changes size mid-recording (RuneLite startup settling, operator resizing the window,
        // fixed/resizable switches), scale the frame into the recorder canvas instead of
        // dropping it: a scaled frame keeps the video timeline usable, while a dropped frame
        // silently degrades the recording into a static image.
        int frameWidth = capturedImage.getWidth();
        int frameHeight = capturedImage.getHeight();

        if (frameWidth != initialWidth || frameHeight != initialHeight) {
          if (initialWidth <= 0 || initialHeight <= 0) {
            logger.warn(
                "Recorder dimensions unavailable ({}x{}); dropping {}x{} frame.",
                initialWidth,
                initialHeight,
                frameWidth,
                frameHeight);
            return;
          }

          capturedImage = fitToRecorderSize(capturedImage, initialWidth, initialHeight);
          long scaled = scaledFrameCount.incrementAndGet();
          String sizePair =
              frameWidth + "x" + frameHeight + "->" + initialWidth + "x" + initialHeight;
          if (observedSizePairs.add(sizePair)) {
            dimensionChangeCount.incrementAndGet();
            logger.warn(
                "Canvas size changed ({}x{} -> {}x{}) while recording; scaling frames into the "
                    + "{}x{} recorder canvas.",
                frameWidth,
                frameHeight,
                initialWidth,
                initialHeight,
                initialWidth,
                initialHeight);
          } else if (scaled % 300 == 1) {
            logger.warn(
                "Scaled {} frames so far into the {}x{} recorder canvas (source canvas {}x{}).",
                scaled,
                initialWidth,
                initialHeight,
                frameWidth,
                frameHeight);
          }
        }

        // Store with capture timestamp
        TimestampedFrame timestampedFrame =
            new TimestampedFrame(capturedImage, event.getCaptureTimeNanos());

        // A count limit alone can retain gigabytes at large canvas sizes.
        var admission = frameBuffer.offer(timestampedFrame);
        if (admission.dropped() > 0) {
          long dropped = droppedFrameCount.addAndGet(admission.dropped());
          if (dropped % 30 < admission.dropped()) {
            logger.warn(
                "Frame budget reached; dropped {} frames, retained {} bytes",
                dropped,
                frameBuffer.bytes());
          }
        }
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

  private long encodeTimedFrame(TimestampedFrame frame, boolean duplicated) throws Exception {
    long now = System.nanoTime();
    if (Math.abs(
            System.currentTimeMillis()
                - (recordingStartTime + (now - recordingStartTimeNano) / 1_000_000.0))
        > 20) {
      timingQualityCodes.add("FRAME_SERVER_CLOCK_RESET");
    }
    long captured = frame.captureNanoTime;
    if (captured <= 0
        || captured > now + TimeUnit.MILLISECONDS.toNanos(20)
        || now - captured > TimeUnit.MINUTES.toNanos(10)) {
      timingQualityCodes.add("FRAME_CAPTURE_CLOCK_INVALID");
      captured = now; // Video remains useful; calibration is explicitly unavailable.
    }
    long presented = duplicated ? now : Math.max(recordingStartTimeNano, captured);
    long relativeMicros =
        Math.max(0, TimeUnit.NANOSECONDS.toMicros(presented - recordingStartTimeNano));
    long slot = Math.round(relativeMicros * (double) unifiedConfig.getFps() / 1_000_000.0);
    if (slot <= lastMediaSlot) {
      return encodedMediaMicros;
    }
    long mediaMicros = Math.round(slot * 1_000_000.0 / unifiedConfig.getFps());
    recorder.setTimestamp(mediaMicros);
    recorder.record(frameConverter.convert(frame.image));
    if (frameTiming != null) {
      frameTiming.frame(encodedFrameCount, captured, duplicated, mediaMicros);
    }
    lastMediaSlot = slot;
    encodedMediaMicros = mediaMicros;
    encodedFrameCount++;
    frameTimings.add(
        new MeasuredVideoIndex.Submission(
            mediaMicros,
            recordingStartTime + (captured - recordingStartTimeNano) / 1_000_000.0,
            recordingStartTime + (presented - recordingStartTimeNano) / 1_000_000.0,
            Math.max(0, (now - captured) / 1_000_000.0),
            Math.max(0, (presented - captured) / 1_000_000.0)));
    return mediaMicros;
  }

  /** Encoder loop that consumes frames from the buffer (consumer). */
  private void encoderLoop() {
    logger.info("Encoder thread started");
    encodedFrameCount = 0;
    encodedMediaMicros = 0;
    duplicatedFrameCount.set(0);
    queueHighWaterMark = 0;
    TimestampedFrame lastFrame = null;
    final int targetFps = unifiedConfig.getFps();
    // Poll at the frame interval so the last-frame fill can actually sustain the target
    // cadence. The old 100ms timeout capped the fill at ~10 fps, so a stalled capture
    // stream produced media that advanced at only ~1/3 of wall time.
    final long pollTimeoutMs = Math.max(1L, 1000L / targetFps);
    long lastTimestampMicros = -1L;
    frameTiming =
        config.isFrameTimingSidecar() && currentRecordingFile != null
            ? RecordingFrameTimingWriter.open(
                currentRecordingFile.toPath(),
                String.valueOf(currentRecordingId),
                recordingStartTime,
                recordingStartTimeNano,
                targetFps)
            : null;

    while (encoderRunning || !frameBuffer.isEmpty()) {
      try {
        // Wait for frame with timeout
        TimestampedFrame timestampedFrame = frameBuffer.poll(pollTimeoutMs, TimeUnit.MILLISECONDS);

        boolean duplicated = false;
        if (timestampedFrame != null) {
          lastFrame = timestampedFrame;
        } else if (encoderRunning && lastFrame != null) {
          // No frame received within poll window — duplicate last frame to maintain cadence
          timestampedFrame = lastFrame;
          duplicated = true;
          duplicatedFrameCount.incrementAndGet();
        }

        if (timestampedFrame != null && recorder != null) {
          // Backlogged images retain capture-relative media time. Cadence fill retains stale-image
          // age.
          lastTimestampMicros = encodeTimedFrame(timestampedFrame, duplicated);

          // Track queue high-water mark
          int queueDepth = frameBuffer.size();
          if (queueDepth > queueHighWaterMark) {
            queueHighWaterMark = queueDepth;
          }

          // Detect encoder lag
          if (queueDepth > FRAME_BUFFER_SIZE * 0.8 && encodedFrameCount % 30 == 0) {
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
        timingQualityCodes.add("FRAME_ENCODING_FAILURE");
        logger.warn("Failed to encode frame: {}", e.getMessage());
      }
    }

    encodedMediaMicros = Math.max(0L, lastTimestampMicros);
    if (frameTiming != null) {
      frameTiming.close();
      frameTiming = null;
    }
    logger.info(
        "Encoder thread finished (encoded {} frames, {} ms media)",
        encodedFrameCount,
        encodedMediaMicros / 1000L);
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
      scaledFrameCount.set(0);
      dimensionChangeCount.set(0);
      observedSizePairs.clear();
      queueHighWaterMark = 0;
      encodedFrameCount = 0;
      encodedMediaMicros = 0;
      framesRecorded = 0;
      recordingSourceBinding = null;
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

    // Remove any zero-byte/partial MP4 left behind by a failed start
    if (currentRecordingFile != null
        && currentRecordingFile.exists()
        && !currentRecordingFile.delete()) {
      logger.warn("Failed to delete partial recording file: {}", currentRecordingFile);
    }

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
    scaledFrameCount.set(0);
    dimensionChangeCount.set(0);
    observedSizePairs.clear();
    queueHighWaterMark = 0;
    encodedFrameCount = 0;
    encodedMediaMicros = 0;
    framesRecorded = 0;
    initialWidth = 0;
    initialHeight = 0;
    recordingSourceBinding = null;
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

    logger.debug(
        "Captured frame dimensions {}x{} are not even; trimming to {}x{} for recorder"
            + " compatibility.",
        width,
        height,
        evenWidth,
        evenHeight);
    return frame.getSubimage(0, 0, evenWidth, evenHeight);
  }

  /**
   * Scales a frame into the recorder's fixed output dimensions, preserving the source aspect ratio
   * and centering the result on a black background (letterbox/pillarbox).
   *
   * <p>Used when the captured canvas size no longer matches the size the encoder was opened with.
   * Encoding a scaled frame keeps the video timeline continuous and playable, unlike dropping the
   * frame.
   *
   * @param source frame captured at the current canvas size
   * @param targetWidth recorder width
   * @param targetHeight recorder height
   * @return a new image of exactly {@code targetWidth} x {@code targetHeight}
   */
  static BufferedImage fitToRecorderSize(BufferedImage source, int targetWidth, int targetHeight) {
    BufferedImage target =
        new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_3BYTE_BGR);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setColor(Color.BLACK);
      graphics.fillRect(0, 0, targetWidth, targetHeight);

      double scale =
          Math.min(
              targetWidth / (double) source.getWidth(), targetHeight / (double) source.getHeight());
      int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
      int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
      int drawX = (targetWidth - drawWidth) / 2;
      int drawY = (targetHeight - drawHeight) / 2;
      graphics.drawImage(source, drawX, drawY, drawWidth, drawHeight, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  /** Number of frames scaled into the recorder canvas because the capture size changed. */
  public long getScaledFrameCount() {
    return scaledFrameCount.get();
  }

  /** Number of distinct canvas-size transitions observed during the current/last recording. */
  public long getDimensionChangeCount() {
    return dimensionChangeCount.get();
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
    final long bytes;

    TimestampedFrame(BufferedImage image, long captureNanoTime) {
      this.image = image;
      this.captureNanoTime = captureNanoTime;
      // A subimage retains its parent's entire data buffer, not only the visible rectangle.
      var data = image.getRaster().getDataBuffer();
      this.bytes =
          Math.multiplyExact(
              Math.multiplyExact((long) data.getSize(), data.getNumBanks()),
              (java.awt.image.DataBuffer.getDataTypeSize(data.getDataType()) + 7L) / 8L);
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

    /** Creates immutable metadata for a finalized recording. */
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

  /**
   * Start facts of the bootstrap recording session (SC-1498).
   *
   * <p>Carries the exact preapproved recording id, its start timestamp and output path so the
   * coordinator can persist a STARTED projection whose identity matches the approved envelope
   * without any local id substitution.
   */
  public static class RecordingStart {
    private final String id;
    private final String filePath;
    private final long startTime;
    private final RecordingTriggerSource triggerSource;
    private final String accountId;
    private final String taskName;
    private final boolean reconciled;

    public RecordingStart(
        String id,
        String filePath,
        long startTime,
        RecordingTriggerSource triggerSource,
        String accountId,
        String taskName,
        boolean reconciled) {
      this.id = id;
      this.filePath = filePath;
      this.startTime = startTime;
      this.triggerSource = triggerSource;
      this.accountId = accountId;
      this.taskName = taskName;
      this.reconciled = reconciled;
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

    public RecordingTriggerSource getTriggerSource() {
      return triggerSource;
    }

    public String getAccountId() {
      return accountId;
    }

    public String getTaskName() {
      return taskName;
    }

    /** True when the exact recording was already active and its live facts were replayed. */
    public boolean isReconciled() {
      return reconciled;
    }
  }
}

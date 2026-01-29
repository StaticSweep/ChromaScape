package com.chromascape.web.viewport.service;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameListener;
import com.chromascape.utils.core.screen.viewport.ViewportManager;
import com.chromascape.web.viewport.config.ScreenCaptureConfig;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for continuously capturing the game screen and updating the viewport.
 *
 * <p>This service subscribes to the {@link UnifiedFrameCaptureService} to receive captured frames
 * and forwards them to the viewport. This ensures the viewport always displays the current game
 * state while avoiding duplicate screen captures.
 *
 * <p>The service implements {@link FrameListener} to receive frames from the unified capture
 * service.
 */
@Service
public class ScreenCaptureService implements FrameListener {

  private static final Logger logger = LogManager.getLogger(ScreenCaptureService.class);

  private final ScreenCaptureConfig config;
  private final UnifiedCaptureConfig unifiedConfig;
  private final UnifiedFrameCaptureService unifiedCaptureService;
  private final AtomicBoolean paused = new AtomicBoolean(false);
  private final AtomicBoolean registered = new AtomicBoolean(false);
  private final AtomicLong latestCaptureTimeNanos = new AtomicLong(0);

  @Autowired
  public ScreenCaptureService(
      ScreenCaptureConfig config,
      UnifiedCaptureConfig unifiedConfig,
      UnifiedFrameCaptureService unifiedCaptureService) {
    this.config = config;
    this.unifiedConfig = unifiedConfig;
    this.unifiedCaptureService = unifiedCaptureService;
  }

  /**
   * Starts the continuous screen capture service.
   *
   * <p>This method is automatically called after Spring bean construction via @PostConstruct.
   *
   * <p>Registers with the unified capture service to receive frames.
   */
  @PostConstruct
  public void start() {
    if (!config.isEnabled()) {
      logger.info("Screen capture is disabled");
      return;
    }

    if (registered.getAndSet(true)) {
      logger.warn("ScreenCaptureService is already registered");
      return;
    }

    if (unifiedConfig.isEnabled()) {
      logger.info(
          "Registering ScreenCaptureService with unified capture at {} FPS",
          unifiedConfig.getFps());
      unifiedCaptureService.registerSubscriber(this);
    } else {
      logger.warn(
          "Unified capture is disabled, falling back to legacy capture mode (not implemented)");
      // Fallback to legacy mode could be implemented here if needed
    }
  }

  /**
   * Stops the continuous screen capture service.
   *
   * <p>This method is automatically called when the Spring context is destroyed via @PreDestroy.
   */
  @PreDestroy
  public void stop() {
    if (!registered.getAndSet(false)) {
      return;
    }

    logger.info("Unregistering ScreenCaptureService from unified capture");
    unifiedCaptureService.unregisterSubscriber(this);
  }

  /**
   * Called by the unified capture service when a new frame is captured.
   *
   * <p>Converts the frame to OpenCV Mat and sends it to the viewport.
   */
  @Override
  public void onFrameCaptured(FrameEvent event) {
    long captureTime = event.getCaptureTimeNanos();
    long latestTime = latestCaptureTimeNanos.updateAndGet(prev -> Math.max(prev, captureTime));
    if (captureTime < latestTime) {
      return;
    }

    try {
      BufferedImage frame = event.getFrame();
      if (frame != null) {
        try (Mat mat = Java2DFrameUtils.toMat(frame)) {
          if (captureTime < latestCaptureTimeNanos.get()) {
            return;
          }
          ViewportManager.getInstance().updateState(mat);
        }
      }
    } catch (Exception e) {
      logger.debug("Failed to process frame for viewport: {}", e.getMessage());
    }
  }

  /**
   * Returns whether this listener is active and wants to receive frames.
   *
   * <p>Returns false when paused to skip frame delivery.
   */
  @Override
  public boolean isActive() {
    return !paused.get();
  }

  /**
   * Pauses the screen capture service.
   *
   * <p>The unified capture continues running, but this listener stops receiving frames.
   */
  public void pause() {
    paused.set(true);
    logger.info("Screen capture paused (frames will not be sent to viewport)");
  }

  /**
   * Resumes the screen capture service.
   *
   * <p>Resumes receiving frames from the unified capture service.
   */
  public void resume() {
    paused.set(false);
    logger.info("Screen capture resumed");
  }

  /**
   * Returns whether the service is currently paused.
   *
   * @return true if paused
   */
  public boolean isPaused() {
    return paused.get();
  }

  /**
   * Returns whether the service is currently registered with unified capture.
   *
   * @return true if registered
   */
  public boolean isRunning() {
    return registered.get();
  }
}

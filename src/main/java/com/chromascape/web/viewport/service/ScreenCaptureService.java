package com.chromascape.web.viewport.service;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameListener;
import com.chromascape.utils.core.screen.viewport.ViewportManager;
import com.chromascape.web.viewport.WebsocketViewport;
import com.chromascape.web.viewport.config.ScreenCaptureConfig;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.config.ViewportStreamConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
  private final ViewportStreamConfig viewportStreamConfig;
  private final UnifiedFrameCaptureService unifiedCaptureService;
  private final AtomicBoolean paused = new AtomicBoolean(false);
  private final AtomicBoolean registered = new AtomicBoolean(false);
  private final AtomicInteger activeViewportSessions = new AtomicInteger(0);
  private final AtomicLong latestCaptureTimeNanos = new AtomicLong(0);
  private final AtomicLong lastForwardedCaptureTimeNanos = new AtomicLong(0);
  private final AtomicLong framesReceived = new AtomicLong(0);
  private final AtomicLong framesForwarded = new AtomicLong(0);
  private final AtomicLong framesDroppedStale = new AtomicLong(0);
  private final AtomicLong framesSkippedThrottle = new AtomicLong(0);
  private final AtomicLong frameProcessingErrors = new AtomicLong(0);
  private final AtomicLong streamActivations = new AtomicLong(0);
  private final AtomicLong streamDeactivations = new AtomicLong(0);

  @Autowired
  public ScreenCaptureService(
      ScreenCaptureConfig config,
      UnifiedCaptureConfig unifiedConfig,
      ViewportStreamConfig viewportStreamConfig,
      UnifiedFrameCaptureService unifiedCaptureService) {
    this.config = config;
    this.unifiedConfig = unifiedConfig;
    this.viewportStreamConfig = viewportStreamConfig;
    this.unifiedCaptureService = unifiedCaptureService;
  }

  /**
   * Starts the continuous screen capture service.
   *
   * <p>This method is automatically called after Spring bean construction via @PostConstruct.
   *
   * <p>Viewport capture is demand-driven and only subscribes while viewport sessions are present.
   */
  @PostConstruct
  public void start() {
    if (!config.isEnabled()) {
      logger.info("Screen capture is disabled");
      return;
    }
    logger.info(
        "ScreenCaptureService ready; waiting for viewport clients before subscribing (target viewport FPS: {})",
        viewportStreamConfig.getTargetFps());
  }

  /**
   * Stops the continuous screen capture service.
   *
   * <p>This method is automatically called when the Spring context is destroyed via @PreDestroy.
   */
  @PreDestroy
  public void stop() {
    activeViewportSessions.set(0);
    ensureUnsubscribed();
  }

  /**
   * Called by the unified capture service when a new frame is captured.
   *
   * <p>Converts the frame to OpenCV Mat and sends it to the viewport.
   */
  @Override
  public void onFrameCaptured(FrameEvent event) {
    framesReceived.incrementAndGet();

    long captureTime = event.getCaptureTimeNanos();
    long latestTime = latestCaptureTimeNanos.updateAndGet(prev -> Math.max(prev, captureTime));
    if (captureTime < latestTime) {
      framesDroppedStale.incrementAndGet();
      return;
    }

    try {
      if (captureTime < latestCaptureTimeNanos.get()) {
        framesDroppedStale.incrementAndGet();
        return;
      }
      if (!shouldForwardAtTargetRate(captureTime)) {
        framesSkippedThrottle.incrementAndGet();
        return;
      }

      if (ViewportManager.getInstance() instanceof WebsocketViewport websocketViewport) {
        websocketViewport.publishFrame(
            event.getFrame(), event.getSourceWidth(), event.getSourceHeight());
      } else {
        try (Mat mat = Java2DFrameUtils.toMat(event.getFrame())) {
          ViewportManager.getInstance().updateState(mat);
        }
      }
      framesForwarded.incrementAndGet();
    } catch (Exception e) {
      frameProcessingErrors.incrementAndGet();
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
    return registered.get() && !paused.get();
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

  public void onViewportSessionOpened() {
    if (!config.isEnabled()) {
      return;
    }

    int sessions = activeViewportSessions.incrementAndGet();
    if (sessions == 1) {
      ensureSubscribed();
    }
  }

  public void onViewportSessionClosed() {
    int sessions = activeViewportSessions.updateAndGet(current -> current > 0 ? current - 1 : 0);
    if (sessions == 0) {
      ensureUnsubscribed();
    }
  }

  private synchronized void ensureSubscribed() {
    if (!unifiedConfig.isEnabled()) {
      logger.warn("Unified capture is disabled, viewport streaming cannot subscribe");
      return;
    }
    if (registered.getAndSet(true)) {
      return;
    }

    lastForwardedCaptureTimeNanos.set(0);
    logger.info(
        "Registering ScreenCaptureService with unified capture at {} FPS (viewport target: {} FPS)",
        unifiedConfig.getFps(),
        viewportStreamConfig.getTargetFps());
    unifiedCaptureService.registerSubscriber(this);
    streamActivations.incrementAndGet();
  }

  private synchronized void ensureUnsubscribed() {
    if (!registered.getAndSet(false)) {
      return;
    }

    logger.info("Unregistering ScreenCaptureService from unified capture");
    unifiedCaptureService.unregisterSubscriber(this);
    streamDeactivations.incrementAndGet();
  }

  private boolean shouldForwardAtTargetRate(long captureTimeNanos) {
    long minIntervalNanos = viewportStreamConfig.getTargetIntervalNanos();
    while (true) {
      long lastForwarded = lastForwardedCaptureTimeNanos.get();
      if (lastForwarded > 0 && captureTimeNanos - lastForwarded < minIntervalNanos) {
        return false;
      }
      if (lastForwardedCaptureTimeNanos.compareAndSet(lastForwarded, captureTimeNanos)) {
        return true;
      }
    }
  }

  public long getFramesReceived() {
    return framesReceived.get();
  }

  public long getFramesForwarded() {
    return framesForwarded.get();
  }

  public long getFramesDroppedStale() {
    return framesDroppedStale.get();
  }

  public long getFramesSkippedThrottle() {
    return framesSkippedThrottle.get();
  }

  public long getFrameProcessingErrors() {
    return frameProcessingErrors.get();
  }

  public int getActiveViewportSessions() {
    return activeViewportSessions.get();
  }

  public long getStreamActivations() {
    return streamActivations.get();
  }

  public long getStreamDeactivations() {
    return streamDeactivations.get();
  }
}

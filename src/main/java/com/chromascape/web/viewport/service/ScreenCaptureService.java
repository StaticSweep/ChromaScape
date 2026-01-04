package com.chromascape.web.viewport.service;

import com.chromascape.utils.core.screen.viewport.ViewportManager;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.web.viewport.config.ScreenCaptureConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for continuously capturing the game screen and updating the viewport.
 *
 * <p>This service runs in the background, capturing the game window at a configured FPS rate
 * and sending updates to the viewport. This ensures the viewport always displays the current
 * game state, even when no bot script is running.
 *
 * <p>The service uses {@link ScreenManager#captureWindow()} to capture the game window using
 * Windows GDI, which provides a clean capture without overlay interference.
 */
@Service
public class ScreenCaptureService {

  private static final Logger logger = LogManager.getLogger(ScreenCaptureService.class);

  private final ScreenCaptureConfig config;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile long lastErrorLogTimeMs = 0;

  @Autowired
  public ScreenCaptureService(ScreenCaptureConfig config) {
    this.config = config;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "ScreenCaptureService");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * Starts the continuous screen capture service.
   *
   * <p>This method is automatically called after Spring bean construction via @PostConstruct.
   *
   * <p>Schedules periodic screen captures at the configured FPS rate.
   */
  @PostConstruct
  public void start() {
    if (!config.isEnabled()) {
      logger.info("Screen capture is disabled");
      return;
    }

    if (running.getAndSet(true)) {
      logger.warn("ScreenCaptureService is already running");
      return;
    }

    logger.info("Starting ScreenCaptureService at {} FPS", config.getFps());

    long intervalMs = (long) (1000.0 / config.getFps());

    scheduler.scheduleAtFixedRate(
        this::captureAndSend,
        0,
        intervalMs,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Stops the continuous screen capture service.
   *
   * <p>This method is automatically called when the Spring context is destroyed via @PreDestroy.
   */
  @PreDestroy
  public void stop() {
    if (!running.getAndSet(false)) {
      return;
    }

    logger.info("Stopping ScreenCaptureService");
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

  /**
   * Captures the current game window state and sends it to the viewport.
   *
   * <p>This method is called periodically by the scheduler. It:
   * <ol>
   *   <li>Captures the game window using Windows GDI</li>
   *   <li>Converts the BufferedImage to OpenCV Mat</li>
   *   <li>Sends the Mat to the viewport via ViewportManager</li>
   * </ol>
   *
   * <p>Captures are wrapped in try-catch to prevent capture errors from stopping the service.
   */
  private void captureAndSend() {
    try {
      BufferedImage capturedImage = ScreenManager.captureWindow();

      if (capturedImage != null) {
        try (Mat mat = Java2DFrameUtils.toMat(capturedImage)) {
          ViewportManager.getInstance().updateState(mat);
        }
      }
    } catch (Exception e) {
      // Rate limit error logging to avoid spam when game window is unavailable
      long now = System.currentTimeMillis();
      long elapsedMs = now - lastErrorLogTimeMs;
      long intervalMs = config.getErrorLogIntervalSeconds() * 1000;

      if (elapsedMs >= intervalMs) {
        lastErrorLogTimeMs = now;
        logger.info("Screen capture failed (window may be unavailable): {}", e.getMessage());
      }
    }
  }

  /**
   * Returns whether the service is currently running.
   *
   * @return true if running
   */
  public boolean isRunning() {
    return running.get();
  }
}

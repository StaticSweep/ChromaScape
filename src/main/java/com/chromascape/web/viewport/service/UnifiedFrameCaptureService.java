package com.chromascape.web.viewport.service;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameListener;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Unified frame capture service that provides screen frames to multiple consumers.
 *
 * <p>This service implements a single-capture, multi-consumer pattern to reduce CPU usage: -
 * Captures screen at configured FPS - Distributes frames to all registered listeners - Reference
 * counted lifecycle (starts when first subscriber registers, stops when last unregisters) - Async
 * delivery per subscriber (slow listeners don't block others)
 *
 * <p>Benefits: - Eliminates duplicate screen captures (viewport + recording = 2x captures) - Lower
 * thread priority reduces interference with game rendering - Single configuration point for capture
 * FPS
 */
@Service
public class UnifiedFrameCaptureService {

  private static final Logger logger = LogManager.getLogger(UnifiedFrameCaptureService.class);

  private final UnifiedCaptureConfig config;
  private final Set<FrameListener> subscribers = ConcurrentHashMap.newKeySet();
  private final AtomicInteger refCount = new AtomicInteger(0);
  private final AtomicReference<FrameEvent> latestFrame = new AtomicReference<>();

  private ScheduledExecutorService captureScheduler;
  private ExecutorService dispatchExecutor;
  private volatile boolean capturing = false;
  private volatile long lastErrorLogTimeMs = 0;
  private static final long ERROR_LOG_INTERVAL_MS = 5000;

  @Autowired
  public UnifiedFrameCaptureService(UnifiedCaptureConfig config) {
    this.config = config;
  }

  /**
   * Registers a frame listener to receive captured frames.
   *
   * <p>If this is the first subscriber, starts the capture thread. Subsequent registrations reuse
   * the existing capture thread.
   *
   * @param listener The listener to register
   */
  public synchronized void registerSubscriber(FrameListener listener) {
    if (!config.isEnabled()) {
      logger.warn(
          "Unified capture is disabled, subscriber will not receive frames: {}",
          listener.getClass().getSimpleName());
      return;
    }

    if (subscribers.add(listener)) {
      int count = refCount.incrementAndGet();
      logger.info(
          "Registered frame listener: {} (total subscribers: {})",
          listener.getClass().getSimpleName(),
          count);

      // Start capture if this is the first subscriber
      if (count == 1) {
        startCapture();
      }

      // Send latest frame to new subscriber immediately if available
      FrameEvent latest = latestFrame.get();
      if (latest != null && listener.isActive()) {
        dispatchExecutor.execute(() -> listener.onFrameCaptured(latest));
      }
    }
  }

  /**
   * Unregisters a frame listener.
   *
   * <p>If this is the last subscriber, stops the capture thread to reduce CPU usage.
   *
   * @param listener The listener to unregister
   */
  public synchronized void unregisterSubscriber(FrameListener listener) {
    if (subscribers.remove(listener)) {
      int count = refCount.decrementAndGet();
      logger.info(
          "Unregistered frame listener: {} (remaining subscribers: {})",
          listener.getClass().getSimpleName(),
          count);

      // Stop capture if no more subscribers
      if (count == 0) {
        stopCapture();
      }
    }
  }

  /**
   * Returns the most recently captured frame.
   *
   * <p>Useful for on-demand consumers (e.g., ZoneManager, OCR) that don't need continuous updates.
   *
   * @return The latest frame, or null if no frame has been captured yet
   */
  public FrameEvent getLatestFrame() {
    return latestFrame.get();
  }

  /** Starts the capture thread. */
  private void startCapture() {
    if (capturing) {
      return;
    }

    logger.info("Starting unified frame capture at {} FPS", config.getFps());

    // Create capture scheduler with lower priority to avoid interfering with game
    captureScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "UnifiedFrameCapture");
              t.setDaemon(true);
              t.setPriority(Thread.NORM_PRIORITY - 1);
              return t;
            });

    // Create dispatcher for async frame delivery
    dispatchExecutor =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "FrameDispatch");
              t.setDaemon(true);
              return t;
            });

    capturing = true;

    // Schedule periodic captures
    long intervalMs = config.getIntervalMs();
    captureScheduler.scheduleAtFixedRate(this::captureTick, 0, intervalMs, TimeUnit.MILLISECONDS);
  }

  /** Stops the capture thread. */
  private void stopCapture() {
    if (!capturing) {
      return;
    }

    logger.info("Stopping unified frame capture (no subscribers)");
    capturing = false;

    if (captureScheduler != null) {
      captureScheduler.shutdown();
      try {
        if (!captureScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
          captureScheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        captureScheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
      captureScheduler = null;
    }

    if (dispatchExecutor != null) {
      dispatchExecutor.shutdown();
      try {
        if (!dispatchExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
          dispatchExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        dispatchExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
      dispatchExecutor = null;
    }
  }

  /** Captures a frame and dispatches it to all active subscribers. */
  private void captureTick() {
    if (refCount.get() == 0) {
      return; // No subscribers, skip capture
    }

    try {
      BufferedImage frame = ScreenManager.captureWindow();

      if (frame != null) {
        // Create frame event with timestamp and dimensions
        FrameEvent event =
            new FrameEvent(frame, System.nanoTime(), frame.getWidth(), frame.getHeight());

        // Store as latest frame
        latestFrame.set(event);

        // Dispatch to all active subscribers asynchronously
        for (FrameListener subscriber : subscribers) {
          if (subscriber.isActive()) {
            dispatchExecutor.execute(() -> deliverFrame(subscriber, event));
          }
        }
      }
    } catch (Exception e) {
      // Rate limit error logging to avoid spam
      long now = System.currentTimeMillis();
      if (now - lastErrorLogTimeMs >= ERROR_LOG_INTERVAL_MS) {
        lastErrorLogTimeMs = now;
        logger.info("Frame capture failed (window may be unavailable): {}", e.getMessage());
      }
    }
  }

  /** Delivers a frame to a subscriber, catching any exceptions. */
  private void deliverFrame(FrameListener subscriber, FrameEvent event) {
    try {
      subscriber.onFrameCaptured(event);
    } catch (Exception e) {
      logger.error(
          "Error delivering frame to subscriber {}: {}",
          subscriber.getClass().getSimpleName(),
          e.getMessage());
    }
  }

  /** Cleanup on application shutdown. */
  @PreDestroy
  public void cleanup() {
    logger.info("UnifiedFrameCaptureService cleanup");
    stopCapture();
    subscribers.clear();
    refCount.set(0);
  }

  /** Returns whether the capture service is currently running. */
  public boolean isCapturing() {
    return capturing;
  }

  /** Returns the number of registered subscribers. */
  public int getSubscriberCount() {
    return refCount.get();
  }

  /**
   * Restarts the capture scheduler with updated FPS.
   *
   * <p>Call this after changing UnifiedCaptureConfig.fps at runtime to apply the new frame rate.
   * Only restarts if capture is currently running.
   */
  public synchronized void restartCapture() {
    if (!capturing) {
      return; // Not running, nothing to restart
    }

    logger.info("Restarting frame capture with new FPS: {}", config.getFps());
    stopCapture();
    startCapture();
  }
}

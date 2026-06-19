package com.chromascape.web.viewport;

import com.chromascape.utils.core.screen.viewport.Viewport;
import com.chromascape.web.viewport.config.ViewportStreamConfig;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * A Websocket-based implementation of the {@link Viewport} interface.
 *
 * <p>This component is responsible for receiving visual updates from the bot, encoding them as
 * JPEG frames, and broadcasting them to connected clients via the {@link ViewportWebSocketHandler}.
 *
 * <p>To ensure optimal performance, image conversion and network transmission are handled
 * asynchronously on a separate thread, with frame dropping logic to prevent backpressure on the
 * main bot loop.
 */
@Component
public class WebsocketViewport implements Viewport {

  /** logger for logging things :) . */
  private static final Logger logger = LoggerFactory.getLogger(WebsocketViewport.class);

  /** Websocket handler to broadcast messages. */
  private final ViewportWebSocketHandler handler;
  private final ViewportStreamConfig viewportStreamConfig;
  private final JpegFrameEncoder jpegFrameEncoder;

  /** Queue holding only the latest frame to keep latency low. */
  private final BlockingQueue<PendingFrame> frameQueue = new ArrayBlockingQueue<>(1);

  /** Executor service for running the background processing tasks. */
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  /** Flag indicating whether the background worker is currently busy. */
  private final AtomicBoolean workerRunning = new AtomicBoolean(false);

  /** Frame sequence counter for ordering. */
  private final AtomicLong frameCounter = new AtomicLong(0);
  private final AtomicLong framesSkippedNoSessions = new AtomicLong(0);
  private final AtomicLong framesDroppedEncodeBackpressure = new AtomicLong(0);
  private final AtomicLong encodeCount = new AtomicLong(0);
  private final AtomicLong totalEncodeTimeMs = new AtomicLong(0);
  private final AtomicLong maxEncodeTimeMs = new AtomicLong(0);
  private final AtomicLong totalPayloadBytes = new AtomicLong(0);

  /**
   * Constructs a new WebsocketViewport.
   *
   * @param handler The websocket handler for broadcasting messages. Injected lazily.
   */
  @Autowired
  public WebsocketViewport(
      @Lazy ViewportWebSocketHandler handler,
      ViewportStreamConfig viewportStreamConfig,
      JpegFrameEncoder jpegFrameEncoder) {
    this.handler = handler;
    this.viewportStreamConfig = viewportStreamConfig;
    this.jpegFrameEncoder = jpegFrameEncoder;
  }

  /**
   * Accepts a new image state from the bot.
   *
   * <p>Frames are added to a single-frame queue. If the queue is full, the existing frame is
   * dropped so the newest frame always wins. The worker thread processes frames and broadcasts them
   * with sequence numbers.
   *
   * @param mat The raw OpenCV matrix representing the new state.
   */
  @Override
  public void updateState(Mat mat) {
    BufferedImage image = Java2DFrameUtils.toBufferedImage(mat);
    publishFrame(image, image.getWidth(), image.getHeight());
  }

  /**
   * Accepts a direct buffered image for live viewport streaming.
   *
   * <p>This path avoids the extra Mat conversion used by the generic Viewport interface and is used
   * by the continuous screen capture pipeline.
   */
  public void publishFrame(BufferedImage image, int sourceWidth, int sourceHeight) {
    if (image == null) {
      return;
    }
    if (handler.getActiveSessionCount() == 0) {
      framesSkippedNoSessions.incrementAndGet();
      return;
    }

    // Enqueue: if full, drop oldest then add new (newest frames win)
    if (frameQueue.remainingCapacity() == 0) {
      frameQueue.poll(); // Drop oldest
      framesDroppedEncodeBackpressure.incrementAndGet();
    }
    frameQueue.offer(new PendingFrame(image, sourceWidth, sourceHeight));

    // Start worker if not running
    if (workerRunning.compareAndSet(false, true)) {
      executor.submit(this::processPendingUpdate);
    }
  }

  /**
   * The background worker loop that processes and sends images.
   *
   * <p>Processes all frames in the queue, assigning sequence numbers to each frame for client-side
   * ordering.
   */
  private void processPendingUpdate() {
    try {
      PendingFrame frame;
      while ((frame = frameQueue.poll()) != null) {
        if (handler.getActiveSessionCount() == 0) {
          framesSkippedNoSessions.incrementAndGet();
          continue;
        }
        long seq = frameCounter.incrementAndGet();
        try {
          long encodeStart = System.currentTimeMillis();
          BufferedImage prepared =
              downscaleIfNeeded(frame.image(), viewportStreamConfig.getMaxLongEdge());
          byte[] jpegBytes =
              jpegFrameEncoder.encode(prepared, viewportStreamConfig.getJpegQuality());
          long encodeMs = System.currentTimeMillis() - encodeStart;
          encodeCount.incrementAndGet();
          totalEncodeTimeMs.addAndGet(encodeMs);
          maxEncodeTimeMs.accumulateAndGet(encodeMs, Math::max);
          if (encodeMs > 30) {
            logger.warn("Slow JPEG encode: {}ms", encodeMs);
          }
          byte[] payload =
              buildBinaryFramePayload(seq, frame.sourceWidth(), frame.sourceHeight(), jpegBytes);
          totalPayloadBytes.addAndGet(payload.length);
          handler.broadcast(payload);
        } catch (IOException e) {
          logger.error("Failed to encode image: {}", e.getMessage());
        }
      }
    } finally {
      workerRunning.set(false);
      // Restart if new frames arrived while we were processing
      if (!frameQueue.isEmpty()) {
        if (workerRunning.compareAndSet(false, true)) {
          executor.submit(this::processPendingUpdate);
        }
      }
    }
  }

  /**
   * Wraps a JPEG payload in the viewport binary websocket format.
   *
   * <p>Header layout: 8-byte seq, 4-byte source width, 4-byte source height, followed by JPEG
   * bytes.
   */
  static byte[] buildBinaryFramePayload(
      long seq, int sourceWidth, int sourceHeight, byte[] jpegBytes) {
    ByteBuffer buffer = ByteBuffer.allocate(16 + jpegBytes.length);
    buffer.putLong(seq);
    buffer.putInt(sourceWidth);
    buffer.putInt(sourceHeight);
    buffer.put(jpegBytes);
    return buffer.array();
  }

  private static BufferedImage downscaleIfNeeded(BufferedImage image, int maxLongEdge) {
    int width = image.getWidth();
    int height = image.getHeight();
    int currentLongEdge = Math.max(width, height);
    if (currentLongEdge <= maxLongEdge) {
      return image;
    }

    double scale = (double) maxLongEdge / currentLongEdge;
    int scaledWidth = Math.max(1, (int) Math.round(width * scale));
    int scaledHeight = Math.max(1, (int) Math.round(height * scale));

    BufferedImage scaled =
        new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_3BYTE_BGR);
    Graphics2D g2 = scaled.createGraphics();
    try {
      g2.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g2.drawImage(image, 0, 0, scaledWidth, scaledHeight, null);
    } finally {
      g2.dispose();
    }
    return scaled;
  }

  public long getFramesSkippedNoSessions() {
    return framesSkippedNoSessions.get();
  }

  public long getFramesDroppedEncodeBackpressure() {
    return framesDroppedEncodeBackpressure.get();
  }

  public long getEncodeCount() {
    return encodeCount.get();
  }

  public double getAverageEncodeTimeMs() {
    long count = encodeCount.get();
    return count == 0 ? 0.0 : (double) totalEncodeTimeMs.get() / count;
  }

  public long getMaxEncodeTimeMs() {
    return maxEncodeTimeMs.get();
  }

  public double getAveragePayloadBytes() {
    long count = encodeCount.get();
    return count == 0 ? 0.0 : (double) totalPayloadBytes.get() / count;
  }

  private record PendingFrame(BufferedImage image, int sourceWidth, int sourceHeight) {}
}

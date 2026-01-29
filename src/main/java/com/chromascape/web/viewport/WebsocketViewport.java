package com.chromascape.web.viewport;

import com.chromascape.utils.core.screen.viewport.Viewport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
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
 * <p>This component is responsible for receiving visual updates from the bot, converting them to a
 * web-friendly format (Base64 JPEG), and broadcasting them to connected clients via the {@link
 * ViewportWebSocketHandler}.
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

  /** Queue holding only the latest frame to keep latency low. */
  private final BlockingQueue<BufferedImage> frameQueue = new ArrayBlockingQueue<>(1);

  /** Executor service for running the background processing tasks. */
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  /** Flag indicating whether the background worker is currently busy. */
  private final AtomicBoolean workerRunning = new AtomicBoolean(false);

  /** Frame sequence counter for ordering. */
  private final AtomicLong frameCounter = new AtomicLong(0);

  /**
   * Constructs a new WebsocketViewport.
   *
   * @param handler The websocket handler for broadcasting messages. Injected lazily.
   */
  @Autowired
  public WebsocketViewport(@Lazy ViewportWebSocketHandler handler) {
    this.handler = handler;
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

    // Enqueue: if full, drop oldest then add new (newest frames win)
    if (frameQueue.remainingCapacity() == 0) {
      frameQueue.poll(); // Drop oldest
    }
    frameQueue.offer(image);

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
      BufferedImage image;
      while ((image = frameQueue.poll()) != null) {
        long seq = frameCounter.incrementAndGet();
        try {
          String base64Image = encodeImageToBase64(image);
          String jsonPayload =
              String.format(
                  "{\"seq\":%d,\"data\":\"data:image/jpeg;base64,%s\"}", seq, base64Image);
          handler.broadcast(jsonPayload);
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
   * Encodes a BufferedImage into a Base64 string representation of a JPEG.
   *
   * <p>JPEG encoding is used instead of PNG for performance: ~3-8ms vs 20-50ms per frame, enabling
   * smooth 30+ FPS streaming.
   *
   * @param image The image to encode.
   * @return The Base64 encoded string.
   * @throws IOException If writing the image fails.
   */
  private String encodeImageToBase64(BufferedImage image) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", outputStream);
    return Base64.getEncoder().encodeToString(outputStream.toByteArray());
  }
}

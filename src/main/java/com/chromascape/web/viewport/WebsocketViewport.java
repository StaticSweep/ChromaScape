package com.chromascape.web.viewport;

import com.chromascape.utils.core.screen.viewport.Viewport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
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
 * web-friendly format (Base64 PNG), and broadcasting them to connected clients via the {@link
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

  /** Holds the latest update to be processed, or null if empty. */
  private final AtomicReference<BufferedImage> pendingUpdate = new AtomicReference<>();

  /** Executor service for running the background processing tasks. */
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  /** Flag indicating whether the background worker is currently busy. */
  private volatile boolean isProcessing = false;

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
   * <p>If the worker thread is free, the {@link Mat} is converted to a {@link BufferedImage} and
   * queued for processing. If the worker is busy, the frame is dropped to maintain performance.
   *
   * @param mat The raw OpenCV matrix representing the new state.
   */
  @Override
  public void updateState(Mat mat) {
    // Optimization: Check if we are already processing a frame.
    // If we are backlogged, DROP this frame immediately to save CPU.
    // We only convert to BufferedImage if we actually plan to queue it.
    if (isProcessing && pendingUpdate.get() != null) {
      return;
    }

    // Convert here. This cost is only incurred if we are NOT backlogged.
    BufferedImage image = Java2DFrameUtils.toBufferedImage(mat);

    // Atomically set the latest update
    pendingUpdate.set(image);

    // If not currently processing, trigger the worker
    if (!isProcessing) {
      executor.submit(this::processPendingUpdate);
    }
  }

  /**
   * The background worker loop that processes and sends images.
   *
   * <p>It continues running as long as there are pending updates in the {@code pendingUpdate}
   * reference.
   */
  private void processPendingUpdate() {
    isProcessing = true;
    try {
      // Keep processing as long as there is a pending update
      BufferedImage image = pendingUpdate.getAndSet(null);
      while (image != null) {
        try {
          String base64Image = encodeImageToBase64(image);
          // Send raw data URI string directly
          String message = "data:image/png;base64," + base64Image;
          handler.broadcast(message);
        } catch (IOException e) {
          logger.error("Failed to encode image for Viewport: {}", e.getMessage());
        }

        // Check if a new update came in while we were processing
        image = pendingUpdate.getAndSet(null);
      }
    } finally {
      isProcessing = false;
      // Double check race condition
      if (pendingUpdate.get() != null) {
        executor.submit(this::processPendingUpdate);
      }
    }
  }

  /**
   * Encodes a BufferedImage into a Base64 string representation of a PNG.
   *
   * @param image The image to encode.
   * @return The Base64 encoded string.
   * @throws IOException If writing the image fails.
   */
  private String encodeImageToBase64(BufferedImage image) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    ImageIO.write(image, "png", outputStream);
    return Base64.getEncoder().encodeToString(outputStream.toByteArray());
  }
}

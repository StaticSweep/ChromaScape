package com.chromascape.web.integration.leeuwbot.service;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameListener;
import com.chromascape.web.integration.leeuwbot.config.LeeuwBotConfig;
import com.chromascape.web.viewport.JpegFrameEncoder;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * WebSocket client for streaming screen frames to Mule Service.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>On startup: Register with Mule, send initial frame
 *   <li>Default: IDLE (0% CPU, keep last frame for thumbnail)
 *   <li>On start_stream: Subscribe to unified capture, send JPEG frames
 *   <li>On stop_stream: Unsubscribe, return to IDLE
 * </ul>
 */
@Component
public class ScreenStreamClient implements FrameListener {

  private static final Logger logger = LogManager.getLogger(ScreenStreamClient.class);

  private final LeeuwBotConfig config;
  private final UnifiedCaptureConfig unifiedConfig;
  private final UnifiedFrameCaptureService unifiedCaptureService;
  private final JpegFrameEncoder jpegFrameEncoder;
  private final String streamId = UUID.randomUUID().toString();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private WebSocketClient wsClient;
  private volatile boolean isStreaming = false;
  private volatile int streamQuality = 70;

  @Autowired
  public ScreenStreamClient(
      LeeuwBotConfig config,
      UnifiedCaptureConfig unifiedConfig,
      UnifiedFrameCaptureService unifiedCaptureService,
      JpegFrameEncoder jpegFrameEncoder) {
    this.config = config;
    this.unifiedConfig = unifiedConfig;
    this.unifiedCaptureService = unifiedCaptureService;
    this.jpegFrameEncoder = jpegFrameEncoder;
  }

  /** Start the screen stream client when application is ready. */
  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    // Verify configuration binding at runtime
    logger.info(
        "LeeuwBot stream config: enabled={}, url={}, hostId={}, displayName={}",
        config.getStream().isEnabled(),
        config.getStream().getUrl(),
        config.getStream().getHostId(),
        config.getStream().getDisplayName());

    if (!config.getStream().isEnabled()) {
      logger.info("Screen streaming disabled in config");
      return;
    }

    try {
      URI serverUri = new URI(config.getStream().getUrl());
      wsClient =
          new WebSocketClient(serverUri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
              logger.info("Connected to Mule stream server at {}", config.getStream().getUrl());

              // Send registration message
              try {
                String regJson =
                    String.format(
                        "{\"type\":\"register\",\"streamId\":\"%s\",\"hostId\":\"%s\",\"displayName\":\"%s\",\"width\":%d,\"height\":%d}",
                        streamId,
                        config.getStream().getHostId(),
                        config.getStream().getDisplayName(),
                        config.getStream().getWidth(),
                        config.getStream().getHeight());
                send(regJson);
                logger.info(
                    "Sent registration: streamId={}, hostId={}, displayName={}",
                    streamId,
                    config.getStream().getHostId(),
                    config.getStream().getDisplayName());
              } catch (Exception e) {
                logger.error("Failed to send registration", e);
              }

              // Send initial frame after a short delay
              Executors.newSingleThreadScheduledExecutor()
                  .schedule(ScreenStreamClient.this::sendInitialFrame, 2, TimeUnit.SECONDS);
            }

            @Override
            public void onMessage(String message) {
              handleMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
              logger.info("Stream connection closed: {} - {}", code, reason);
              stopStreaming();
            }

            @Override
            public void onError(Exception ex) {
              logger.error("Stream connection error", ex);
            }
          };

      wsClient.connect();
    } catch (Exception e) {
      logger.error("Failed to start stream client", e);
    }
  }

  /** Stop the screen stream client. */
  public void stop() {
    stopStreaming();
    if (wsClient != null) {
      wsClient.close();
    }
  }

  /** Send the initial frame after connection. */
  private void sendInitialFrame() {
    if (wsClient == null || !wsClient.isOpen()) {
      return;
    }

    try {
      // Get latest frame from unified capture service
      FrameEvent latestFrame = unifiedCaptureService.getLatestFrame();
      if (latestFrame != null) {
        byte[] jpegData =
            encodeFrameToJpeg(latestFrame.getFrame(), config.getStream().getQuality());
        if (jpegData != null) {
          wsClient.send(jpegData);
          logger.info("Sent initial frame: {} bytes", jpegData.length);
        }
      } else {
        logger.warn("No frame available to send as initial frame");
      }
    } catch (Exception e) {
      logger.error("Failed to send initial frame", e);
    }
  }

  /** Handle incoming messages from Mule service. */
  private void handleMessage(String message) {
    try {
      JsonNode json = objectMapper.readTree(message);
      String type = json.has("type") ? json.get("type").asText() : "";

      if ("start_stream".equals(type)) {
        int fps = json.has("fps") ? json.get("fps").asInt() : config.getStream().getFps();
        int quality =
            json.has("quality") ? json.get("quality").asInt() : config.getStream().getQuality();
        startStreaming(fps, quality);
      } else if ("stop_stream".equals(type)) {
        stopStreaming();
      }
    } catch (Exception e) {
      logger.error("Failed to handle message", e);
    }
  }

  /** Start streaming frames at the specified FPS and quality. */
  private void startStreaming(int fps, int quality) {
    if (isStreaming) {
      return;
    }

    isStreaming = true;
    streamQuality = quality;
    logger.info(
        "Starting streaming at {} FPS (unified capture rate), quality {}",
        unifiedConfig.getFps(),
        quality);

    // Register with unified capture service
    if (unifiedConfig.isEnabled()) {
      unifiedCaptureService.registerSubscriber(this);
      logger.info("Registered with unified capture service for streaming");
    } else {
      logger.warn("Unified capture is disabled, streaming will not receive frames");
    }
  }

  /** Stop streaming frames. */
  private void stopStreaming() {
    if (!isStreaming) {
      return;
    }

    isStreaming = false;
    logger.info("Stopping streaming");

    // Unregister from unified capture service
    if (unifiedConfig.isEnabled()) {
      unifiedCaptureService.unregisterSubscriber(this);
      logger.info("Unregistered from unified capture service");
    }
  }

  /**
   * Called by the unified capture service when a new frame is captured.
   *
   * <p>Encodes the frame to JPEG and sends it to the Mule service.
   */
  @Override
  public void onFrameCaptured(FrameEvent event) {
    if (!isStreaming || wsClient == null || !wsClient.isOpen()) {
      return;
    }

    try {
      byte[] jpegData = encodeFrameToJpeg(event.getFrame(), streamQuality);
      if (jpegData != null) {
        wsClient.send(jpegData);
      }
    } catch (Exception e) {
      logger.error("Failed to send frame to Mule service", e);
    }
  }

  /**
   * Returns whether this listener is active and wants to receive frames.
   *
   * <p>Returns true while streaming is active.
   */
  @Override
  public boolean isActive() {
    return isStreaming && wsClient != null && wsClient.isOpen();
  }

  /**
   * Encodes a BufferedImage to JPEG format.
   *
   * @param image The image to encode
   * @param quality JPEG quality (1-100)
   * @return JPEG encoded data, or null on error
   */
  private byte[] encodeFrameToJpeg(BufferedImage image, int quality) {
    try {
      if (image == null) {
        return null;
      }
      return jpegFrameEncoder.encode(image, quality);
    } catch (Exception e) {
      logger.error("Failed to encode frame to JPEG", e);
      return null;
    }
  }
}

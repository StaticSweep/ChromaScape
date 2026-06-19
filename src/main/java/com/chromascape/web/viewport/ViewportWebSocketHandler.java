package com.chromascape.web.viewport;

import com.chromascape.web.viewport.service.ScreenCaptureService;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * A WebSocket handler specifically for the viewport endpoint.
 *
 * <p>This class manages the active WebSocket connections and provides functionality to broadcast
 * image updates to all connected clients.
 */
@Component
public class ViewportWebSocketHandler extends TextWebSocketHandler {

  private static final Logger logger = LoggerFactory.getLogger(ViewportWebSocketHandler.class);

  /** A thread-safe set of active WebSocket sessions. */
  private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
  private final ScreenCaptureService screenCaptureService;

  /**
   * Single-threaded executor for sending messages asynchronously.
   *
   * <p>Uses a single thread to keep WebSocketSession.sendMessage serialized while coalescing frames
   * to avoid backlog.
   */
  private final ExecutorService wsExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "ViewportBroadcast");
            t.setDaemon(true);
            return t;
          });

  /** Latest viewport payload to send (coalesced). */
  private final AtomicReference<byte[]> pendingMessage = new AtomicReference<>();

  /** Ensures only one send loop is queued at a time. */
  private final AtomicBoolean sendScheduled = new AtomicBoolean(false);
  private final AtomicLong broadcastRequests = new AtomicLong(0);
  private final AtomicLong flushCycles = new AtomicLong(0);
  private final AtomicLong payloadsFlushed = new AtomicLong(0);
  private final AtomicLong sessionSendSuccesses = new AtomicLong(0);
  private final AtomicLong sessionSendErrors = new AtomicLong(0);

  @Autowired
  public ViewportWebSocketHandler(@Lazy ScreenCaptureService screenCaptureService) {
    this.screenCaptureService = screenCaptureService;
  }

  /**
   * Invoked after the WebSocket connection has been closed by either side, or after a transport
   * error has occurred.
   *
   * @param session The session that was established.
   */
  @Override
  public void afterConnectionEstablished(@Nullable WebSocketSession session) {
    if (session != null) {
      sessions.add(session);
      screenCaptureService.onViewportSessionOpened();
      logger.info("Viewport WebSocket client connected");
    }
  }

  /**
   * Invoked after the WebSocket connection has been closed by either side, or after a transport
   * error has occurred.
   *
   * @param session The session that was closed.
   * @param status The status code indicating why the session was closed.
   */
  @Override
  public void afterConnectionClosed(
      @Nullable WebSocketSession session, @Nullable CloseStatus status) {
    if (session != null && sessions.remove(session)) {
      screenCaptureService.onViewportSessionClosed();
      logger.info("Viewport WebSocket client disconnected");
    }
  }

  /**
   * Invoked when an error occurs in the underlying communication channel.
   *
   * @param session The session where the error occurred.
   * @param exception The exception that occurred.
   * @throws Exception If handling the error fails.
   */
  @Override
  public void handleTransportError(
      @Nullable WebSocketSession session, @Nullable Throwable exception) throws Exception {
    if (session != null) {
      if (sessions.remove(session)) {
        screenCaptureService.onViewportSessionClosed();
      }
      session.close(CloseStatus.SERVER_ERROR);
      logger.error(
          "Viewport WebSocket transport error: {}",
          exception != null ? exception.getMessage() : "Unknown error");
    }
  }

  /**
   * Broadcasts a binary frame to all currently connected clients.
   *
   * <p>Frames are coalesced so only the most recent payload is queued for send. This avoids
   * backpressure when clients or the network can't keep up with capture.
   *
   * @param message The binary frame payload to broadcast.
   */
  public void broadcast(byte[] message) {
    broadcastRequests.incrementAndGet();
    logger.debug(
        "Broadcasting viewport frame, size: {} bytes, sessions: {}",
        message.length,
        sessions.size());
    pendingMessage.set(message);
    scheduleSend();
  }

  private void scheduleSend() {
    if (!sendScheduled.compareAndSet(false, true)) {
      return;
    }
    wsExecutor.submit(this::flushPending);
  }

  private void flushPending() {
    flushCycles.incrementAndGet();
    try {
      while (true) {
        byte[] message = pendingMessage.getAndSet(null);
        if (message == null) {
          return;
        }
        payloadsFlushed.incrementAndGet();
        for (WebSocketSession session : sessions) {
          if (!session.isOpen()) {
            sessions.remove(session);
            continue;
          }
          try {
            session.sendMessage(new BinaryMessage(message));
            sessionSendSuccesses.incrementAndGet();
          } catch (IOException e) {
            sessionSendErrors.incrementAndGet();
            logger.warn("Failed to send viewport data to session: {}", e.getMessage());
            sessions.remove(session);
          }
        }
      }
    } finally {
      sendScheduled.set(false);
      if (pendingMessage.get() != null) {
        scheduleSend();
      }
    }
  }

  public int getActiveSessionCount() {
    return sessions.size();
  }

  public long getBroadcastRequests() {
    return broadcastRequests.get();
  }

  public long getFlushCycles() {
    return flushCycles.get();
  }

  public long getPayloadsFlushed() {
    return payloadsFlushed.get();
  }

  public long getSessionSendSuccesses() {
    return sessionSendSuccesses.get();
  }

  public long getSessionSendErrors() {
    return sessionSendErrors.get();
  }
}

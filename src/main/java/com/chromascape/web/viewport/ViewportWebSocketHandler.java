package com.chromascape.web.viewport;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
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

  /** Executor service for sending messages asynchronously to avoid blocking. */
  private final ExecutorService wsExecutor = Executors.newCachedThreadPool();

  /**
   * Invoked after the WebSocket connection has been closed by either side, or after a transport
   * error has occurred.
   *
   * @param session The session that was established.
   */
  @Override
  public void afterConnectionEstablished(@Nullable WebSocketSession session) {
    sessions.add(session);
    if (session != null) {
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
    sessions.remove(session);
    if (session != null) {
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
    sessions.remove(session);
    if (session != null) {
      session.close(CloseStatus.SERVER_ERROR);
      logger.error(
          "Viewport WebSocket transport error: {}",
          exception != null ? exception.getMessage() : "Unknown error");
    }
  }

  /**
   * Broadcasts a text message to all currently connected clients.
   *
   * <p>Broadcasting is done asynchronously for each client to prevent one slow client from blocking
   * the update for others.
   *
   * @param message The message string to broadcast (e.g., a Data URI).
   */
  public void broadcast(String message) {
    if (sessions.isEmpty()) {
      return;
    }
    for (WebSocketSession session : sessions) {
      if (!session.isOpen()) {
        sessions.remove(session);
        continue;
      }
      wsExecutor.submit(
          () -> {
            try {
              // Double check before sending
              if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
              }
            } catch (IOException e) {
              logger.warn("Failed to send viewport data to session: {}", e.getMessage());
              sessions.remove(session);
            }
          });
    }
  }
}

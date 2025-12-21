package com.chromascape.web.logs;

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
 * WebSocket handler for broadcasting log messages to connected clients.
 *
 * <p>This handler manages active WebSocket sessions and provides a broadcast method for delivering
 * log messages to all connected clients. It is designed to be thread-safe and robust against
 * session disconnects and transport errors.
 *
 * <p>Usage: Register this handler as a bean in your Spring application context and wire it in your
 * WebSocket config. The {@code broadcast} method should be called by your log appender whenever a
 * new log message is available for real-time delivery.
 *
 * <p>All log messages and transport errors are recorded via SLF4J for operational visibility.
 *
 * @see org.springframework.web.socket.handler.TextWebSocketHandler
 * @see com.chromascape.web.logs.WebSocketLogAppender
 */
@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

  /** SLF4J logger for connection and error events. */
  private static final Logger logger = LoggerFactory.getLogger(LogWebSocketHandler.class);

  /**
   * Thread-safe set of active WebSocket sessions. {@link CopyOnWriteArraySet} is used to avoid
   * concurrent modification issues during broadcast.
   */
  private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

  /** Executor service to send WebSocket requests without blocking the main thread. */
  private final ExecutorService wsExecutor = Executors.newCachedThreadPool();

  /**
   * Called when a new WebSocket connection is established. Adds the session to the active set and
   * logs the connection.
   *
   * @param session the new WebSocket session (maybe null, per Spring contract)
   */
  @Override
  public void afterConnectionEstablished(@Nullable WebSocketSession session) {
    sessions.add(session);
    // Defensive null check; session is typically non-null after establishment.
    if (session != null) {
      logger.info("WebSocket client connected");
    }
  }

  /**
   * Called when a WebSocket connection is closed. Removes the session from the active set and logs
   * the disconnection.
   *
   * @param session the closed WebSocket session (maybe null)
   * @param status the close status (maybe null)
   */
  @Override
  public void afterConnectionClosed(
      @Nullable WebSocketSession session, @Nullable CloseStatus status) {
    sessions.remove(session);
    if (session != null) {
      logger.info("WebSocket client disconnected");
    }
  }

  /**
   * Called when a transport error occurs for a session. Removes the session, closes it with a
   * server error status, and logs the error.
   *
   * @param session the affected WebSocket session (maybe null)
   * @param exception the thrown error/exception
   * @throws Exception if closing the session fails
   */
  @Override
  public void handleTransportError(
      @Nullable WebSocketSession session, @Nullable Throwable exception) throws Exception {
    sessions.remove(session);
    if (session != null) {
      session.close(CloseStatus.SERVER_ERROR);
      assert exception != null;
      logger.error("WebSocket transport error for session: {}", exception.getMessage());
    }
  }

  /**
   * Broadcasts a message to all connected WebSocket clients. If a session fails to receive the
   * message, it is removed from the active set and the failure is logged.
   *
   * @param message the message to broadcast
   */
  public void broadcast(String message) {
    for (WebSocketSession session : sessions) {
      wsExecutor.submit(
          () -> {
            try {
              synchronized (session) {
                if (session.isOpen()) {
                  session.sendMessage(new TextMessage(message));
                }
              }
            } catch (IOException e) {
              sessions.remove(session);
              logger.warn("Failed to send message to session: {}", e.getMessage());
            }
          });
    }
  }
}

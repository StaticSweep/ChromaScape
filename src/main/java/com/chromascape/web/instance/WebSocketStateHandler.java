package com.chromascape.web.instance;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket handler responsible for broadcasting the current running state of a script to all
 * connected WebSocket clients.
 *
 * <p>Clients subscribing to this endpoint will receive messages containing {@code "true"} if a
 * script is running, or {@code "false"} if no script is active. The handler maintains a thread-safe
 * set of active sessions and automatically handles client connect/disconnect events.
 *
 * <p>This implementation uses an {@link ExecutorService} to send messages asynchronously, ensuring
 * that blocking network operations do not interfere with the main application thread or script
 * execution.
 */
@Component
public class WebSocketStateHandler extends TextWebSocketHandler {

  /** Logger for internal events and errors. */
  private final Logger logger = LogManager.getLogger(this.getClass().getName());

  /** Thread-safe set of all currently connected WebSocket sessions. */
  private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

  /**
   * Invoked after a new WebSocket connection is established.
   *
   * @param session the session that was established
   */
  @Override
  public void afterConnectionEstablished(@Nullable WebSocketSession session) {
    sessions.add(session);
  }

  /**
   * Invoked after a WebSocket connection is closed.
   *
   * @param session the session that was closed
   * @param status the status describing why the connection was closed
   */
  @Override
  public void afterConnectionClosed(
      @Nullable WebSocketSession session, @Nullable CloseStatus status) {
    sessions.remove(session);
  }

  /**
   * Broadcasts the current script running state to all connected clients asynchronously.
   *
   * <p>The message sent is a simple {@code "true"} or {@code "false"} string, representing whether
   * a script is currently active. This method offloads network operations to a separate executor to
   * prevent blocking the calling thread.
   *
   * @param isRunning {@code true} if a script is running, {@code false} otherwise
   */
  public void broadcast(boolean isRunning) {
    for (WebSocketSession session : sessions) {
      if (session.isOpen()) {
        try {
          session.sendMessage(new TextMessage(Boolean.toString(isRunning)));
        } catch (IOException e) {
          sessions.remove(session);
          logger.warn("Failed to send message to session: {}", e.getMessage());
        } catch (RuntimeException e) {
          if (Thread.currentThread().isInterrupted()) {
            logger.info("Broadcast interrupted, exiting task.");
            return;
          }
          throw e;
        }
      }
    }
  }
}

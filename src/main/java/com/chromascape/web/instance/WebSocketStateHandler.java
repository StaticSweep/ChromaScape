package com.chromascape.web.instance;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>This component is typically registered in {@link
 * org.springframework.web.socket.config.annotation.WebSocketConfigurer} to expose a `/ws/state`
 * endpoint.
 */
@Component
public class WebSocketStateHandler extends TextWebSocketHandler {

  /** Logger for internal events and errors. */
  private final Logger logger = LogManager.getLogger(this.getClass().getName());

  /** Thread-safe set of all currently connected WebSocket sessions. */
  private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

  /**
   * Tracks the last broadcasted state (running or not) to immediately synchronize new connections.
   *
   * <p>Initialized to {@code false}. Updated every time {@link #broadcast(boolean)} is called.
   */
  private volatile boolean lastState = false;

  /**
   * Invoked after a new WebSocket connection is established.
   *
   * <p>Adds the session to the active set and immediately sends the {@code lastState} so the client
   * UI can sync its start/stop button without waiting for a new event.
   *
   * @param session the session that was established
   */
  @Override
  public void afterConnectionEstablished(@Nullable WebSocketSession session) {
    sessions.add(session);
    // Send current state immediately to the new session
    if (session != null && session.isOpen()) {
      try {
        session.sendMessage(new TextMessage(Boolean.toString(lastState)));
      } catch (IOException e) {
        logger.error("Failed to send initial state to client", e);
      }
    }
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
   * Broadcasts the current script running state to all connected clients.
   *
   * <p>The message sent is a simple {@code "true"} or {@code "false"} string, representing whether
   * a script is currently active.
   *
   * <p>Also updates {@link #lastState} to persist this state for future connections.
   *
   * @param isRunning {@code true} if a script is running, {@code false} otherwise
   */
  public void broadcast(boolean isRunning) {
    this.lastState = isRunning;
    for (WebSocketSession session : sessions) {
      if (session.isOpen()) {
        try {
          session.sendMessage(new TextMessage(Boolean.toString(isRunning)));
        } catch (IOException e) {
          logger.error("Failed to send running state to client", e);
        }
      }
    }
  }
}

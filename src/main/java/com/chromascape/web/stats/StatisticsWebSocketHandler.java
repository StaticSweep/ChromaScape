package com.chromascape.web.stats;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket handler that manages connections for the statistics endpoint.
 *
 * <p>Listens on {@code /ws/stats}. This handler maintains a registry of active sessions and
 * provides a mechanism to broadcast statistical data updates to all connected clients in real-time.
 */
@Component
public class StatisticsWebSocketHandler extends TextWebSocketHandler {

  private static final Logger logger = LoggerFactory.getLogger(StatisticsWebSocketHandler.class);

  /**
   * A thread-safe set of active WebSocket sessions.
   *
   * <p>{@link CopyOnWriteArraySet} is used here to ensure safe iteration during broadcasts while
   * allowing concurrent additions and removals of sessions.
   */
  private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

  /**
   * Invoked after a WebSocket negotiation has succeeded and the WebSocket connection is opened and
   * ready for use.
   *
   * <p>This implementation registers the new session in the tracking set to receive future
   * broadcasts.
   *
   * @param session the new {@link WebSocketSession}; may be {@code null} if the framework passes a
   *     null session (though unlikely in standard Spring WebSocket flow)
   */
  @Override
  public void afterConnectionEstablished(@Nullable WebSocketSession session) {
    if (session != null) {
      sessions.add(session);
    }
  }

  /**
   * Invoked after the WebSocket connection has been closed by either side, or after a transport
   * error has occurred.
   *
   * <p>This implementation removes the session from the tracking set to prevent memory leaks and
   * attempted writes to closed connections.
   *
   * @param session the {@link WebSocketSession} that was closed
   * @param status the close status code and reason
   */
  @Override
  public void afterConnectionClosed(
      @Nullable WebSocketSession session, @Nullable CloseStatus status) {
    if (session != null) {
      sessions.remove(session);
    }
  }

  /**
   * Broadcasts the provided statistics JSON string to all currently connected and open clients.
   *
   * <p>If a session is closed or encounters an I/O error during sending, the exception is logged,
   * but the broadcast continues to other clients.
   *
   * @param statsJson The JSON string containing the current stats to be sent to clients.
   */
  public void broadcast(String statsJson) {
    if (sessions.isEmpty()) {
      return;
    }
    for (WebSocketSession session : sessions) {
      if (session.isOpen()) {
        try {
          session.sendMessage(new TextMessage(statsJson));
        } catch (IOException e) {
          logger.warn("Failed to send stats update: {}", e.getMessage());
        }
      }
    }
  }
}

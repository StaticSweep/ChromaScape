package com.chromascape.web.debug;

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
 * WebSocket handler responsible for broadcasting overlay debug data to the frontend.
 *
 * <p>Clients connect at {@code /ws/overlay} and receive JSON payloads that include overlay points
 * such as clicks, mouse position, and target highlights.
 */
@Component
public class OverlayWebSocketHandler extends TextWebSocketHandler {

  private static final Logger logger = LoggerFactory.getLogger(OverlayWebSocketHandler.class);
  private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

  @Override
  public void afterConnectionEstablished(@Nullable WebSocketSession session) {
    if (session != null) {
      sessions.add(session);
      logger.debug("Overlay WebSocket client connected");
    }
  }

  @Override
  public void afterConnectionClosed(
      @Nullable WebSocketSession session, @Nullable CloseStatus status) {
    if (session != null) {
      sessions.remove(session);
      logger.debug("Overlay WebSocket client disconnected");
    }
  }

  @Override
  public void handleTransportError(
      @Nullable WebSocketSession session, @Nullable Throwable exception) throws Exception {
    if (session != null) {
      sessions.remove(session);
      session.close(CloseStatus.SERVER_ERROR);
      logger.warn(
          "Overlay WebSocket error: {}",
          exception != null ? exception.getMessage() : "Unknown error");
    }
  }

  /** Broadcasts a JSON payload to all connected clients. */
  public void broadcast(String message) {
    if (sessions.isEmpty()) {
      return;
    }
    for (WebSocketSession session : sessions) {
      if (!session.isOpen()) {
        sessions.remove(session);
        continue;
      }
      try {
        session.sendMessage(new TextMessage(message));
      } catch (IOException e) {
        logger.warn("Failed to send overlay update: {}", e.getMessage());
        sessions.remove(session);
      }
    }
  }
}

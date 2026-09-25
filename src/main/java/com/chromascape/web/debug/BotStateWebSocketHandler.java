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
 * WebSocket handler responsible for broadcasting structured bot state updates.
 *
 * <p>Clients connect at {@code /ws/bot-state} and receive JSON payloads that summarize script and
 * decision context.
 */
@Component
public class BotStateWebSocketHandler extends TextWebSocketHandler {

  private static final Logger logger = LoggerFactory.getLogger(BotStateWebSocketHandler.class);
  private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

  @Override
  public void afterConnectionEstablished(@Nullable WebSocketSession session) {
    if (session != null) {
      sessions.add(session);
    }
  }

  @Override
  public void afterConnectionClosed(
      @Nullable WebSocketSession session, @Nullable CloseStatus status) {
    if (session != null) {
      sessions.remove(session);
    }
  }

  @Override
  public void handleTransportError(
      @Nullable WebSocketSession session, @Nullable Throwable exception) throws Exception {
    if (session != null) {
      sessions.remove(session);
      session.close(CloseStatus.SERVER_ERROR);
      logger.warn(
          "Bot state WebSocket error: {}",
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
        logger.warn("Failed to send bot state update: {}", e.getMessage());
        sessions.remove(session);
      }
    }
  }
}

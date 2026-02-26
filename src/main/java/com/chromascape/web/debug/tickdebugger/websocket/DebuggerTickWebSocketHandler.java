package com.chromascape.web.debug.tickdebugger.websocket;

import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket handler for broadcasting tick data to debugger web UI clients.
 *
 * <p>This handler receives tick batch updates from the ingestion service and broadcasts them to
 * connected web clients for live debugging visualization.
 *
 * <p>Endpoint: {@code /ws/debugger/tick-stream}
 */
@Component
public class DebuggerTickWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(DebuggerTickWebSocketHandler.class);

  private final List<WebSocketSession> clients = new ArrayList<>();
  private final TickStreamIngestionService ingestionService;

  public DebuggerTickWebSocketHandler(TickStreamIngestionService ingestionService) {
    this.ingestionService = ingestionService;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    clients.add(session);
    ingestionService.registerDebuggerClient(session);
    log.info("Debugger client connected: {} (total clients: {})", session.getId(), clients.size());
    super.afterConnectionEstablished(session);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    // This handler is for broadcast only, clients don't send messages
    log.debug(
        "Received message from debugger client {}: {}", session.getId(), message.getPayload());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    clients.remove(session);
    ingestionService.unregisterDebuggerClient(session);
    log.info(
        "Debugger client disconnected: {} (total clients: {})", session.getId(), clients.size());
    super.afterConnectionClosed(session, status);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    log.error(
        "Debugger client transport error: {} - {}",
        session.getId(),
        exception.getMessage(),
        exception);
    clients.remove(session);
    super.handleTransportError(session, exception);
  }

  /**
   * Broadcast a message to all connected debugger clients.
   *
   * @param message the message to broadcast
   */
  public void broadcast(TextMessage message) {
    List<WebSocketSession> toRemove = new ArrayList<>();

    for (WebSocketSession client : clients) {
      if (client.isOpen()) {
        try {
          synchronized (client) {
            client.sendMessage(message);
          }
        } catch (Exception e) {
          log.warn("Failed to send message to client {}: {}", client.getId(), e.getMessage());
          toRemove.add(client);
        }
      } else {
        toRemove.add(client);
      }
    }

    // Remove dead clients
    for (WebSocketSession client : toRemove) {
      clients.remove(client);
      ingestionService.unregisterDebuggerClient(client);
    }
  }

  /** Get the number of connected clients. */
  public int getClientCount() {
    return clients.size();
  }
}

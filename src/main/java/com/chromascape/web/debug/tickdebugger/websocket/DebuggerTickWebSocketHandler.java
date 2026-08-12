package com.chromascape.web.debug.tickdebugger.websocket;

import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
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
 *
 * <p>The client set is a {@link CopyOnWriteArraySet} so broadcast iterates safely during concurrent
 * connect/disconnect. Connect is idempotent; close and transport error remove the client, and a
 * client is unregistered from ingestion only when its removal actually succeeded. A failed send
 * removes and unregisters the client exactly once. Broadcast duration is measured and a warning is
 * logged when a single broadcast exceeds 250 ms.
 */
@Component
public class DebuggerTickWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(DebuggerTickWebSocketHandler.class);
  private static final long SLOW_BROADCAST_WARN_MILLIS = 250L;

  private final Set<WebSocketSession> clients = new CopyOnWriteArraySet<>();
  private final TickStreamIngestionService ingestionService;

  public DebuggerTickWebSocketHandler(TickStreamIngestionService ingestionService) {
    this.ingestionService = ingestionService;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    if (clients.add(session)) {
      ingestionService.registerDebuggerClient(session);
    }
    log.info(
        "Debugger client connected: {} (total clients: {})", session.getId(), getClientCount());
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
    if (clients.remove(session)) {
      ingestionService.unregisterDebuggerClient(session);
    }
    log.info(
        "Debugger client disconnected: {} (total clients: {})", session.getId(), getClientCount());
    super.afterConnectionClosed(session, status);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    log.error(
        "Debugger client transport error: {} - {}",
        session.getId(),
        exception.getMessage(),
        exception);
    if (clients.remove(session)) {
      ingestionService.unregisterDebuggerClient(session);
    }
    super.handleTransportError(session, exception);
  }

  /**
   * Broadcast a message to all connected debugger clients.
   *
   * @param message the message to broadcast
   */
  public void broadcast(TextMessage message) {
    long startNanos = System.nanoTime();
    int clientCount = getClientCount();
    for (WebSocketSession client : clients) {
      if (client.isOpen()) {
        try {
          synchronized (client) {
            client.sendMessage(message);
          }
        } catch (Exception e) {
          log.warn("Failed to send message to client {}: {}", client.getId(), e.getMessage());
          removeClient(client);
        }
      } else {
        removeClient(client);
      }
    }
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
    if (elapsedMillis > SLOW_BROADCAST_WARN_MILLIS) {
      log.warn("Slow debugger broadcast of {}ms to {} client(s)", elapsedMillis, clientCount);
    }
  }

  /** Removes a dead client from the set and unregisters it only when removal actually succeeded. */
  private void removeClient(WebSocketSession client) {
    if (clients.remove(client)) {
      ingestionService.unregisterDebuggerClient(client);
    }
  }

  /** Get the number of connected clients. */
  public int getClientCount() {
    return clients.size();
  }
}

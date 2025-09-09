package com.chromascape.web.instance;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket handler responsible for broadcasting script progress updates to connected clients.
 *
 * <p>Clients subscribing to this handler will receive messages containing a single integer
 * representing the progress percentage of the currently running script. The handler uses a
 * thread-safe {@link CopyOnWriteArraySet} to store sessions and automatically handles clients
 * connecting and disconnecting.
 *
 * <p>This component is typically registered in {@link
 * org.springframework.web.socket.config.annotation.WebSocketConfigurer} to expose a `/ws/progress`
 * endpoint.
 */
@Component
public class WebSocketProgressHandler extends TextWebSocketHandler {

  /** Thread-safe set of all currently connected WebSocket sessions. */
  private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

  /**
   * Called after a new WebSocket connection is established.
   *
   * @param session the WebSocket session that was established (nullable)
   */
  @Override
  public void afterConnectionEstablished(@Nullable WebSocketSession session) {
    if (session != null) {
      sessions.add(session);
    }
  }

  /**
   * Broadcasts a progress update to all connected WebSocket clients.
   *
   * <p>The message is sent as a plain integer string representing the current progress percentage
   * of the running script.
   *
   * @param progress the current progress percentage (0-100)
   */
  public void broadcast(int progress) {
    TextMessage msg = new TextMessage(String.valueOf(progress));
    sessions.forEach(
        s -> {
          try {
            if (s.isOpen()) {
              s.sendMessage(msg);
            } else {
              sessions.remove(s);
            }
          } catch (Exception e) {
            sessions.remove(s);
          }
        });
  }
}

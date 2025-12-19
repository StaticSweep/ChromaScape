package com.chromascape.web.state;

import com.chromascape.utils.core.state.BotState;
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
 * WebSocket handler responsible for broadcasting the bot's current semantic state to all connected
 * frontend clients.
 *
 * <p>This handler manages a thread-safe set of active WebSocket sessions. It listens for
 * connections at {@code /ws/semantic-state} and provides methods to push state updates via JSON
 * messages containing the state name, display label, and CSS styling class.
 *
 * @see com.chromascape.utils.core.state.BotState
 * @see WebsocketBotStateListener
 */
@Component
public class SemanticWebSocketHandler extends TextWebSocketHandler {

  private static final Logger logger = LoggerFactory.getLogger(SemanticWebSocketHandler.class);
  private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

  /**
   * Registers a new WebSocket session when a client connects.
   *
   * @param session the new WebSocket session
   */
  @Override
  public void afterConnectionEstablished(@Nullable WebSocketSession session) {
    sessions.add(session);
  }

  /**
   * Removes a WebSocket session when the connection is closed.
   *
   * @param session the closed WebSocket session
   * @param status the closure status
   */
  @Override
  public void afterConnectionClosed(
      @Nullable WebSocketSession session, @Nullable CloseStatus status) {
    sessions.remove(session);
  }

  /**
   * Broadcasts the specified {@link BotState} to all currently connected WebSocket sessions.
   *
   * <p>The state is serialized into a JSON object with the following fields:
   *
   * <ul>
   *   <li>{@code state}: The enum name of the state.
   *   <li>{@code label}: The user-friendly display name.
   *   <li>{@code css}: The associated CSS class for styling UI elements.
   * </ul>
   *
   * @param state the new state to broadcast; must not be null
   */
  public void broadcastState(BotState state) {
    String json =
        String.format(
            "{\"state\": \"%s\", \"label\": \"%s\", \"css\": \"%s\"}",
            state.name(), state.getDisplayName(), state.getCssClass());

    for (WebSocketSession session : sessions) {
      if (session.isOpen()) {
        try {
          session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
          logger.warn("Failed to send state update: {}", e.getMessage());
        }
      }
    }
  }
}

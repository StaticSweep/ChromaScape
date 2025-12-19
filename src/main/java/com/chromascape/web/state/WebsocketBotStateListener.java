package com.chromascape.web.state;

import com.chromascape.utils.core.state.BotState;
import com.chromascape.utils.core.state.BotStateListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A Spring component that implements {@link BotStateListener} to act as a bridge between the core
 * application state and the web layer.
 *
 * <p>This listener subscribes to state changes from the core {@link
 * com.chromascape.utils.core.state.StateManager} (via the listener infrastructure) and forwards
 * them to the {@link SemanticWebSocketHandler} for broadcast to connected web clients.
 *
 * @see SemanticWebSocketHandler
 * @see BotStateListener
 */
@Component
public class WebsocketBotStateListener implements BotStateListener {

  /**
   * The WebSocket handler responsible for managing connections and broadcasting messages to web
   * clients.
   */
  private final SemanticWebSocketHandler handler;

  /**
   * Constructs a new {@code WebsocketBotStateListener} with the specified WebSocket handler.
   *
   * @param handler the {@link SemanticWebSocketHandler} used to broadcast state updates; must not
   *     be null
   */
  @Autowired
  public WebsocketBotStateListener(SemanticWebSocketHandler handler) {
    this.handler = handler;
  }

  /**
   * Invoked when the bot's state changes.
   *
   * <p>This method delegates the new state to the {@link SemanticWebSocketHandler} to be broadcast
   * to all active WebSocket sessions.
   *
   * @param state the new {@link BotState} of the application
   */
  @Override
  public void onStateChange(BotState state) {
    handler.broadcastState(state);
  }
}

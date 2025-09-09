package com.chromascape.utils.core.runtime;

import com.chromascape.web.instance.WebSocketProgressHandler;

/**
 * Utility class for publishing script progress updates to connected WebSocket clients.
 *
 * <p>Acts as a static bridge between core runtime logic and the web layer. Progress values are
 * forwarded to the {@link WebSocketProgressHandler}, which handles broadcasting messages to
 * subscribed WebSocket sessions.
 *
 * <p>This class is designed to be simple and stateless, with a single global {@link
 * WebSocketProgressHandler} instance set once during application initialization.
 */
public class ScriptProgressPublisher {

  /** Singleton reference to the WebSocket handler responsible for broadcasting progress. */
  private static WebSocketProgressHandler handler;

  /**
   * Sets the global {@link WebSocketProgressHandler} to be used for broadcasting updates.
   *
   * <p>This method should typically be invoked once during application startup (e.g., from a
   * configuration or initializer class).
   *
   * @param wsHandler the WebSocket handler instance that will forward progress updates
   */
  public static void setWebSocketHandler(WebSocketProgressHandler wsHandler) {
    handler = wsHandler;
  }

  /**
   * Publishes a progress update to all connected WebSocket clients, if a handler has been set.
   *
   * @param value the progress value to broadcast, usually expressed as a percentage (0–100)
   */
  public static void updateProgress(int value) {
    if (handler != null) {
      handler.broadcast(value);
    }
  }
}

package com.chromascape.web.config;

import com.chromascape.web.instance.WebSocketStateHandler;
import com.chromascape.web.logs.LogWebSocketHandler;
import com.chromascape.web.state.SemanticWebSocketHandler;
import com.chromascape.web.stats.StatisticsWebSocketHandler;
import com.chromascape.web.viewport.ViewportWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Spring configuration class for enabling WebSocket support and registering WebSocket handlers.
 *
 * <p>This configuration enables WebSocket functionality within the Spring Boot application and
 * registers the {@link LogWebSocketHandler} at the endpoint {@code /ws/logs} and {@link
 * WebSocketStateHandler} at {@code /ws/state}. All origins are allowed for cross-origin WebSocket
 * connections, suitable for local development or trusted environments.
 *
 * @see LogWebSocketHandler
 * @see WebSocketStateHandler
 * @see org.springframework.web.socket.config.annotation.WebSocketConfigurer
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  /** The shared handler for broadcasting log messages. */
  private final LogWebSocketHandler logWebSocketHandler;

  /** The shared handler for broadcasting running state updates. */
  private final WebSocketStateHandler stateWebSocketHandler;

  /** The shared handler for broadcasting viewport updates. */
  private final ViewportWebSocketHandler viewportWebSocketHandler;

  /** The shared handler for broadcasting semantic state. */
  private final SemanticWebSocketHandler semanticWebSocketHandler;

  /** The shared handler for broadcasting bot statistic state. */
  private final StatisticsWebSocketHandler statisticsWebSocketHandler;

  /**
   * Constructs the configuration with the injected handlers.
   *
   * @param logWebSocketHandler handler for log messages
   * @param stateWebSocketHandler handler for script running state
   * @param viewportWebSocketHandler handler for viewport updates
   * @param semanticWebSocketHandler handler for semantic state updates
   */
  @Autowired
  public WebSocketConfig(
      LogWebSocketHandler logWebSocketHandler,
      WebSocketStateHandler stateWebSocketHandler,
      ViewportWebSocketHandler viewportWebSocketHandler,
      SemanticWebSocketHandler semanticWebSocketHandler,
      StatisticsWebSocketHandler statisticsWebSocketHandler) {
    this.logWebSocketHandler = logWebSocketHandler;
    this.stateWebSocketHandler = stateWebSocketHandler;
    this.viewportWebSocketHandler = viewportWebSocketHandler;
    this.semanticWebSocketHandler = semanticWebSocketHandler;
    this.statisticsWebSocketHandler = statisticsWebSocketHandler;
  }

  /**
   * Registers WebSocket handlers for the application.
   *
   * @param registry the registry for handler mapping
   */
  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    // Log messages
    registry.addHandler(logWebSocketHandler, "/ws/logs").setAllowedOrigins("*");

    // Script running state
    registry.addHandler(stateWebSocketHandler, "/ws/state").setAllowedOrigins("*");

    // Viewport image stream
    registry.addHandler(viewportWebSocketHandler, "/ws/viewport").setAllowedOrigins("*");

    // Semantic state stream
    registry.addHandler(semanticWebSocketHandler, "/ws/semantic-state").setAllowedOrigins("*");

    // Statistics stream
    registry.addHandler(statisticsWebSocketHandler, "/ws/stats").setAllowedOrigins("*");
  }
}

package com.chromascape.web.config;

import com.chromascape.web.gateway.config.GatewayCapabilitiesProperties;
import com.chromascape.web.debug.BotStateWebSocketHandler;
import com.chromascape.web.debug.OverlayWebSocketHandler;
import com.chromascape.web.debug.patchloop.websocket.PatchLoopWebSocketHandler;
import com.chromascape.web.debug.tickdebugger.websocket.DebuggerTickWebSocketHandler;
import com.chromascape.web.debug.tickdebugger.websocket.TickStreamWebSocketHandler;
import com.chromascape.web.instance.WebSocketStateHandler;
import com.chromascape.web.logs.LogWebSocketHandler;
import com.chromascape.web.state.SemanticWebSocketHandler;
import com.chromascape.web.stats.StatisticsWebSocketHandler;
import com.chromascape.web.viewport.ViewportWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

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
@EnableAsync
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

  /** The shared handler for broadcasting overlay data. */
  private final OverlayWebSocketHandler overlayWebSocketHandler;

  /** The shared handler for broadcasting structured bot state. */
  private final BotStateWebSocketHandler botStateWebSocketHandler;

  /** The shared handler for receiving tick batches from Micro6. */
  private final TickStreamWebSocketHandler tickStreamWebSocketHandler;

  /** The shared handler for broadcasting tick data to debugger UI. */
  private final DebuggerTickWebSocketHandler debuggerTickWebSocketHandler;

  /** The shared handler for broadcasting patch loop execution updates. */
  private final PatchLoopWebSocketHandler patchLoopWebSocketHandler;

  /** Server-configured frame ceiling shared by capability discovery and the WebSocket container. */
  private final GatewayCapabilitiesProperties gatewayCapabilitiesProperties;

  /**
   * Constructs the configuration with the injected handlers.
   *
   * @param logWebSocketHandler handler for log messages
   * @param stateWebSocketHandler handler for script running state
   * @param viewportWebSocketHandler handler for viewport updates
   * @param semanticWebSocketHandler handler for semantic state updates
   * @param tickStreamWebSocketHandler handler for Micro6 tick stream
   * @param debuggerTickWebSocketHandler handler for debugger tick broadcast
   */
  @Autowired
  public WebSocketConfig(
      LogWebSocketHandler logWebSocketHandler,
      WebSocketStateHandler stateWebSocketHandler,
      ViewportWebSocketHandler viewportWebSocketHandler,
      SemanticWebSocketHandler semanticWebSocketHandler,
      StatisticsWebSocketHandler statisticsWebSocketHandler,
      OverlayWebSocketHandler overlayWebSocketHandler,
      BotStateWebSocketHandler botStateWebSocketHandler,
      TickStreamWebSocketHandler tickStreamWebSocketHandler,
      DebuggerTickWebSocketHandler debuggerTickWebSocketHandler,
      PatchLoopWebSocketHandler patchLoopWebSocketHandler,
      GatewayCapabilitiesProperties gatewayCapabilitiesProperties) {
    this.logWebSocketHandler = logWebSocketHandler;
    this.stateWebSocketHandler = stateWebSocketHandler;
    this.viewportWebSocketHandler = viewportWebSocketHandler;
    this.semanticWebSocketHandler = semanticWebSocketHandler;
    this.statisticsWebSocketHandler = statisticsWebSocketHandler;
    this.overlayWebSocketHandler = overlayWebSocketHandler;
    this.botStateWebSocketHandler = botStateWebSocketHandler;
    this.tickStreamWebSocketHandler = tickStreamWebSocketHandler;
    this.debuggerTickWebSocketHandler = debuggerTickWebSocketHandler;
    this.patchLoopWebSocketHandler = patchLoopWebSocketHandler;
    this.gatewayCapabilitiesProperties = gatewayCapabilitiesProperties;
  }

  /**
   * Configures the WebSocket container with larger message buffer sizes.
   *
   * <p>The default Spring WebSocket message size limit is 64KB, which is too small for viewport
   * image data. This bean applies the bounded server-side gateway setting so the capability
   * manifest and effective container ceiling remain consistent.
   *
   * @return configured container factory bean
   */
  @Bean
  public ServletServerContainerFactoryBean createWebSocketContainer() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    int maxFrameBytes = gatewayCapabilitiesProperties.getMaxFrameBytes();
    container.setMaxTextMessageBufferSize(maxFrameBytes);
    container.setMaxBinaryMessageBufferSize(maxFrameBytes);
    return container;
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

    // Overlay stream
    registry.addHandler(overlayWebSocketHandler, "/ws/overlay").setAllowedOrigins("*");

    // Structured bot state stream
    registry.addHandler(botStateWebSocketHandler, "/ws/bot-state").setAllowedOrigins("*");

    // Micro6 tick stream ingestion
    registry
        .addHandler(tickStreamWebSocketHandler, "/ws/micro6/tick-stream")
        .setAllowedOrigins("*");

    // Debugger tick stream broadcast to UI
    registry
        .addHandler(debuggerTickWebSocketHandler, "/ws/debugger/tick-stream")
        .setAllowedOrigins("*");

    // Patch loop execution updates
    // Uses wildcard routing for execution-scoped connections (e.g., /ws/debug/patch-loop/exec-123)
    registry
        .addHandler(patchLoopWebSocketHandler, "/ws/debug/patch-loop", "/ws/debug/patch-loop/*")
        .setAllowedOrigins("*");
  }
}

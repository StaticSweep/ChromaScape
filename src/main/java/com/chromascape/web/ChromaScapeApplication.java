package com.chromascape.web;

import com.chromascape.utils.core.screen.viewport.ViewportManager;
import com.chromascape.utils.core.state.StateManager;
import com.chromascape.web.logs.LogWebSocketHandler;
import com.chromascape.web.logs.WebSocketLogAppender;
import com.chromascape.web.state.WebsocketBotStateListener;
import com.chromascape.web.viewport.WebsocketViewport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The main entry point for the ChromaScape Spring Boot application.
 *
 * <p>This class bootstraps the entire backend system, initializing all Spring components such as
 * REST controllers, services, and configuration classes.
 */
@SpringBootApplication
@EnableScheduling
public class ChromaScapeApplication {

  /**
   * Launches the ChromaScape application.
   *
   * @param args command-line arguments passed to the application
   */
  public static void main(String[] args) {
    // Disable headless mode to allow GUI components (e.g., MouseOverlay)
    System.setProperty("java.awt.headless", "false");
    SpringApplication.run(ChromaScapeApplication.class, args);
  }

  /**
   * Injects the {@link LogWebSocketHandler} bean into the {@link WebSocketLogAppender}.
   *
   * <p>This allows the {@link WebSocketLogAppender} to send log messages over WebSocket to
   * connected clients.
   *
   * @param handler the WebSocket handler responsible for sending log messages
   */
  @Autowired
  public void configureWebSocketHandler(LogWebSocketHandler handler) {
    WebSocketLogAppender.setWebSocketHandler(handler);
  }

  /**
   * Injects the {@link WebsocketViewport} into the {@link ViewportManager}.
   *
   * <p>This hooks the static ViewportManager usage in core utils to the Spring WebSocket
   * implementation.
   *
   * @param viewport the WebsocketViewport implementation
   */
  @Autowired
  public void configureViewport(WebsocketViewport viewport) {
    ViewportManager.setInstance(viewport);
  }

  /**
   * Injects the {@link WebsocketBotStateListener} into the {@link StateManager}.
   *
   * <p>This hooks the static StateManager usage in core utils to the Spring WebSocket
   * implementation.
   *
   * @param listener the WebsocketBotStateListener implementation
   */
  @Autowired
  public void configureStateManager(WebsocketBotStateListener listener) {
    StateManager.setListener(listener);
  }
}

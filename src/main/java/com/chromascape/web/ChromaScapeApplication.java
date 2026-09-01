package com.chromascape.web;

import com.chromascape.utils.core.screen.viewport.ViewportManager;
import com.chromascape.utils.core.state.StateManager;
import com.chromascape.web.logs.LogWebSocketHandler;
import com.chromascape.web.logs.WebSocketLogAppender;
import com.chromascape.web.state.WebsocketBotStateListener;
import com.chromascape.web.viewport.WebsocketViewport;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The main entry point for the ChromaScape Spring Boot application.
 *
 * <p>This class bootstraps the entire backend system, initializing all Spring components such as
 * REST controllers, services, and configuration classes.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"com.chromascape.web", "com.chromascape.utils"})
@EntityScan(basePackages = {"com.chromascape.web", "com.chromascape.utils"})
public class ChromaScapeApplication {

  /**
   * Launches the ChromaScape application.
   *
   * @param args command-line arguments passed to the application
   */
  public static void main(String[] args) {
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

  /**
   * Redirects System.out and System.err to Log4j2 loggers.
   *
   * <p>This ensures that all console output (System.out.println, System.err.println) is captured by
   * the logging framework and can be broadcast via WebSocket and stored in the database. System.out
   * is redirected to INFO level, System.err to ERROR level.
   *
   * <p>This method is called after Spring dependency injection is complete.
   */
  @PostConstruct
  public void redirectSystemStreams() {
    // Intentionally left empty. Redirecting System.out/System.err into Log4j causes
    // recursive logging when Log4j's own Console appender writes to standard streams.
  }
}

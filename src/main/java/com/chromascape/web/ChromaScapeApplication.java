package com.chromascape.web;

import com.chromascape.utils.core.runtime.ScriptProgressPublisher;
import com.chromascape.web.instance.WebSocketProgressHandler;
import com.chromascape.web.logs.LogWebSocketHandler;
import com.chromascape.web.logs.WebSocketLogAppender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The main entry point for the ChromaScape Spring Boot application.
 *
 * <p>This class bootstraps the entire backend system, initializing all Spring components such as
 * REST controllers, services, and configuration classes.
 */
@SpringBootApplication
public class ChromaScapeApplication {

  private static final Logger logger = LogManager.getLogger(ChromaScapeApplication.class);

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
   * Injects the {@link WebSocketProgressHandler} bean into the {@link ScriptProgressPublisher}.
   *
   * <p>This allows the {@link ScriptProgressPublisher} to publish progress updates of running
   * scripts over WebSocket to connected clients.
   *
   * @param handler the WebSocket handler responsible for sending script progress updates
   */
  @Autowired
  public void initProgressPublisher(WebSocketProgressHandler handler) {
    ScriptProgressPublisher.setWebSocketHandler(handler);
  }
}

package com.chromascape.web.logs;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

/**
 * A custom Log4j2 appender that broadcasts log messages to all connected WebSocket clients.
 *
 * <p>This appender is intended for use in a Spring Boot application where {@link
 * LogWebSocketHandler} manages client connections. Each log message emitted by Log4j2 will be sent
 * to every active WebSocket session via the {@code broadcast} method.
 *
 * <p>Typical usage involves registering this appender in {@code log4j2.xml} and configuring the
 * WebSocket handler via Spring. See documentation for wiring instructions.
 *
 * <pre>
 * Example XML registration:
 * &lt;Appenders&gt;
 *   &lt;WebSocketLogAppender name="WebSocket"/&gt;
 * &lt;/Appenders&gt;
 * </pre>
 *
 * <p>The WebSocket handler must be set using {@link #setWebSocketHandler(LogWebSocketHandler)}
 * after the Spring application context has fully initialized.
 *
 * @see LogWebSocketHandler
 */
@Plugin(
    name = "WebSocketLogAppender",
    category = "Core",
    elementType = Appender.ELEMENT_TYPE,
    printObject = true)
public class WebSocketLogAppender extends AbstractAppender {

  /**
   * The handler managing WebSocket sessions. This is set by Spring after context initialization.
   * Must be thread-safe.
   */
  private static LogWebSocketHandler webSocketHandler;

  /**
   * Allows Spring to inject the {@link LogWebSocketHandler} instance after application startup.
   * This method should be called from a Spring bean, typically in a {@code @Configuration} class.
   *
   * @param handler the shared WebSocket handler bean
   */
  public static void setWebSocketHandler(LogWebSocketHandler handler) {
    webSocketHandler = handler;
  }

  /**
   * Constructs the appender with the provided name. Other parameters (filter, layout) are omitted
   * for simplicity, but can be added if needed.
   *
   * @param name the appender name
   */
  protected WebSocketLogAppender(String name) {
    super(name, null, null, true, null);
    // Start the appender immediately on creation.
    start();
  }

  /**
   * Factory method required by Log4j2 for plugin discovery and instantiation via XML configuration.
   * This method is called when the appender is referenced in {@code log4j2.xml}.
   *
   * @param name the appender name as specified in XML
   * @return a new instance of {@link WebSocketLogAppender}
   */
  @PluginFactory
  public static WebSocketLogAppender createAppender(@PluginAttribute("name") String name) {
    return new WebSocketLogAppender(name);
  }

  /**
   * Called by Log4j2 for each log event. Broadcasts the formatted log message to all connected
   * WebSocket clients. If the handler is not set, the message is silently dropped.
   *
   * @param event the log event to append/broadcast
   */
  @Override
  public void append(LogEvent event) {
    if (webSocketHandler == null) {
      // Handler not configured; drop message.
      return;
    }
    String logMessage = event.getMessage().getFormattedMessage();
    try {
      webSocketHandler.broadcast(logMessage);
    } catch (Exception e) {
      System.err.println("Failed to broadcast log: " + e.getMessage());
    }
  }
}

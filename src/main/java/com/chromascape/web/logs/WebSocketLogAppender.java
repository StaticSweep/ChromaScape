package com.chromascape.web.logs;

import com.chromascape.utils.core.statistics.StatisticsManager;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
 * <p>The JSON payload includes: level, message, source, timestamp, and tick.
 *
 * @see LogWebSocketHandler
 */
@Plugin(
    name = "WebSocketLogAppender",
    category = "Core",
    elementType = Appender.ELEMENT_TYPE,
    printObject = true)
public class WebSocketLogAppender extends AbstractAppender {

  /** ISO 8601 formatter for timestamps in WebSocket payload. */
  private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;

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

    String level = event.getLevel().name();
    String message = event.getMessage().getFormattedMessage();
    String loggerName = event.getLoggerName();
    String timestamp = ISO_INSTANT.format(Instant.ofEpochMilli(event.getTimeMillis()));

    // Extract source/subsystem from logger name
    String source = extractSource(loggerName);

    // Add tick field from StatisticsManager
    int tick = StatisticsManager.getCycles();
    String json =
        String.format(
            "{\"level\": \"%s\", \"message\": \"%s\", \"source\": \"%s\", \"timestamp\": \"%s\", \"tick\": %d}",
            level, escapeJson(message), source, timestamp, tick);

    try {
      webSocketHandler.broadcast(json);
    } catch (Exception e) {
      // Use System.err to avoid recursive logging
      System.err.println("Failed to broadcast log: " + e.getMessage());
    }
  }

  /**
   * Extracts a simplified source/subsystem name from the logger name.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code com.chromascape.utils.core.WindowController} → {@code utils.core}
   *   <li>{@code com.chromascape.web.logs.LogWebSocketHandler} → {@code web.logs}
   *   <li>{@code System.out} → {@code System}
   *   <li>{@code System.err} → {@code System}
   * </ul>
   *
   * @param loggerName the full logger name from Log4j2
   * @return a simplified source identifier
   */
  private String extractSource(String loggerName) {
    if (loggerName == null || loggerName.isEmpty()) {
      return "unknown";
    }

    // Handle System.out and System.err specially
    if (loggerName.startsWith("System.")) {
      return "System";
    }

    String[] parts = loggerName.split("\\.");
    if (parts.length <= 2) {
      return loggerName;
    }

    // Extract ChromaScape subsystem: com.chromascape.utils.core.screen → utils.core
    if (parts.length >= 3 && parts[0].equals("com") && parts[1].equals("chromascape")) {
      String subsystem = parts[2]; // "utils", "web", or "scripts"
      if (parts.length >= 4) {
        return subsystem + "." + parts[3]; // e.g., "utils.core", "web.logs"
      }
      return subsystem;
    }

    // For external libraries or other packages, return the logger name
    return loggerName;
  }

  /**
   * Escapes special characters in text for JSON encoding.
   *
   * @param text the text to escape
   * @return JSON-safe text
   */
  private String escapeJson(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}

package com.chromascape.web.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles streaming and logging of MCP bridge process output.
 *
 * <p>This component captures stdout/stderr from the MCP bridge Node.js process and routes it to
 * ChromaScape's logging system with appropriate log levels based on content parsing.
 */
@Component
public class BridgeOutputStreamHandler {

  private static final Logger logger = LoggerFactory.getLogger("MCP-BRIDGE");

  /**
   * Streams output from the MCP bridge process to ChromaScape logs.
   *
   * <p>Runs in a background thread and parses each line to determine the appropriate log level.
   * ERROR/FATAL → error(), WARN → warn(), everything else → info().
   *
   * @param inputStream the process input stream (stdout/stderr)
   * @param streamName the name of the stream for thread identification
   */
  public void streamOutput(InputStream inputStream, String streamName) {
    new Thread(
            () -> {
              try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  logBridgeLine(line);
                }
              } catch (IOException e) {
                if (!e.getMessage().contains("Stream closed")) {
                  logger.error(
                      "Error reading bridge output from {}: {}", streamName, e.getMessage());
                }
              }
            },
            "MCP-Bridge-Logger-" + streamName)
        .start();
  }

  /**
   * Parses a bridge log line and logs it with the appropriate level.
   *
   * <p>Log level determination: - Lines containing "ERROR" or "FATAL" → ERROR - Lines containing
   * "WARN" → WARN - All other lines → INFO
   *
   * @param line the log line from the bridge process
   */
  private void logBridgeLine(String line) {
    if (line.contains("ERROR") || line.contains("FATAL")) {
      logger.error("[Bridge] {}", line);
    } else if (line.contains("WARN")) {
      logger.warn("[Bridge] {}", line);
    } else {
      logger.info("[Bridge] {}", line);
    }
  }
}

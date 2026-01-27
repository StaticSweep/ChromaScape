package com.chromascape.web.logs;

import java.io.OutputStream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

/**
 * Custom OutputStream that redirects console output (System.out/System.err) to Log4j2.
 *
 * <p>This class buffers characters until a newline is encountered, then logs the complete line
 * using the configured Log4j2 logger at the specified level. This allows System.out and System.err
 * messages to be captured by the logging framework and broadcast via WebSocket or stored in the
 * database.
 *
 * <p>Usage:
 *
 * <pre>
 * System.setOut(new PrintStream(new LogOutputStream(LogManager.getLogger("System.out"), Level.INFO)));
 * System.setErr(new PrintStream(new LogOutputStream(LogManager.getLogger("System.err"), Level.ERROR)));
 * </pre>
 */
public class LogOutputStream extends OutputStream {

  private final Logger logger;
  private final Level level;
  private final StringBuilder buffer = new StringBuilder();

  /**
   * Creates a LogOutputStream that writes to the specified logger at the given level.
   *
   * @param logger the Log4j2 logger to write to
   * @param level the log level to use (e.g., INFO, ERROR)
   */
  public LogOutputStream(Logger logger, Level level) {
    this.logger = logger;
    this.level = level;
  }

  /**
   * Writes a single byte to the output stream. If the byte is a newline, the buffered line is
   * flushed to the logger. Carriage return characters are ignored.
   *
   * @param b the byte to write
   */
  @Override
  public void write(int b) {
    if (b == '\n') {
      flush();
    } else if (b != '\r') {
      buffer.append((char) b);
    }
  }

  /**
   * Flushes the current buffer to the logger if it contains any content, then clears the buffer.
   */
  @Override
  public void flush() {
    if (buffer.length() > 0) {
      logger.log(level, buffer.toString());
      buffer.setLength(0);
    }
  }
}

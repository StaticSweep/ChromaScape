package com.chromascape.web.integration.micro6.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses log lines from micro6 client log files into structured data.
 *
 * <p>Expected format: {@code 2025-02-06 12:34:56.789 [INFO] [LoggerName] Message}
 *
 * <p>If the line cannot be parsed, returns a ParsedLogLine with the current timestamp and the
 * entire line as the message.
 */
public class LogLineParser {
  private static final Logger log = LoggerFactory.getLogger(LogLineParser.class);

  // Pattern: 2025-02-06 12:34:56.789 [INFO] [LoggerName] Message
  private static final Pattern LOG_PATTERN =
      Pattern.compile(
          "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[(\\w+)\\] (?:\\[([^\\]]+)\\] )?(.*)$");

  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  /**
   * Parse a log line into structured components.
   *
   * @param line the raw log line
   * @return parsed log line with timestamp, level, logger name, and message
   */
  public ParsedLogLine parse(String line) {
    Matcher matcher = LOG_PATTERN.matcher(line);
    if (!matcher.matches()) {
      // Unparseable line - return as RAW with current timestamp
      return new ParsedLogLine(LocalDateTime.now(), "INFO", null, line);
    }

    try {
      LocalDateTime timestamp = LocalDateTime.parse(matcher.group(1), TIMESTAMP_FORMATTER);
      String level = matcher.group(2);
      String loggerName = matcher.group(3);
      String message = matcher.group(4);

      return new ParsedLogLine(timestamp, level, loggerName, message);
    } catch (Exception e) {
      log.debug("Failed to parse timestamp, using current: {}", line);
      return new ParsedLogLine(LocalDateTime.now(), "INFO", null, line);
    }
  }

  /** Structured representation of a parsed log line. */
  public record ParsedLogLine(
      LocalDateTime timestamp, String level, String loggerName, String message) {}
}

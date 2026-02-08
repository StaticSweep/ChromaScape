package com.chromascape.web.integration.micro6.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;
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

  // Pattern A: 2026-02-06 03:47:05 CET [Thread] INFO logger - Message
  private static final Pattern LOG_PATTERN_TZ =
      Pattern.compile(
          "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?)\\s+(\\w{2,5})\\s+\\[([^\\]]+)]\\s+(\\w+)\\s+([^\\s]+)\\s+-\\s+(.*)$");

  // Pattern B: 2025-02-06 12:34:56.789 [INFO] [LoggerName] Message
  private static final Pattern LOG_PATTERN_SIMPLE =
      Pattern.compile(
          "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?)\\s+\\[(\\w+)\\]\\s+(?:\\[([^\\]]+)\\]\\s+)?(.*)$");

  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.MILLI_OF_SECOND, 1, 3, true)
          .optionalEnd()
          .toFormatter(Locale.US);

  /**
   * Parse a log line into structured components.
   *
   * @param line the raw log line
   * @return parsed log line with timestamp, level, logger name, and message
   */
  public ParsedLogLine parse(String line) {
    try {
      Matcher tzMatcher = LOG_PATTERN_TZ.matcher(line);
      if (tzMatcher.matches()) {
        String ts = tzMatcher.group(1);
        String tz = tzMatcher.group(2);
        String level = tzMatcher.group(4);
        String loggerName = tzMatcher.group(5);
        String message = tzMatcher.group(6);

        LocalDateTime localTimestamp = LocalDateTime.parse(ts, TIMESTAMP_FORMATTER);
        ZoneId zone = parseZone(tz);
        ZonedDateTime zoned = localTimestamp.atZone(zone);
        return new ParsedLogLine(zoned.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
            level, loggerName, message);
      }

      Matcher simpleMatcher = LOG_PATTERN_SIMPLE.matcher(line);
      if (simpleMatcher.matches()) {
        LocalDateTime timestamp = LocalDateTime.parse(simpleMatcher.group(1), TIMESTAMP_FORMATTER);
        String level = simpleMatcher.group(2);
        String loggerName = simpleMatcher.group(3);
        String message = simpleMatcher.group(4);
        return new ParsedLogLine(timestamp, level, loggerName, message);
      }
    } catch (Exception e) {
      log.debug("Failed to parse timestamp, using current: {}", line);
    }

    // Unparseable line - return as RAW with current timestamp
    return new ParsedLogLine(LocalDateTime.now(), "INFO", null, line);
  }

  private ZoneId parseZone(String tz) {
    if (tz == null || tz.isBlank()) {
      return ZoneId.systemDefault();
    }
    String upper = tz.toUpperCase(Locale.ROOT);
    if (upper.equals("UTC") || upper.equals("GMT")) {
      return ZoneOffset.UTC;
    }
    if (upper.equals("CET")) {
      return ZoneId.of("CET");
    }
    if (upper.equals("CEST")) {
      return ZoneId.of("CET");
    }
    try {
      return ZoneId.of(upper);
    } catch (Exception e) {
      return ZoneId.systemDefault();
    }
  }

  /** Structured representation of a parsed log line. */
  public record ParsedLogLine(
      LocalDateTime timestamp, String level, String loggerName, String message) {}
}

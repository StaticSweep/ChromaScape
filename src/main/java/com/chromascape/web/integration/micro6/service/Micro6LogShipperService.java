package com.chromascape.web.integration.micro6.service;

import com.chromascape.web.integration.micro6.Micro6IntegrationController.Micro6LogBatch;
import com.chromascape.web.integration.micro6.Micro6IntegrationController.Micro6LogEntry;
import com.chromascape.web.integration.micro6.config.Micro6Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service that tails the micro6 client log file and ships new log lines to ChromaScape's log
 * ingestion endpoint.
 *
 * <p>This captures menu clicks, network messages, and other client activity that isn't explicitly
 * POSTed via the Micro6IntegrationController.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Polls log file at configurable interval (default 5 seconds)
 *   <li>Detects file rotation and handles gracefully
 *   <li>Deduplicates lines to avoid sending duplicates during rotation
 *   <li>Batches logs for efficiency
 *   <li>Stops automatically after consecutive failures
 * </ul>
 */
@Service
public class Micro6LogShipperService {
  private static final Logger log = LoggerFactory.getLogger(Micro6LogShipperService.class);

  private final Micro6Config config;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "micro6-log-shipper");
            t.setDaemon(true);
            return t;
          });

  private LogFileTailer tailer;
  private LogLineParser parser;
  private LogDeduplicator deduplicator;
  private final List<Micro6LogEntry> logBuffer = new CopyOnWriteArrayList<>();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private int consecutiveFailures = 0;

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public Micro6LogShipperService(Micro6Config config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
  }

  /** Start the log shipper service if enabled and configured. */
  @PostConstruct
  public void start() {
    if (!config.getLogShipper().isEnabled()) {
      log.info("Micro6 log shipper is disabled");
      return;
    }

    String logFilePath = config.getLogShipper().getLogFilePath();
    if (logFilePath == null || logFilePath.trim().isEmpty()) {
      log.warn("Micro6 log shipper enabled but no log file path configured");
      return;
    }

    // Initialize components
    tailer = new LogFileTailer(logFilePath);
    parser = new LogLineParser();
    deduplicator = new LogDeduplicator(config.getLogShipper().getMaxHistoryLines());

    // Schedule polling task
    scheduler.scheduleAtFixedRate(
        this::pollAndShip, 0, config.getLogShipper().getPollingIntervalMs(), TimeUnit.MILLISECONDS);

    running.set(true);
    log.info(
        "Micro6 log shipper started: path={}, interval={}ms",
        logFilePath,
        config.getLogShipper().getPollingIntervalMs());
  }

  /** Poll the log file and ship new lines to the ingestion endpoint. */
  private void pollAndShip() {
    if (!running.get()) {
      return;
    }

    try {
      // 1. Read new lines from file
      List<String> newLines = tailer.readNewLines();
      if (newLines.isEmpty()) {
        // Flush buffer periodically even if no new lines
        if (!logBuffer.isEmpty()) {
          flushBuffer();
        }
        return;
      }

      // 2. Parse and deduplicate
      for (String line : newLines) {
        if (deduplicator.isDuplicate(line)) {
          continue;
        }

        LogLineParser.ParsedLogLine parsed = parser.parse(line);
        logBuffer.add(createLogEntry(parsed));
      }

      // 3. Flush buffer if size exceeded
      if (logBuffer.size() >= config.getLogShipper().getMaxBatchSize()) {
        flushBuffer();
      }

    } catch (IOException e) {
      log.error("Error polling log file: {}", e.getMessage());
      handleFailure();
    } catch (Exception e) {
      log.error("Unexpected error in log shipper", e);
      handleFailure();
    }
  }

  private Micro6LogEntry createLogEntry(LogLineParser.ParsedLogLine parsed) {
    Micro6LogEntry entry = new Micro6LogEntry();
    entry.setTimestamp(parsed.timestamp().toString());
    entry.setLevel(parsed.level());
    entry.setMessage(parsed.message());
    entry.setSource(config.getLogShipper().getSourceName());
    return entry;
  }

  /** Flush buffered logs to the ingestion endpoint. */
  private void flushBuffer() {
    if (logBuffer.isEmpty()) {
      return;
    }

    List<Micro6LogEntry> batch = new ArrayList<>(logBuffer);
    logBuffer.clear();

    Micro6LogBatch batchRequest = new Micro6LogBatch();
    batchRequest.setSource(config.getLogShipper().getSourceName());
    batchRequest.setLogs(batch);

    try {
      String json = objectMapper.writeValueAsString(batchRequest);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:8090/api/micro6/logs"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .timeout(Duration.ofSeconds(5))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 400) {
        log.warn("Failed to ship logs: HTTP {}", response.statusCode());
        handleFailure();
      } else {
        log.debug("Shipped {} logs", batch.size());
        consecutiveFailures = 0;
      }

    } catch (Exception e) {
      log.error("Error shipping logs: {}", e.getMessage());
      handleFailure();
      // Don't re-add to buffer - logs are lost on ship failure
    }
  }

  private void handleFailure() {
    consecutiveFailures++;
    if (consecutiveFailures >= 10) {
      log.error("Too many consecutive failures ({}), stopping log shipper", consecutiveFailures);
      running.set(false);
    }
  }

  /** Stop the log shipper and perform final cleanup. */
  @PreDestroy
  public void stop() {
    log.info("Stopping Micro6 log shipper");
    running.set(false);

    // Final flush
    if (!logBuffer.isEmpty()) {
      flushBuffer();
    }

    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }

    if (tailer != null) {
      tailer.close();
    }

    log.info("Micro6 log shipper stopped");
  }
}

package com.chromascape.web.integration.leeuwbot.service;

import com.chromascape.web.integration.leeuwbot.config.LeeuwBotConfig;
import com.chromascape.web.integration.leeuwbot.model.ChromaHeartbeat;
import com.chromascape.web.integration.leeuwbot.model.ChromaHeartbeat.HeartbeatStatus;
import com.chromascape.web.integration.leeuwbot.model.LogEntry;
import com.chromascape.web.integration.leeuwbot.model.ScriptEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for communicating with the LeeuwBot Bridge Service.
 *
 * <p>Handles heartbeat sending, log forwarding, and script event reporting.
 */
@Service
public class LeeuwBotBridgeService {

  private static final Logger logger = LogManager.getLogger(LeeuwBotBridgeService.class);

  private final LeeuwBotConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService scheduler;

  private final String instanceId;
  private final String hostname;
  private final Instant startTime;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong colorDetections = new AtomicLong(0);
  private final AtomicLong actionsPerformed = new AtomicLong(0);

  private volatile String currentScriptName;
  private volatile HeartbeatStatus currentStatus = HeartbeatStatus.IDLE;

  private final CopyOnWriteArrayList<LogEntry> logBuffer = new CopyOnWriteArrayList<>();

  /**
   * Creates a new LeeuwBotBridgeService.
   *
   * @param config the LeeuwBot configuration
   */
  @Autowired
  public LeeuwBotBridgeService(LeeuwBotConfig config) {
    this.config = config;
    this.instanceId = UUID.randomUUID().toString();
    this.startTime = Instant.now();
    this.hostname = getHostname();

    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());

    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.getConnectionTimeoutMs()))
            .build();

    this.scheduler = Executors.newScheduledThreadPool(2);

    logger.info(
        "LeeuwBotBridgeService initialized - instanceId: {}, enabled: {}",
        instanceId,
        config.isEnabled());
  }

  /** Starts the bridge service, beginning heartbeat sending and log forwarding. */
  public void start() {
    if (!config.isEnabled()) {
      logger.info("LeeuwBot integration is disabled");
      return;
    }

    if (running.getAndSet(true)) {
      logger.warn("LeeuwBotBridgeService is already running");
      return;
    }

    logger.info("Starting LeeuwBotBridgeService");

    // Schedule heartbeat sending
    scheduler.scheduleAtFixedRate(
        this::sendHeartbeat, 0, config.getHeartbeatIntervalSeconds(), TimeUnit.SECONDS);

    // Schedule log flushing
    if (config.isForwardLogs()) {
      scheduler.scheduleAtFixedRate(
          this::flushLogs,
          config.getLogFlushIntervalSeconds(),
          config.getLogFlushIntervalSeconds(),
          TimeUnit.SECONDS);
    }
  }

  /** Stops the bridge service. */
  public void stop() {
    if (!running.getAndSet(false)) {
      return;
    }

    logger.info("Stopping LeeuwBotBridgeService");
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Notifies the bridge that a script has started.
   *
   * @param scriptName the name of the script
   */
  public void onScriptStart(String scriptName) {
    this.currentScriptName = scriptName;
    this.currentStatus = HeartbeatStatus.RUNNING;

    if (config.isEnabled() && config.isReportScriptEvents()) {
      sendScriptEvent(ScriptEvent.start(instanceId, scriptName));
    }
  }

  /**
   * Notifies the bridge that a script has stopped.
   *
   * @param scriptName the name of the script
   */
  public void onScriptStop(String scriptName) {
    this.currentStatus = HeartbeatStatus.STOPPED;

    if (config.isEnabled() && config.isReportScriptEvents()) {
      sendScriptEvent(ScriptEvent.stop(instanceId, scriptName));
    }
  }

  /**
   * Notifies the bridge that a script has encountered an error.
   *
   * @param scriptName the name of the script
   * @param errorMessage the error message
   */
  public void onScriptError(String scriptName, String errorMessage) {
    this.currentStatus = HeartbeatStatus.ERROR;

    if (config.isEnabled() && config.isReportScriptEvents()) {
      sendScriptEvent(ScriptEvent.error(instanceId, scriptName, errorMessage));
    }
  }

  /** Increments the color detection counter. */
  public void recordColorDetection() {
    colorDetections.incrementAndGet();
  }

  /** Increments the actions performed counter. */
  public void recordAction() {
    actionsPerformed.incrementAndGet();
  }

  /**
   * Adds a log entry to be forwarded.
   *
   * @param entry the log entry
   */
  public void addLog(LogEntry entry) {
    if (!config.isEnabled() || !config.isForwardLogs()) {
      return;
    }

    entry.setInstanceId(instanceId);
    if (currentScriptName != null) {
      entry.setScriptName(currentScriptName);
    }

    logBuffer.add(entry);

    // Flush if buffer is full
    if (logBuffer.size() >= config.getLogBatchSize()) {
      flushLogs();
    }
  }

  /**
   * Returns the instance ID.
   *
   * @return the instance ID
   */
  public String getInstanceId() {
    return instanceId;
  }

  /**
   * Returns whether the service is running.
   *
   * @return true if running
   */
  public boolean isRunning() {
    return running.get();
  }

  /** Sends a heartbeat to the bridge service. */
  private void sendHeartbeat() {
    if (!config.isEnabled()) {
      return;
    }

    try {
      long uptimeSeconds = Duration.between(startTime, Instant.now()).getSeconds();

      ChromaHeartbeat heartbeat =
          new ChromaHeartbeat(instanceId)
              .withScriptName(currentScriptName)
              .withStatus(currentStatus)
              .withHost(hostname)
              .withUptimeSeconds(uptimeSeconds)
              .withMetadata("colorDetections", colorDetections.get())
              .withMetadata("actionsPerformed", actionsPerformed.get())
              .withMetadata("cpuLoad", getCpuLoad())
              .withMetadata("memUsedMb", getMemoryUsedMb());

      String json = objectMapper.writeValueAsString(heartbeat);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.getBridgeUrl() + "/api/v1/bridge/heartbeat"))
              .header("Content-Type", "application/json")
              .header("X-API-Key", config.getApiKey())
              .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 400) {
        logger.warn("Heartbeat failed with status: {}", response.statusCode());
      } else {
        logger.debug("Heartbeat sent successfully");
      }

    } catch (Exception e) {
      logger.warn("Failed to send heartbeat: {}", e.getMessage());
    }
  }

  /**
   * Sends a script event to the bridge service.
   *
   * @param event the script event
   */
  private void sendScriptEvent(ScriptEvent event) {
    if (!config.isEnabled()) {
      return;
    }

    try {
      String json = objectMapper.writeValueAsString(event);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.getBridgeUrl() + "/api/v1/bridge/event"))
              .header("Content-Type", "application/json")
              .header("X-API-Key", config.getApiKey())
              .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 400) {
        logger.warn("Script event failed with status: {}", response.statusCode());
      } else {
        logger.debug("Script event sent: {}", event.getEventType());
      }

    } catch (Exception e) {
      logger.warn("Failed to send script event: {}", e.getMessage());
    }
  }

  /** Flushes buffered logs to the bridge service. */
  private void flushLogs() {
    if (!config.isEnabled() || !config.isForwardLogs() || logBuffer.isEmpty()) {
      return;
    }

    List<LogEntry> logsToSend = new ArrayList<>(logBuffer);
    logBuffer.clear();

    try {
      Map<String, Object> batch = new HashMap<>();
      batch.put("instanceId", instanceId);
      batch.put("logs", logsToSend);

      String json = objectMapper.writeValueAsString(batch);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.getBridgeUrl() + "/api/v1/bridge/logs"))
              .header("Content-Type", "application/json")
              .header("X-API-Key", config.getApiKey())
              .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 400) {
        logger.warn("Log flush failed with status: {}", response.statusCode());
        // Re-add logs to buffer on failure
        logBuffer.addAll(logsToSend);
      } else {
        logger.debug("Flushed {} logs", logsToSend.size());
      }

    } catch (Exception e) {
      logger.warn("Failed to flush logs: {}", e.getMessage());
      // Re-add logs to buffer on failure
      logBuffer.addAll(logsToSend);
    }
  }

  /**
   * Gets the hostname of this machine.
   *
   * @return the hostname
   */
  private String getHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }

  /**
   * Gets the current CPU load.
   *
   * @return the CPU load as a fraction (0.0 to 1.0)
   */
  private double getCpuLoad() {
    try {
      java.lang.management.OperatingSystemMXBean osBean =
          java.lang.management.ManagementFactory.getOperatingSystemMXBean();
      if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
        return ((com.sun.management.OperatingSystemMXBean) osBean).getCpuLoad();
      }
    } catch (Exception e) {
      // Ignore
    }
    return 0.0;
  }

  /**
   * Gets the memory used in megabytes.
   *
   * @return the memory used in MB
   */
  private int getMemoryUsedMb() {
    Runtime runtime = Runtime.getRuntime();
    long usedMemory = runtime.totalMemory() - runtime.freeMemory();
    return (int) (usedMemory / (1024 * 1024));
  }
}

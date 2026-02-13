package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage.TickLogEntry;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage.TickOverlayEntry;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlay;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarker;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.integration.leeuwbot.config.LeeuwBotConfig;
import com.chromascape.web.integration.leeuwbot.service.LeeuwBotBridgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Service for processing tick batch messages from Micro6.
 *
 * <p>Saves tick data (snapshots, logs, overlays) to PostgreSQL and broadcasts to live debugger
 * clients.
 */
@Service
public class TickStreamIngestionService {

  private static final Logger log = LoggerFactory.getLogger(TickStreamIngestionService.class);

  private final DebugSessionService sessionService;
  private final TickSnapshotRepository snapshotRepository;
  private final TickLogRepository logRepository;
  private final TickOverlayRepository overlayRepository;
  private final TimelineMarkerRepository markerRepository;
  private final ObjectMapper objectMapper;
  private final LeeuwBotBridgeService leeuwBotBridgeService;
  private final LeeuwBotConfig leeuwBotConfig;
  private final HttpClient httpClient;

  private final List<WebSocketSession> debuggerClients = new ArrayList<>();

  public TickStreamIngestionService(
      DebugSessionService sessionService,
      TickSnapshotRepository snapshotRepository,
      TickLogRepository logRepository,
      TickOverlayRepository overlayRepository,
      TimelineMarkerRepository markerRepository,
      ObjectMapper objectMapper,
      @Autowired(required = false) LeeuwBotBridgeService leeuwBotBridgeService,
      @Autowired(required = false) LeeuwBotConfig leeuwBotConfig) {
    this.sessionService = sessionService;
    this.snapshotRepository = snapshotRepository;
    this.logRepository = logRepository;
    this.overlayRepository = overlayRepository;
    this.markerRepository = markerRepository;
    this.objectMapper = objectMapper;
    this.leeuwBotBridgeService = leeuwBotBridgeService;
    this.leeuwBotConfig = leeuwBotConfig;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(5000)).build();
  }

  /**
   * Process a tick batch message.
   *
   * <p>Saves snapshot, logs, and overlays to the database. Auto-generates timeline markers for
   * errors. Broadcasts to live debugger clients.
   *
   * @param batch the tick batch message
   */
  @Async
  @Transactional
  public void processTickBatch(TickBatchMessage batch) {
    try {
      String sessionId = batch.getSessionId();

      // Verify session exists
      if (sessionService.getSession(sessionId).isEmpty()) {
        log.warn(
            "Received tick batch for unknown session: {}. Active sessions: {}",
            sessionId,
            sessionService.getActiveSessions().size());
        return;
      }

      long tick = batch.getTick();

      // Parse timestamp
      LocalDateTime timestamp = parseTimestamp(batch.getTimestamp());

      // 1. Broadcast immediately to live debugger clients (before DB writes to reduce lag)
      broadcastToDebuggerClients(batch);

      // 2. Save tick snapshot
      saveTickSnapshot(batch, timestamp);

      // 3. Save tick logs and collect errors for markers
      List<TickLogEntry> logs = batch.getLogs();
      if (logs != null && !logs.isEmpty()) {
        List<TickLog> tickLogs = saveTickLogs(sessionId, tick, timestamp, logs);

        // Auto-generate markers for errors
        for (TickLog tickLog : tickLogs) {
          if (tickLog.isError()) {
            createErrorMarker(sessionId, tick, tickLog);
          }
        }
      }

      // 4. Save tick overlays
      List<TickOverlayEntry> overlays = batch.getOverlays();
      if (overlays != null && !overlays.isEmpty()) {
        saveTickOverlays(sessionId, tick, overlays);
      }

      // 5. Update session tick bounds
      sessionService.updateSessionTickBounds(sessionId, tick);

      // 6. Forward to Go backend if enabled
      forwardToGoBackend(batch);

      log.info("Processed tick batch: sessionId={}, tick={}", sessionId, tick);

    } catch (Exception e) {
      log.error("Error processing tick batch: {}", e.getMessage(), e);
    }
  }

  /** Save a tick snapshot to the database. */
  private void saveTickSnapshot(TickBatchMessage batch, LocalDateTime timestamp) {
    try {
      JsonNode playerState = objectMapper.valueToTree(batch.getPlayerState());
      JsonNode inventory =
          batch.getInventory() != null ? objectMapper.valueToTree(batch.getInventory()) : null;

      // Log inventory data for debugging
      log.info(
          "Processing tick {} inventory: {} items",
          batch.getTick(),
          batch.getInventory() != null ? batch.getInventory().size() : 0);

      JsonNode equipment =
          batch.getEquipment() != null ? objectMapper.valueToTree(batch.getEquipment()) : null;
      JsonNode threats =
          batch.getThreats() != null ? objectMapper.valueToTree(batch.getThreats()) : null;
      JsonNode projectiles =
          batch.getProjectiles() != null ? objectMapper.valueToTree(batch.getProjectiles()) : null;
      JsonNode cooldowns =
          batch.getCooldowns() != null ? objectMapper.valueToTree(batch.getCooldowns()) : null;
      JsonNode quest = batch.getQuest() != null ? objectMapper.valueToTree(batch.getQuest()) : null;

      // NEW: Convert expanded world state to JSON
      JsonNode npcs = batch.getNpcs() != null ? objectMapper.valueToTree(batch.getNpcs()) : null;
      JsonNode gameObjects =
          batch.getGameObjects() != null ? objectMapper.valueToTree(batch.getGameObjects()) : null;
      JsonNode groundObjects =
          batch.getGroundObjects() != null
              ? objectMapper.valueToTree(batch.getGroundObjects())
              : null;
      JsonNode groundItems =
          batch.getGroundItems() != null ? objectMapper.valueToTree(batch.getGroundItems()) : null;

      // Log expanded data for diagnostics
      log.info(
          "Processing tick {} expanded data: npcs={}, gameObjects={}, groundObjects={}, groundItems={}",
          batch.getTick(),
          batch.getNpcs() != null ? batch.getNpcs().size() : 0,
          batch.getGameObjects() != null ? batch.getGameObjects().size() : 0,
          batch.getGroundObjects() != null ? batch.getGroundObjects().size() : 0,
          batch.getGroundItems() != null ? batch.getGroundItems().size() : 0);

      // Diagnostic: Log missing major sections to identify Micro6 payload gaps
      List<String> missingSections = new ArrayList<>();
      if (batch.getPlayerState() == null) missingSections.add("playerState");
      if (batch.getInventory() == null) missingSections.add("inventory");
      if (batch.getNpcs() == null && batch.getThreats() == null) missingSections.add("npcs");
      if (batch.getProjectiles() == null) missingSections.add("projectiles");
      if (batch.getGameObjects() == null) missingSections.add("gameObjects");
      if (batch.getGroundObjects() == null) missingSections.add("groundObjects");
      if (batch.getGroundItems() == null) missingSections.add("groundItems");
      if (batch.getQuest() == null) missingSections.add("quest");

      if (!missingSections.isEmpty()) {
        log.debug(
            "Tick {} missing sections: {}",
            batch.getTick(),
            String.join(", ", missingSections));
      }

      TickSnapshot snapshot =
          new TickSnapshot(batch.getSessionId(), batch.getTick(), timestamp, playerState);

      snapshot.setInventory(inventory);
      snapshot.setEquipment(equipment);
      snapshot.setThreats(threats);
      snapshot.setProjectiles(projectiles);
      snapshot.setCooldowns(cooldowns);
      snapshot.setQuest(quest);
      snapshot.setNpcs(npcs);
      snapshot.setGameObjects(gameObjects);
      snapshot.setGroundObjects(groundObjects);
      snapshot.setGroundItems(groundItems);

      snapshotRepository.save(snapshot);

    } catch (Exception e) {
      log.error("Error saving tick snapshot: {}", e.getMessage(), e);
    }
  }

  /** Save tick logs to the database. */
  private List<TickLog> saveTickLogs(
      String sessionId, long tick, LocalDateTime timestamp, List<TickLogEntry> logs) {

    List<TickLog> savedLogs = new ArrayList<>();

    for (TickLogEntry entry : logs) {
      try {
        TickLog tickLog =
            new TickLog(
                sessionId,
                tick,
                timestamp,
                entry.getSubsystem() != null ? entry.getSubsystem() : "System",
                entry.getLevel() != null ? entry.getLevel() : "Info",
                entry.getMessage());

        tickLog.setEventType(entry.getEventType());

        if (entry.getDetails() != null) {
          tickLog.setDetails(objectMapper.valueToTree(entry.getDetails()));
        }

        if (entry.getCorrelationId() != null) {
          try {
            tickLog.setCorrelationId(UUID.fromString(entry.getCorrelationId()));
          } catch (IllegalArgumentException e) {
            // Invalid UUID, ignore
          }
        }

        savedLogs.add(logRepository.save(tickLog));

      } catch (Exception e) {
        log.error("Error saving tick log: {}", e.getMessage(), e);
      }
    }

    return savedLogs;
  }

  /** Save tick overlays to the database. */
  private void saveTickOverlays(String sessionId, long tick, List<TickOverlayEntry> overlays) {
    for (TickOverlayEntry entry : overlays) {
      try {
        JsonNode data = objectMapper.valueToTree(entry.getData());

        TickOverlay overlay = new TickOverlay(sessionId, tick, entry.getType(), data);

        overlayRepository.save(overlay);

      } catch (Exception e) {
        log.error("Error saving tick overlay: {}", e.getMessage(), e);
      }
    }
  }

  /** Create a timeline marker for an error log. */
  private void createErrorMarker(String sessionId, long tick, TickLog tickLog) {
    try {
      TimelineMarker marker =
          new TimelineMarker(sessionId, tick, "ERROR", "Error: " + tickLog.getSubsystem());

      marker.setSeverity("Error");
      marker.setDescription(tickLog.getMessage());
      marker.setLogId(tickLog.getId());
      marker.setIsAutoGenerated(true);

      markerRepository.save(marker);

    } catch (Exception e) {
      log.error("Error creating error marker: {}", e.getMessage(), e);
    }
  }

  /** Broadcast tick data to live debugger clients. */
  private void broadcastToDebuggerClients(TickBatchMessage batch) {
    if (debuggerClients.isEmpty()) {
      return;
    }

    try {
      String message = objectMapper.writeValueAsString(batch);
      TextMessage textMessage = new TextMessage(message);

      for (WebSocketSession client : debuggerClients) {
        if (client.isOpen()) {
          synchronized (client) {
            client.sendMessage(textMessage);
          }
        }
      }

    } catch (Exception e) {
      log.error("Error broadcasting to debugger clients: {}", e.getMessage(), e);
    }
  }

  /** Register a debugger WebSocket client for live tick updates. */
  public void registerDebuggerClient(WebSocketSession session) {
    debuggerClients.add(session);
    log.info("Registered debugger client: {}", session.getId());
  }

  /** Unregister a debugger WebSocket client. */
  public void unregisterDebuggerClient(WebSocketSession session) {
    debuggerClients.remove(session);
    log.info("Unregistered debugger client: {}", session.getId());
  }

  /** Forward tick batch to Go backend if enabled. */
  private void forwardToGoBackend(TickBatchMessage batch) {
    // Check if forwarding is enabled
    if (leeuwBotConfig == null || !leeuwBotConfig.isForwardTickData()) {
      return;
    }

    // Check if bridge service is available
    if (leeuwBotBridgeService == null || !leeuwBotBridgeService.isRunning()) {
      log.debug("Go backend forwarding disabled or not available");
      return;
    }

    try {
      String json = objectMapper.writeValueAsString(batch);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(leeuwBotConfig.getBridgeUrl() + "/api/v1/ticks/batch"))
              .header("Content-Type", "application/json")
              .header("X-API-Key", leeuwBotConfig.getApiKey())
              .timeout(Duration.ofMillis(leeuwBotConfig.getTickForwardTimeoutMs()))
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 400) {
        log.warn("Go backend tick forwarding failed with status: {}", response.statusCode());
      } else {
        log.debug("Tick batch forwarded to Go backend: tick={}", batch.getTick());
      }

    } catch (Exception e) {
      log.warn("Failed to forward tick batch to Go backend: {}", e.getMessage());
      // Data is already saved locally, so no action needed
    }
  }

  /** Parse an ISO-8601 timestamp string to LocalDateTime. */
  private LocalDateTime parseTimestamp(String timestamp) {
    try {
      if (timestamp != null && !timestamp.isEmpty()) {
        Instant instant = Instant.parse(timestamp);
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
      }
    } catch (Exception e) {
      log.warn("Failed to parse timestamp: {}", timestamp);
    }
    return LocalDateTime.now();
  }
}

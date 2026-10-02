package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalRepairIncidentService;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage.TickLogEntry;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage.TickOverlayEntry;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  /** Optional for lightweight legacy/unit construction; present in the application context. */
  @Autowired(required = false)
  private ReplayRecordingActionIngestionService actionIngestionService;

  @Autowired(required = false)
  private BuilderGoalRepairIncidentService repairIncidentService;

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
    this(
        sessionService,
        snapshotRepository,
        logRepository,
        overlayRepository,
        markerRepository,
        objectMapper,
        leeuwBotBridgeService,
        leeuwBotConfig,
        null);
  }

  /** Constructor used by the application and by focused ingestion tests. */
  @Autowired
  public TickStreamIngestionService(
      DebugSessionService sessionService,
      TickSnapshotRepository snapshotRepository,
      TickLogRepository logRepository,
      TickOverlayRepository overlayRepository,
      TimelineMarkerRepository markerRepository,
      ObjectMapper objectMapper,
      @Autowired(required = false) LeeuwBotBridgeService leeuwBotBridgeService,
      @Autowired(required = false) LeeuwBotConfig leeuwBotConfig,
      @Autowired(required = false) ReplayRecordingActionIngestionService actionIngestionService) {
    this.sessionService = sessionService;
    this.snapshotRepository = snapshotRepository;
    this.logRepository = logRepository;
    this.overlayRepository = overlayRepository;
    this.markerRepository = markerRepository;
    this.objectMapper = objectMapper;
    this.leeuwBotBridgeService = leeuwBotBridgeService;
    this.leeuwBotConfig = leeuwBotConfig;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(5000)).build();
    this.actionIngestionService = actionIngestionService;
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
      Map<String, Object> normalizedPs = normalizePlayerState(batch.getPlayerState());
      if (batch.getMicrobotStatus() != null) {
        normalizedPs.put("microbotStatus", batch.getMicrobotStatus());
      }
      if (batch.getActiveTaskKey() != null) {
        normalizedPs.put("activeTaskKey", batch.getActiveTaskKey());
      }
      if (batch.getActivePluginClasses() != null) {
        normalizedPs.put("activePluginClasses", batch.getActivePluginClasses());
      }
      batch.setPlayerState(normalizedPs);
      validateStrictV2(batch);
      String sessionId = batch.getSessionId();

      // Verify session exists
      Optional<DebugSession> session = sessionService.getSession(sessionId);
      if (session.isEmpty()) {
        log.warn(
            "Received tick batch for unknown session: {}. Active sessions: {}",
            sessionId,
            sessionService.getActiveSessions().size());
        return;
      }

      // Persist typed actions before any derived replay artifact is built. The ingestion service
      // performs ownership, schema, checksum, duplicate, and conflict validation. Legacy logs are
      // still persisted below and are intentionally not used as a replacement for this stream.
      if (actionIngestionService != null
          && batch.getActions() != null
          && !batch.getActions().isEmpty()) {
        actionIngestionService.ingest(batch, session.get().getRecordingId());
      }

      long tick = batch.getTick();

      // Parse timestamp
      LocalDateTime timestamp = parseTimestamp(batch.getTimestamp());

      // 1. Broadcast immediately to live debugger clients (before DB writes to reduce lag)
      broadcastToDebuggerClients(batch);

      // 2. Save tick snapshot
      saveTickSnapshot(batch, timestamp);

      if (repairIncidentService != null) {
        repairIncidentService.observe(batch);
      }

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
      JsonNode questHelper =
          batch.getQuestHelper() != null ? objectMapper.valueToTree(batch.getQuestHelper()) : null;

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
      JsonNode players =
          batch.getPlayers() != null ? objectMapper.valueToTree(batch.getPlayers()) : null;
      JsonNode widgets =
          batch.getWidgets() != null ? objectMapper.valueToTree(batch.getWidgets()) : null;

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
      if (batch.getQuestHelper() == null) missingSections.add("questHelper");

      if (!missingSections.isEmpty()) {
        log.debug(
            "Tick {} missing sections: {}", batch.getTick(), String.join(", ", missingSections));
      }

      TickSnapshot snapshot =
          new TickSnapshot(batch.getSessionId(), batch.getTick(), timestamp, playerState);

      snapshot.setInventory(inventory);
      snapshot.setEquipment(equipment);
      snapshot.setThreats(threats);
      snapshot.setProjectiles(projectiles);
      snapshot.setCooldowns(cooldowns);
      snapshot.setQuest(quest);
      snapshot.setQuestHelper(questHelper);
      snapshot.setNpcs(npcs);
      snapshot.setGameObjects(gameObjects);
      snapshot.setGroundObjects(groundObjects);
      snapshot.setGroundItems(groundItems);
      snapshot.setPlayers(players);
      snapshot.setWidgets(widgets);
      snapshot.setSchemaVersion(batch.getSchemaVersion());

      snapshotRepository.save(snapshot);

    } catch (Exception e) {
      log.error("Error saving tick snapshot: {}", e.getMessage(), e);
    }
  }

  private void validateStrictV2(TickBatchMessage batch) {
    // V3 was already validated and durably accepted by the handler. Its redacted projection
    // intentionally lacks raw sections required by legacy v2; never fabricate those sections.
    if (Integer.valueOf(3).equals(batch.getSchemaVersion()) && batch.getCanonicalFrame() != null)
      return;

    if (batch.getSchemaVersion() == null || batch.getSchemaVersion() < 2) {
      throw new IllegalArgumentException("Strict replay requires schemaVersion >= 2");
    }
    if (batch.getSessionId() == null || batch.getSessionId().isBlank()) {
      throw new IllegalArgumentException("Missing sessionId");
    }
    if (batch.getPlayerState() == null) {
      throw new IllegalArgumentException("Missing playerState");
    }
    Map<String, Object> ps = batch.getPlayerState();
    if (!hasValidPlayerPosition(ps)) {
      throw new IllegalArgumentException("Missing required player position (position.x/y or x/z)");
    }
    if (batch.getInventory() == null || batch.getEquipment() == null) {
      throw new IllegalArgumentException("Missing inventory/equipment");
    }
    if (batch.getNpcs() == null
        || batch.getGameObjects() == null
        || batch.getGroundObjects() == null
        || batch.getGroundItems() == null
        || batch.getPlayers() == null
        || batch.getWidgets() == null
        || batch.getQuest() == null
        || batch.getCooldowns() == null) {
      throw new IllegalArgumentException("Missing required strict replay sections");
    }
  }

  private boolean hasValidPlayerPosition(Map<String, Object> playerState) {
    return hasValidNestedPosition(playerState) || hasValidFlatPosition(playerState);
  }

  @SuppressWarnings("unchecked")
  private boolean hasValidNestedPosition(Map<String, Object> playerState) {
    Object posObj = playerState.get("position");
    if (!(posObj instanceof Map)) {
      return false;
    }
    Map<String, Object> pos = (Map<String, Object>) posObj;
    return hasCoordinateValue(pos, "x")
        && (hasCoordinateValue(pos, "y") || hasCoordinateValue(pos, "z"))
        && !hasInvalidPlane(pos);
  }

  private boolean hasValidFlatPosition(Map<String, Object> playerState) {
    boolean hasX =
        hasCoordinateValue(playerState, "x") || hasCoordinateValue(playerState, "worldX");
    boolean hasY =
        hasCoordinateValue(playerState, "y")
            || hasCoordinateValue(playerState, "z")
            || hasCoordinateValue(playerState, "worldY");
    return hasX && hasY && !hasInvalidPlane(playerState);
  }

  private boolean hasValue(Map<String, Object> map, String key) {
    return map != null && map.containsKey(key) && map.get(key) != null;
  }

  private boolean hasCoordinateValue(Map<String, Object> map, String key) {
    return map != null && map.containsKey(key) && normalizeCoordinate(map.get(key)) != null;
  }

  private Object firstCoordinateValue(Map<String, Object> map, String... keys) {
    if (map == null) {
      return null;
    }
    for (String key : keys) {
      Object coordinate = normalizeCoordinate(map.get(key));
      if (coordinate != null) {
        return coordinate;
      }
    }
    return null;
  }

  private Integer normalizeCoordinate(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      String trimmed = text.trim();
      if (trimmed.isEmpty()) {
        return null;
      }
      try {
        return Integer.parseInt(trimmed);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private boolean hasInvalidPlane(Map<String, Object> map) {
    return map != null
        && map.containsKey("plane")
        && map.get("plane") != null
        && normalizeCoordinate(map.get("plane")) == null;
  }

  private void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (target != null && value != null) {
      target.put(key, value);
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

  /**
   * Normalize playerState to include both canonical nested fields and legacy flat aliases.
   *
   * <p>If the incoming payload has flat fields (x, z, currentHp, etc.) without nested containers,
   * this method synthesizes the nested containers. If nested containers exist but flat fields are
   * missing, it fills in the flat aliases. The result is a consistent map usable by both storage
   * and replay.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> normalizePlayerState(Map<String, Object> playerState) {
    if (playerState == null) {
      return null;
    }

    // Copy into a mutable LinkedHashMap to preserve readable field order
    Map<String, Object> normalized = new LinkedHashMap<>(playerState);

    // --- Position normalization ---
    Object positionObj = normalized.get("position");
    Map<String, Object> position =
        positionObj instanceof Map ? new LinkedHashMap<>((Map<String, Object>) positionObj) : null;
    if (position != null) {
      normalized.put("position", position);
    }

    boolean hasValidNestedPos = hasValidNestedPosition(normalized);
    boolean hasValidFlatPos = hasValidFlatPosition(normalized);

    if (!hasValidNestedPos && hasValidFlatPos) {
      // Synthesize nested position from flat coordinates
      Map<String, Object> newPos = new LinkedHashMap<>();
      Object x = firstCoordinateValue(normalized, "x", "worldX");
      Object y = firstCoordinateValue(normalized, "y", "z", "worldY");
      Object z = firstCoordinateValue(normalized, "z", "y", "worldY");
      Object plane = firstCoordinateValue(normalized, "plane");
      putIfPresent(newPos, "x", x);
      putIfPresent(newPos, "y", y);
      putIfPresent(newPos, "z", z);
      newPos.put("plane", plane != null ? plane : 0);
      normalized.put("position", newPos);
      position = newPos;
      addFlatPositionAliases(normalized, position);
    } else if (hasValidNestedPos) {
      putIfPresent(position, "x", firstCoordinateValue(position, "x"));
      // Derive missing y from z, or missing z from y; don't zero-fill.
      putIfPresent(position, "y", firstCoordinateValue(position, "y", "z"));
      putIfPresent(position, "z", firstCoordinateValue(position, "z", "y"));
      Object plane = firstCoordinateValue(position, "plane");
      if (plane == null) {
        plane = firstCoordinateValue(normalized, "plane");
      }
      position.put("plane", plane != null ? plane : 0);
      normalized.put("position", position);
      addFlatPositionAliases(normalized, position);
    }

    // --- HP normalization ---
    if (!(normalized.get("hp") instanceof Map) && normalized.containsKey("currentHp")) {
      Map<String, Object> hp = new LinkedHashMap<>();
      int current = toInt(normalized.get("currentHp"));
      int max = toInt(normalized.get("maxHp"));
      hp.put("current", current);
      hp.put("max", max);
      hp.put("percent", max > 0 ? (current * 100 / max) : 0);
      normalized.put("hp", hp);
    }

    // --- Prayer normalization ---
    if (!(normalized.get("prayer") instanceof Map) && normalized.containsKey("currentPrayer")) {
      Map<String, Object> prayer = new LinkedHashMap<>();
      int current = toInt(normalized.get("currentPrayer"));
      int max = toInt(normalized.get("maxPrayer"));
      prayer.put("current", current);
      prayer.put("max", max);
      prayer.put("percent", max > 0 ? (current * 100 / max) : 0);
      normalized.put("prayer", prayer);
    }

    // --- Energy normalization ---
    if (!normalized.containsKey("energy") && normalized.containsKey("runEnergy")) {
      normalized.put("energy", normalized.get("runEnergy"));
    }
    if (!normalized.containsKey("runEnergy") && normalized.containsKey("energy")) {
      normalized.put("runEnergy", normalized.get("energy"));
    }

    // --- Animation normalization ---
    if (!normalized.containsKey("animation") && normalized.containsKey("animationId")) {
      normalized.put("animation", normalized.get("animationId"));
    }
    if (!normalized.containsKey("animationId") && normalized.containsKey("animation")) {
      normalized.put("animationId", normalized.get("animation"));
    }

    return normalized;
  }

  /** Add the legacy flat aliases that can be derived from a valid nested position. */
  private void addFlatPositionAliases(
      Map<String, Object> normalized, Map<String, Object> position) {
    Object x = firstCoordinateValue(position, "x");
    Object y = firstCoordinateValue(position, "y", "z");
    Object z = firstCoordinateValue(position, "z", "y");

    putIfPresent(normalized, "x", x);
    putIfPresent(normalized, "z", z);
    putIfPresent(normalized, "worldX", x);
    putIfPresent(normalized, "worldY", y);
    putIfPresent(normalized, "plane", firstCoordinateValue(position, "plane"));
  }

  private int toInt(Object value) {
    return value instanceof Number ? ((Number) value).intValue() : 0;
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

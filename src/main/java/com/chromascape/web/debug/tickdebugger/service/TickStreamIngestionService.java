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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
  private final com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceiptRepository
      batchReceiptRepository;
  private final TickBatchCanonicalChecksumService tickBatchChecksumService;

  /** Result sent to Micro6 only after the surrounding transaction commits successfully. */
  public record TickBatchAcknowledgment(
      String batchId, String sessionId, long tick, String payloadChecksum, String status) {}

  /** Optional for lightweight legacy/unit construction; present in the application context. */
  @Autowired(required = false)
  private ReplayRecordingActionIngestionService actionIngestionService;

  /** Packet observation store; present in the application context after V39. */
  @Autowired(required = false)
  private com.chromascape.web.debug.tickdebugger.entity.PacketObservationRepository
      packetRepository;

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
        null,
        null);
  }

  /** Constructor used by the application and by focused ingestion tests. */
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
    this(
        sessionService,
        snapshotRepository,
        logRepository,
        overlayRepository,
        markerRepository,
        objectMapper,
        leeuwBotBridgeService,
        leeuwBotConfig,
        actionIngestionService,
        null);
  }

  /** Constructor with the durable batch receipt boundary and canonical checksum service. */
  public TickStreamIngestionService(
      DebugSessionService sessionService,
      TickSnapshotRepository snapshotRepository,
      TickLogRepository logRepository,
      TickOverlayRepository overlayRepository,
      TimelineMarkerRepository markerRepository,
      ObjectMapper objectMapper,
      @Autowired(required = false) LeeuwBotBridgeService leeuwBotBridgeService,
      @Autowired(required = false) LeeuwBotConfig leeuwBotConfig,
      @Autowired(required = false) ReplayRecordingActionIngestionService actionIngestionService,
      @Autowired(required = false)
          com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceiptRepository
              batchReceiptRepository) {
    this(
        sessionService,
        snapshotRepository,
        logRepository,
        overlayRepository,
        markerRepository,
        objectMapper,
        leeuwBotBridgeService,
        leeuwBotConfig,
        actionIngestionService,
        batchReceiptRepository,
        null);
  }

  /** Constructor with the durable batch receipt boundary. */
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
      @Autowired(required = false) ReplayRecordingActionIngestionService actionIngestionService,
      @Autowired(required = false)
          com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceiptRepository
              batchReceiptRepository,
      @Autowired(required = false) TickBatchCanonicalChecksumService tickBatchChecksumService) {
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
    this.batchReceiptRepository = batchReceiptRepository;
    this.tickBatchChecksumService =
        tickBatchChecksumService != null
            ? tickBatchChecksumService
            : new TickBatchCanonicalChecksumService(objectMapper);
  }

  /**
   * Process a tick batch message.
   *
   * <p>Saves snapshot, logs, and overlays to the database. Auto-generates timeline markers for
   * errors. Broadcasts to live debugger clients.
   *
   * @param batch the tick batch message
   */
  @Transactional
  public TickBatchAcknowledgment processTickBatch(TickBatchMessage batch) {
    // Compatibility for direct internal callers. WebSocket ingestion must pass the original
    // parsed JsonNode through the overload below so DTO normalization cannot change the contract.
    JsonNode legacyWireDocument = objectMapper.valueToTree(batch);
    removeNullFieldsForLegacyCaller(legacyWireDocument);
    return processTickBatch(batch, legacyWireDocument);
  }

  /** Processes a batch against the exact JSON document received from the WebSocket. */
  @Transactional
  public TickBatchAcknowledgment processTickBatch(
      TickBatchMessage batch, JsonNode originalWireDocument) {
    try {
      java.util.UUID batchId = null;
      if (batchReceiptRepository != null) {
        // Verify immutable wire identity before normalization changes the in-memory DTO.
        batchId = requireBatchIdentity(batch);
        validatePayloadChecksum(batch, originalWireDocument);
      }
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
        throw new IllegalArgumentException("Unknown debug session: " + sessionId);
      }

      // SC-1618: schema-3 packet validation runs before any persistence so a bad
      // envelope fails the transaction with a stable permanent code and no partial rows.
      validatePackets(batch);

      if (batchReceiptRepository != null) {
        Optional<com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceipt> existing =
            batchReceiptRepository.findBySessionIdAndBatchId(sessionId, batchId);
        if (existing.isPresent()) {
          ensureReceiptMatches(existing.get(), batch);
          return acknowledgment(existing.get());
        }
        Optional<com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceipt> tickWinner =
            batchReceiptRepository.findBySessionIdAndTick(sessionId, batch.getTick());
        if (tickWinner.isPresent()) {
          ensureReceiptMatches(tickWinner.get(), batch);
          return acknowledgment(tickWinner.get());
        }
        int inserted =
            batchReceiptRepository.insertIfAbsent(
                sessionId,
                batchId,
                batch.getTick(),
                batch.getPayloadChecksum(),
                "tick-batch/v2",
                LocalDateTime.now(ZoneId.of("UTC")));
        if (inserted == 0) {
          com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceipt winner =
              batchReceiptRepository
                  .findBySessionIdAndBatchId(sessionId, batchId)
                  .or(
                      () ->
                          batchReceiptRepository.findBySessionIdAndTick(sessionId, batch.getTick()))
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Batch receipt conflict could not be resolved"));
          ensureReceiptMatches(winner, batch);
          return acknowledgment(winner);
        }
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

      // All evidence is written before the transaction returns. The WebSocket handler emits the
      // acknowledgment only after this method's transaction commits.
      // 1. Save tick snapshot
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

      // 4b. Save packet observations in the same transaction, before the ACK.
      // Duplicate batch retries hit the receipt early-return above and never reach
      // here, so rows are inserted exactly once.
      if (batch.getPackets() != null && !batch.getPackets().isEmpty()) {
        saveTickPackets(sessionId, timestamp, batch);
      }

      // 5. Update session tick bounds
      sessionService.updateSessionTickBounds(sessionId, tick);
      registerPostCommitPublication(batch);

      log.info(
          "Processed tick batch: sessionId={}, tick={}, batchId={}",
          sessionId,
          tick,
          batch.getBatchId());
      return new TickBatchAcknowledgment(
          batch.getBatchId(), sessionId, tick, batch.getPayloadChecksum(), "PERSISTED");

    } catch (Exception e) {
      log.error("Error processing tick batch: {}", e.getMessage(), e);
      throw e;
    }
  }

  private java.util.UUID requireBatchIdentity(TickBatchMessage batch) {
    if (batch.getBatchId() == null || batch.getBatchId().isBlank()) {
      throw new IllegalArgumentException("Missing batchId");
    }
    try {
      return java.util.UUID.fromString(batch.getBatchId());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("batchId must be a UUID", e);
    }
  }

  /** Compatibility-only normalization for direct pre-v2 internal callers. */
  private void removeNullFieldsForLegacyCaller(JsonNode node) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      List<String> nullFields = new ArrayList<>();
      object
          .fields()
          .forEachRemaining(
              entry -> {
                if (entry.getValue() == null || entry.getValue().isNull()) {
                  nullFields.add(entry.getKey());
                } else {
                  removeNullFieldsForLegacyCaller(entry.getValue());
                }
              });
      nullFields.forEach(object::remove);
    } else if (node.isArray()) {
      node.forEach(this::removeNullFieldsForLegacyCaller);
    }
  }

  private void validatePayloadChecksum(TickBatchMessage batch, JsonNode originalWireDocument) {
    if (originalWireDocument == null || !originalWireDocument.isObject()) {
      throw new IllegalArgumentException("tick batch wire document must be an object");
    }
    if (batch.getPayloadChecksum() == null
        || !batch.getPayloadChecksum().matches("^sha256:[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("payloadChecksum must be sha256:<64 lowercase hex>");
    }
    validateWireIdentity(batch, originalWireDocument);
    String expected = tickBatchChecksumService.checksum(originalWireDocument);
    if (!batch.getPayloadChecksum().equals(expected)) {
      throw new IllegalArgumentException("BATCH_CHECKSUM_CONFLICT");
    }
  }

  private void validateWireIdentity(TickBatchMessage batch, JsonNode wire) {
    requireWireText(wire, "type", batch.getType());
    JsonNode schemaVersion = wire.get("schemaVersion");
    if (schemaVersion == null
        || !schemaVersion.isIntegralNumber()
        || schemaVersion.intValue() != batch.getSchemaVersion()) {
      throw new IllegalArgumentException("BATCH_WIRE_IDENTITY_MISMATCH");
    }
    requireWireText(wire, "batchId", batch.getBatchId());
    requireWireText(wire, "sessionId", batch.getSessionId());
    if (!wire.has("tick")
        || !wire.get("tick").isIntegralNumber()
        || !wire.get("tick").canConvertToLong()
        || wire.get("tick").asLong() != batch.getTick()) {
      throw new IllegalArgumentException("BATCH_WIRE_IDENTITY_MISMATCH");
    }
    requireWireText(wire, "payloadChecksum", batch.getPayloadChecksum());
  }

  private void requireWireText(JsonNode wire, String field, String expected) {
    JsonNode value = wire.get(field);
    if (expected == null
        || value == null
        || value.isNull()
        || !value.isTextual()
        || !expected.equals(value.textValue())) {
      throw new IllegalArgumentException("BATCH_WIRE_IDENTITY_MISMATCH");
    }
  }

  private void ensureReceiptMatches(
      com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceipt receipt,
      TickBatchMessage batch) {
    if (!receipt.getSessionId().equals(batch.getSessionId())) {
      throw new IllegalArgumentException("BATCH_SESSION_MISMATCH");
    }
    if (receipt.getTick() != batch.getTick()
        || !receipt.getBatchId().equals(UUID.fromString(batch.getBatchId()))
        || !receipt.getPayloadChecksum().equals(batch.getPayloadChecksum())) {
      throw new IllegalArgumentException("BATCH_CHECKSUM_CONFLICT");
    }
  }

  private void registerPostCommitPublication(TickBatchMessage batch) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      // Direct unit construction bypasses Spring's @Transactional proxy. Keep those tests useful
      // without weakening the production boundary: in the application context this branch is
      // unreachable and publication remains strictly post-commit.
      broadcastToDebuggerClients(batch);
      forwardToGoBackend(batch);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            broadcastToDebuggerClients(batch);
            forwardToGoBackend(batch);
          }
        });
  }

  private TickBatchAcknowledgment acknowledgment(
      com.chromascape.web.debug.tickdebugger.entity.ReplayTickBatchReceipt receipt) {
    return new TickBatchAcknowledgment(
        receipt.getBatchId().toString(),
        receipt.getSessionId(),
        receipt.getTick(),
        receipt.getPayloadChecksum(),
        "PERSISTED");
  }

  /** Save a tick snapshot to the database. */
  private void saveTickSnapshot(TickBatchMessage batch, LocalDateTime timestamp) {
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
    JsonNode dialogue =
        batch.getDialogue() != null ? objectMapper.valueToTree(batch.getDialogue()) : null;
    JsonNode spells =
        batch.getSpells() != null ? objectMapper.valueToTree(batch.getSpells()) : null;
    JsonNode vars = batch.getVars() != null ? objectMapper.valueToTree(batch.getVars()) : null;

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
    if (batch.getDialogue() == null) missingSections.add("dialogue");
    if (batch.getSpells() == null) missingSections.add("spells");
    if (batch.getVars() == null) missingSections.add("vars");

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
    snapshot.setDialogue(dialogue);
    snapshot.setSpells(spells);
    snapshot.setVars(vars);
    snapshot.setNpcs(npcs);
    snapshot.setGameObjects(gameObjects);
    snapshot.setGroundObjects(groundObjects);
    snapshot.setGroundItems(groundItems);
    snapshot.setPlayers(players);
    snapshot.setWidgets(widgets);
    snapshot.setSchemaVersion(batch.getSchemaVersion());

    snapshotRepository.save(snapshot);
  }

  private void validateStrictV2(TickBatchMessage batch) {
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
  private static final int MAX_PACKETS_PER_BATCH = 512;

  private static final long MAX_BATCH_PACKET_BYTES = 512L * 1024;
  private static final int MAX_DECODED_BYTES = 64 * 1024;
  private static final int MAX_DECODED_DEPTH = 8;

  /** Packet tick must fall within this window around the enclosing batch tick. */
  private static final long PACKET_TICK_WINDOW = 600;

  /**
   * Validates schema-3 packet envelopes before persistence. Schema-v2 batches carry no packets and
   * skip silently. Failures are permanent (IllegalArgumentException with a stable code);
   * storage/transient failures surface separately as retryable persistence errors.
   */
  private void validatePackets(TickBatchMessage batch) {
    List<TickBatchMessage.TickPacketEntry> packets = batch.getPackets();
    if (packets == null || packets.isEmpty()) {
      return;
    }
    if (packets.size() > MAX_PACKETS_PER_BATCH) {
      throw new IllegalArgumentException("PACKET_BATCH_LIMIT_EXCEEDED");
    }
    long totalBytes = 0;
    java.util.Map<String, Long> lastSequenceBySource = new java.util.HashMap<>();
    for (TickBatchMessage.TickPacketEntry packet : packets) {
      if (packet == null) {
        throw new IllegalArgumentException("PACKET_ENVELOPE_INVALID");
      }
      if (!"rsprox-packet/v1".equals(packet.getSchema())) {
        throw new IllegalArgumentException("PACKET_SCHEMA_UNSUPPORTED");
      }
      String direction = packet.getDirection();
      if (!"CLIENT_TO_SERVER".equals(direction) && !"SERVER_TO_CLIENT".equals(direction)) {
        throw new IllegalArgumentException("PACKET_DIRECTION_INVALID");
      }
      String status = packet.getDecodeStatus();
      if (!"DECODED".equals(status)
          && !"UNKNOWN_PROTOCOL".equals(status)
          && !"DECODE_ERROR".equals(status)
          && !"GAP".equals(status)) {
        throw new IllegalArgumentException("PACKET_STATUS_INVALID");
      }
      if (packet.getSourceSessionId() == null || packet.getSourceSessionId().isBlank()) {
        throw new IllegalArgumentException("PACKET_SOURCE_INVALID");
      }
      if (packet.getSequence() == null || packet.getSequence() < 0) {
        throw new IllegalArgumentException("PACKET_SEQUENCE_INVALID");
      }
      if (packet.getOpcode() == null || packet.getOpcode() < 0) {
        throw new IllegalArgumentException("PACKET_OPCODE_INVALID");
      }
      if (packet.getEncodedSize() == null || packet.getEncodedSize() < 0) {
        throw new IllegalArgumentException("PACKET_SIZE_INVALID");
      }
      if (packet.getTick() == null
          || Math.abs(packet.getTick() - batch.getTick()) > PACKET_TICK_WINDOW) {
        throw new IllegalArgumentException("PACKET_TICK_OUT_OF_RANGE");
      }
      if (packet.getProtocolName() == null
          || packet.getProtocolName().isBlank()
          || packet.getProtocolName().length() > 128) {
        throw new IllegalArgumentException("PACKET_PROTOCOL_INVALID");
      }
      if (packet.getDecodedType() != null && packet.getDecodedType().length() > 128) {
        throw new IllegalArgumentException("PACKET_DECODED_TYPE_INVALID");
      }
      if (packet.getDecodeError() != null && packet.getDecodeError().length() > 512) {
        throw new IllegalArgumentException("PACKET_DECODE_ERROR_INVALID");
      }
      if (packet.getDecoded() != null) {
        JsonNode decodedNode = objectMapper.valueToTree(packet.getDecoded());
        int size;
        try {
          size = objectMapper.writeValueAsBytes(decodedNode).length;
        } catch (Exception e) {
          throw new IllegalArgumentException("PACKET_DECODED_INVALID");
        }
        if (size > MAX_DECODED_BYTES) {
          throw new IllegalArgumentException("PACKET_DECODED_TOO_LARGE");
        }
        if (jsonDepth(decodedNode, 0) > MAX_DECODED_DEPTH) {
          throw new IllegalArgumentException("PACKET_DECODED_TOO_DEEP");
        }
        totalBytes += size;
      }
      if (packet.getSanitizedPayloadBase64() != null
          && packet.getSanitizedPayloadBase64().length() > MAX_DECODED_BYTES) {
        throw new IllegalArgumentException("PACKET_PAYLOAD_TOO_LARGE");
      }
      // Monotonic sequence per source session across the whole received list: interleaved
      // sources each keep their own high-water mark, so a duplicate or regression on any
      // one source is rejected even when other sources' packets sit between the two rows.
      Long previousSequence = lastSequenceBySource.get(packet.getSourceSessionId());
      if (previousSequence != null && packet.getSequence() <= previousSequence) {
        throw new IllegalArgumentException("PACKET_SEQUENCE_NOT_MONOTONIC");
      }
      lastSequenceBySource.put(packet.getSourceSessionId(), packet.getSequence());
    }
    if (totalBytes > MAX_BATCH_PACKET_BYTES) {
      throw new IllegalArgumentException("PACKET_BATCH_BYTES_EXCEEDED");
    }
  }

  private int jsonDepth(JsonNode node, int depth) {
    if (node == null || node.isNull() || node.isValueNode()) {
      return depth;
    }
    int max = depth;
    if (node.isArray()) {
      for (JsonNode item : node) {
        max = Math.max(max, jsonDepth(item, depth + 1));
      }
      return max;
    }
    var fields = node.fields();
    while (fields.hasNext()) {
      max = Math.max(max, jsonDepth(fields.next().getValue(), depth + 1));
    }
    return max;
  }

  /** Persists packet rows idempotently; conflicting reuse fails permanently. */
  private void saveTickPackets(String sessionId, LocalDateTime timestamp, TickBatchMessage batch) {
    if (packetRepository == null) {
      throw new IllegalStateException("BATCH_PERSISTENCE_FAILED: packet store unavailable");
    }
    for (TickBatchMessage.TickPacketEntry packet : batch.getPackets()) {
      var existing =
          packetRepository.findBySessionIdAndSourceSessionIdAndPacketSequence(
              sessionId, packet.getSourceSessionId(), packet.getSequence());
      if (existing.isPresent()) {
        ensurePacketMatches(existing.get(), sessionId, batch.getTick(), packet);
        continue;
      }
      com.chromascape.web.debug.tickdebugger.entity.PacketObservation row =
          new com.chromascape.web.debug.tickdebugger.entity.PacketObservation(
              sessionId, packet.getTick(), packet.getSourceSessionId(), packet.getSequence());
      row.setCapturedAt(parseTimestamp(packet.getCapturedAt()));
      row.setCaptureDeltaMillis(packet.getCaptureDeltaMillis());
      row.setDirection(packet.getDirection());
      row.setOpcode(packet.getOpcode());
      row.setProtocolName(packet.getProtocolName());
      row.setEncodedSize(packet.getEncodedSize());
      row.setDecodedType(packet.getDecodedType());
      if (packet.getDecoded() != null) {
        row.setDecodedPayload(objectMapper.valueToTree(packet.getDecoded()));
      }
      if (packet.getSanitizedPayloadBase64() != null) {
        try {
          row.setSanitizedPayload(
              java.util.Base64.getDecoder().decode(packet.getSanitizedPayloadBase64()));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("PACKET_PAYLOAD_INVALID");
        }
      }
      row.setDecodeStatus(packet.getDecodeStatus());
      row.setDecodeError(packet.getDecodeError());
      row.setSynthetic(Boolean.TRUE.equals(packet.getSynthetic()));
      try {
        packetRepository.saveAndFlush(row);
      } catch (org.springframework.dao.DataIntegrityViolationException e) {
        // Lost-ACK retry racing a concurrent insert: re-read and compare idempotently.
        var winner =
            packetRepository
                .findBySessionIdAndSourceSessionIdAndPacketSequence(
                    sessionId, packet.getSourceSessionId(), packet.getSequence())
                .orElseThrow(() -> e);
        ensurePacketMatches(winner, sessionId, batch.getTick(), packet);
      }
    }
  }

  private void ensurePacketMatches(
      com.chromascape.web.debug.tickdebugger.entity.PacketObservation row,
      String sessionId,
      long batchTick,
      TickBatchMessage.TickPacketEntry packet) {
    if (!row.getSessionId().equals(sessionId)
        || !row.getSourceSessionId().equals(packet.getSourceSessionId())
        || !row.getPacketSequence().equals(packet.getSequence())
        || !java.util.Objects.equals(row.getTick(), packet.getTick())
        || !java.util.Objects.equals(row.getDirection(), packet.getDirection())
        || !java.util.Objects.equals(row.getOpcode(), packet.getOpcode())
        || !java.util.Objects.equals(row.getProtocolName(), packet.getProtocolName())
        || !java.util.Objects.equals(row.getEncodedSize(), packet.getEncodedSize())
        || !java.util.Objects.equals(row.getDecodedType(), packet.getDecodedType())
        || !java.util.Objects.equals(row.getDecodeStatus(), packet.getDecodeStatus())
        || !java.util.Objects.equals(row.getDecodeError(), packet.getDecodeError())
        || !java.util.Objects.equals(row.getCaptureDeltaMillis(), packet.getCaptureDeltaMillis())
        || row.isSynthetic() != Boolean.TRUE.equals(packet.getSynthetic())
        || !decodedPayloadMatches(row.getDecodedPayload(), packet.getDecoded())
        || !sanitizedPayloadMatches(row.getSanitizedPayload(), packet.getSanitizedPayloadBase64())
        || !capturedAtMatches(row.getCapturedAt(), packet.getCapturedAt())) {
      throw new IllegalArgumentException("PACKET_IDENTITY_CONFLICT");
    }
  }

  private boolean decodedPayloadMatches(JsonNode stored, Map<String, Object> incoming) {
    JsonNode incomingNode = incoming == null ? null : objectMapper.valueToTree(incoming);
    if (stored == null || stored.isNull()) {
      return incomingNode == null || incomingNode.isNull();
    }
    return stored.equals(incomingNode);
  }

  private boolean sanitizedPayloadMatches(byte[] stored, String incomingBase64) {
    if (stored == null && (incomingBase64 == null || incomingBase64.isEmpty())) {
      return true;
    }
    if (stored == null || incomingBase64 == null) {
      return false;
    }
    try {
      return java.util.Arrays.equals(stored, java.util.Base64.getDecoder().decode(incomingBase64));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private boolean capturedAtMatches(LocalDateTime stored, String incoming) {
    if (stored == null && (incoming == null || incoming.isEmpty())) {
      return true;
    }
    if (stored == null || incoming == null) {
      return false;
    }
    try {
      return stored.equals(parseTimestamp(incoming));
    } catch (Exception e) {
      return false;
    }
  }

  /** Save tick logs to the database. */
  private List<TickLog> saveTickLogs(
      String sessionId, long tick, LocalDateTime timestamp, List<TickLogEntry> logs) {

    List<TickLog> savedLogs = new ArrayList<>();

    for (TickLogEntry entry : logs) {
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
    }

    return savedLogs;
  }

  /** Save tick overlays to the database. */
  private void saveTickOverlays(String sessionId, long tick, List<TickOverlayEntry> overlays) {
    for (TickOverlayEntry entry : overlays) {
      JsonNode data = objectMapper.valueToTree(entry.getData());
      TickOverlay overlay = new TickOverlay(sessionId, tick, entry.getType(), data);
      overlayRepository.save(overlay);
    }
  }

  /** Create a timeline marker for an error log. */
  private void createErrorMarker(String sessionId, long tick, TickLog tickLog) {
    TimelineMarker marker =
        new TimelineMarker(sessionId, tick, "ERROR", "Error: " + tickLog.getSubsystem());

    marker.setSeverity("Error");
    marker.setDescription(tickLog.getMessage());
    marker.setLogId(tickLog.getId());
    marker.setIsAutoGenerated(true);

    markerRepository.save(marker);
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
      // Packets stay out of the optional Go forwarding path by default; the local
      // evidence is already durable and the Go contract has no versioned packet shape.
      com.fasterxml.jackson.databind.node.ObjectNode goNode = objectMapper.valueToTree(batch);
      goNode.remove("packets");
      String json = objectMapper.writeValueAsString(goNode);

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

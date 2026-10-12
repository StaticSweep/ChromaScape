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
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

  /** Uses the transaction's JDBC connection to batch packet inserts in the running application. */
  @Autowired(required = false)
  private JdbcTemplate packetJdbcTemplate;

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
      // Canonical v3 frames already committed through CanonicalFrameStore. Their legacy
      // projection must not create a second receipt or validate a redacted payload as v2.
      boolean canonicalProjection =
          Integer.valueOf(3).equals(batch.getSchemaVersion()) && batch.getCanonicalFrame() != null;
      java.util.UUID batchId = null;
      if (batchReceiptRepository != null && !canonicalProjection) {
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
      Optional<DebugSession> session = sessionService.getSessionForTickIngestion(sessionId);
      if (session.isEmpty()) {
        throw new IllegalArgumentException("Unknown debug session: " + sessionId);
      }

      // SC-1618: schema-3 packet validation runs before any persistence so a bad
      // envelope fails the transaction with a stable permanent code and no partial rows.
      validatePackets(batch);

      if (batchReceiptRepository != null && !canonicalProjection) {
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
      }

      // Exact committed retries remain ACKable after closure. New evidence must never extend
      // a stopped/failed recording, including late batches already queued on the client.
      if (!session.get().acceptsTickEvidence()) {
        throw new IllegalArgumentException("BATCH_SESSION_CLOSED: " + sessionId);
      }

      if (batchReceiptRepository != null && !canonicalProjection) {
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
    JsonNode inputs =
        batch.getInputs() != null ? objectMapper.valueToTree(batch.getInputs()) : null;
    JsonNode tiles = batch.getTiles() != null ? objectMapper.valueToTree(batch.getTiles()) : null;
    JsonNode hazards =
        batch.getHazards() != null ? objectMapper.valueToTree(batch.getHazards()) : null;
    JsonNode phase = batch.getPhase() != null ? objectMapper.valueToTree(batch.getPhase()) : null;

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
    if (batch.getInputs() == null) missingSections.add("inputs");
    if (batch.getTiles() == null) missingSections.add("tiles");
    if (batch.getHazards() == null) missingSections.add("hazards");
    if (batch.getPhase() == null) missingSections.add("phase");

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
    snapshot.setInputs(inputs);
    snapshot.setTiles(tiles);
    snapshot.setHazards(hazards);
    snapshot.setPhase(phase);

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
    // V3 was already validated and durably accepted by the handler. Its redacted projection
    // intentionally lacks raw sections required by legacy v2; never fabricate those sections.
    if (Integer.valueOf(3).equals(batch.getSchemaVersion()) && batch.getCanonicalFrame() != null)
      return;

    if (batch.getSchemaVersion() == null
        || (batch.getSchemaVersion() != 2
            && batch.getSchemaVersion() != 3
            && batch.getSchemaVersion() != 4)) {
      throw new IllegalArgumentException("Unsupported schemaVersion; expected 2, 3, or 4");
    }
    if (batch.getSchemaVersion() == 2
        && batch.getPackets() != null
        && !batch.getPackets().isEmpty()) {
      throw new IllegalArgumentException("SCHEMA_V2_PACKETS_UNSUPPORTED");
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
  private static final int MAX_DECODED_NODES = 4096;
  private static final int MAX_DECODED_KEYS = 2048;

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
    if (batch.getSchemaVersion() != 3 && batch.getSchemaVersion() != 4) {
      throw new IllegalArgumentException("SCHEMA_V2_PACKETS_UNSUPPORTED");
    }
    if (packets.size() > MAX_PACKETS_PER_BATCH) {
      throw new IllegalArgumentException("PACKET_BATCH_LIMIT_EXCEEDED");
    }
    long totalBytes = 0;
    // A source session normally emits one schema, but keep the schema in the local ordering key
    // so a rolling v1/v2 upgrade cannot make an otherwise valid legacy sequence look regressive.
    java.util.Map<String, Long> lastSequenceBySourceAndSchema = new java.util.HashMap<>();
    for (TickBatchMessage.TickPacketEntry packet : packets) {
      if (packet == null) {
        throw new IllegalArgumentException("PACKET_ENVELOPE_INVALID");
      }
      boolean v1 = "rsprox-packet/v1".equals(packet.getSchema());
      boolean v2 = "rsprox-packet/v2".equals(packet.getSchema());
      if (!v1 && !v2) {
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
      if (packet.getSourceSessionId().length() > 100) {
        throw new IllegalArgumentException("PACKET_SOURCE_INVALID");
      }
      if (packet.getSequence() == null || packet.getSequence() < 0) {
        throw new IllegalArgumentException("PACKET_SEQUENCE_INVALID");
      }
      if (packet.getCaptureDeltaMillis() == null || packet.getCaptureDeltaMillis() < 0) {
        throw new IllegalArgumentException("PACKET_CAPTURE_DELTA_INVALID");
      }
      if (packet.getCapturedAt() != null) {
        try {
          parsePacketTimestamp(packet.getCapturedAt());
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("PACKET_TIMESTAMP_INVALID", e);
        }
      }
      if (v2) {
        if ("GAP".equals(status)) {
          if (packet.getPacketSequence() != null || !Boolean.TRUE.equals(packet.getSynthetic())) {
            throw new IllegalArgumentException("PACKET_GAP_IDENTITY_INVALID");
          }
        } else if (packet.getPacketSequence() == null || packet.getPacketSequence() < 0) {
          throw new IllegalArgumentException("PACKET_CAPTURE_SEQUENCE_INVALID");
        }
      } else if (packet.getPacketSequence() != null) {
        // v1 has one legacy identity. Do not silently reinterpret it as a v2 pair.
        throw new IllegalArgumentException("PACKET_V1_CAPTURE_SEQUENCE_INVALID");
      }
      if ("GAP".equals(status) && !Boolean.TRUE.equals(packet.getSynthetic())) {
        throw new IllegalArgumentException("PACKET_GAP_SYNTHETIC_INVALID");
      }
      if (packet.getOpcode() == null || packet.getOpcode() < 0) {
        throw new IllegalArgumentException("PACKET_OPCODE_INVALID");
      }
      if (packet.getEncodedSize() == null || packet.getEncodedSize() < 0) {
        throw new IllegalArgumentException("PACKET_SIZE_INVALID");
      }
      if (packet.getTick() == null
          || tickDistanceExceedsWindow(packet.getTick(), batch.getTick())) {
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
        JsonNode decodedNode;
        int size;
        try {
          decodedNode = objectMapper.valueToTree(packet.getDecoded());
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
        if (jsonNodeCount(decodedNode) > MAX_DECODED_NODES) {
          throw new IllegalArgumentException("PACKET_DECODED_TOO_MANY_NODES");
        }
        if (jsonKeyCount(decodedNode) > MAX_DECODED_KEYS) {
          throw new IllegalArgumentException("PACKET_DECODED_TOO_MANY_KEYS");
        }
        totalBytes += size;
      }
      if (packet.getSanitizedPayloadBase64() != null) {
        byte[] sanitizedPayload;
        try {
          sanitizedPayload =
              java.util.Base64.getDecoder().decode(packet.getSanitizedPayloadBase64());
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("PACKET_PAYLOAD_INVALID", e);
        }
        if (sanitizedPayload.length > MAX_DECODED_BYTES) {
          throw new IllegalArgumentException("PACKET_PAYLOAD_TOO_LARGE");
        }
        totalBytes += sanitizedPayload.length;
      }
      // v2 real observations are strictly monotonic per source. A delayed v2 GAP is a diagnostic
      // marker and may arrive after the real observations it describes; persistence identity and
      // content comparison still make conflicting reuse permanent. Legacy v1 GAPs retain their
      // historical marker semantics and do not advance the v1 high-water mark.
      String orderingKey = packet.getSourceSessionId() + "\u0000" + packet.getSchema();
      Long previousSequence = lastSequenceBySourceAndSchema.get(orderingKey);
      boolean delayedV2Gap = v2 && "GAP".equals(status);
      if (previousSequence != null
          && packet.getSequence() <= previousSequence
          && !delayedV2Gap
          && !(v1 && "GAP".equals(status))) {
        throw new IllegalArgumentException("PACKET_SEQUENCE_NOT_MONOTONIC");
      }
      if (previousSequence == null || packet.getSequence() > previousSequence) {
        lastSequenceBySourceAndSchema.put(orderingKey, packet.getSequence());
      }
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

  private int jsonNodeCount(JsonNode node) {
    if (node == null || node.isValueNode()) {
      return node == null ? 0 : 1;
    }
    int count = 1;
    if (node.isArray()) {
      for (JsonNode item : node) {
        count += jsonNodeCount(item);
      }
    } else {
      var fields = node.fields();
      while (fields.hasNext()) {
        count += jsonNodeCount(fields.next().getValue());
      }
    }
    return count;
  }

  private int jsonKeyCount(JsonNode node) {
    if (node == null || node.isValueNode()) {
      return 0;
    }
    int count = 0;
    if (node.isArray()) {
      for (JsonNode item : node) {
        count += jsonKeyCount(item);
      }
      return count;
    }
    var fields = node.fields();
    while (fields.hasNext()) {
      count++;
      count += jsonKeyCount(fields.next().getValue());
    }
    return count;
  }

  /** Compares tick distance without allowing signed-long subtraction to wrap around. */
  private boolean tickDistanceExceedsWindow(long packetTick, long batchTick) {
    if (packetTick == batchTick) {
      return false;
    }
    long distance = packetTick > batchTick ? packetTick - batchTick : batchTick - packetTick;
    // A negative result means the positive mathematical distance exceeded Long.MAX_VALUE and
    // wrapped during subtraction; that is necessarily outside the small configured window.
    return distance < 0 || distance > PACKET_TICK_WINDOW;
  }

  /** Persists packet rows idempotently; conflicting reuse fails permanently. */
  private void saveTickPackets(String sessionId, LocalDateTime timestamp, TickBatchMessage batch) {
    if (packetRepository == null) {
      throw new IllegalStateException("BATCH_PERSISTENCE_FAILED: packet store unavailable");
    }
    if (packetJdbcTemplate != null) {
      saveTickPacketsBatched(sessionId, batch);
      return;
    }
    saveTickPacketsJpa(sessionId, batch);
  }

  private record PacketSource(String sourceSessionId, String schema) {}

  private record PacketIdentity(String sourceSessionId, String schema, Long sequence) {}

  private void saveTickPacketsBatched(String sessionId, TickBatchMessage batch) {
    Map<PacketSource, List<Long>> sequencesBySource = new LinkedHashMap<>();
    for (TickBatchMessage.TickPacketEntry packet : batch.getPackets()) {
      sequencesBySource
          .computeIfAbsent(
              new PacketSource(packet.getSourceSessionId(), packet.getSchema()),
              ignored -> new ArrayList<>())
          .add(packet.getSequence());
    }

    Map<PacketIdentity, com.chromascape.web.debug.tickdebugger.entity.PacketObservation> existing =
        new HashMap<>();
    for (var source : sequencesBySource.entrySet()) {
      for (var row :
          packetRepository.findBySessionIdAndSourceSessionIdAndPacketSchemaAndObservationSequenceIn(
              sessionId,
              source.getKey().sourceSessionId(),
              source.getKey().schema(),
              source.getValue())) {
        existing.put(
            new PacketIdentity(
                row.getSourceSessionId(), row.getPacketSchema(), row.getObservationSequence()),
            row);
      }
    }

    Set<PacketIdentity> seen = new HashSet<>();
    List<com.chromascape.web.debug.tickdebugger.entity.PacketObservation> inserts =
        new ArrayList<>();
    List<TickBatchMessage.TickPacketEntry> insertedPackets = new ArrayList<>();
    for (TickBatchMessage.TickPacketEntry packet : batch.getPackets()) {
      PacketIdentity identity =
          new PacketIdentity(packet.getSourceSessionId(), packet.getSchema(), packet.getSequence());
      if (!seen.add(identity)) {
        throw new IllegalArgumentException("PACKET_ORDER_INVALID: duplicate observation identity");
      }
      var prior = existing.get(identity);
      if (prior != null) {
        ensurePacketMatches(prior, sessionId, batch.getTick(), packet);
      } else {
        inserts.add(packetRow(sessionId, packet));
        insertedPackets.add(packet);
      }
    }
    if (inserts.isEmpty()) {
      return;
    }

    // IDENTITY-generated JPA saves issue one INSERT round trip per packet. A prepared JDBC batch
    // keeps the same Spring transaction and unique constraint while allowing PostgreSQL to receive
    // the recovery burst without blocking ACKs on hundreds of separate network round trips.
    int[] counts =
        packetJdbcTemplate.batchUpdate(
            "INSERT INTO packet_observations (session_id, tick, source_session_id, packet_schema,"
                + " observation_sequence, packet_sequence, captured_at, capture_delta_millis,"
                + " direction, opcode, protocol_name, encoded_size, decoded_type, decoded_payload,"
                + " sanitized_payload, decode_status, decode_error, synthetic, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)"
                + " ON CONFLICT (session_id, source_session_id, packet_schema, observation_sequence)"
                + " DO NOTHING",
            new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
              @Override
              public void setValues(java.sql.PreparedStatement statement, int index)
                  throws java.sql.SQLException {
                var row = inserts.get(index);
                statement.setString(1, row.getSessionId());
                statement.setLong(2, row.getTick());
                statement.setString(3, row.getSourceSessionId());
                statement.setString(4, row.getPacketSchema());
                statement.setLong(5, row.getObservationSequence());
                statement.setObject(6, row.getPacketSequence(), java.sql.Types.BIGINT);
                statement.setObject(7, row.getCapturedAt());
                statement.setObject(8, row.getCaptureDeltaMillis(), java.sql.Types.BIGINT);
                statement.setString(9, row.getDirection());
                statement.setInt(10, row.getOpcode());
                statement.setString(11, row.getProtocolName());
                statement.setInt(12, row.getEncodedSize());
                statement.setString(13, row.getDecodedType());
                statement.setString(
                    14,
                    row.getDecodedPayload() == null ? null : row.getDecodedPayload().toString());
                statement.setBytes(15, row.getSanitizedPayload());
                statement.setString(16, row.getDecodeStatus());
                statement.setString(17, row.getDecodeError());
                statement.setBoolean(18, row.isSynthetic());
                statement.setObject(19, row.getCreatedAt());
              }

              @Override
              public int getBatchSize() {
                return inserts.size();
              }
            });
    for (int i = 0; i < counts.length; i++) {
      if (counts[i] == 0) {
        var packet = insertedPackets.get(i);
        var winner =
            packetRepository
                .findBySessionIdAndSourceSessionIdAndPacketSchemaAndObservationSequence(
                    sessionId,
                    packet.getSourceSessionId(),
                    packet.getSchema(),
                    packet.getSequence())
                .orElseThrow(() -> new IllegalStateException("PACKET_IDENTITY_CONFLICT"));
        ensurePacketMatches(winner, sessionId, batch.getTick(), packet);
      }
    }
  }

  private void saveTickPacketsJpa(String sessionId, TickBatchMessage batch) {
    for (TickBatchMessage.TickPacketEntry packet : batch.getPackets()) {
      boolean v2 = "rsprox-packet/v2".equals(packet.getSchema());
      var existing =
          v2
              ? packetRepository
                  .findBySessionIdAndSourceSessionIdAndPacketSchemaAndObservationSequence(
                      sessionId,
                      packet.getSourceSessionId(),
                      packet.getSchema(),
                      packet.getSequence())
              : packetRepository.findBySessionIdAndSourceSessionIdAndPacketSchemaAndPacketSequence(
                  sessionId, packet.getSourceSessionId(), packet.getSchema(), packet.getSequence());
      if (existing.isPresent()) {
        ensurePacketMatches(existing.get(), sessionId, batch.getTick(), packet);
        continue;
      }
      com.chromascape.web.debug.tickdebugger.entity.PacketObservation row =
          packetRow(sessionId, packet);
      try {
        // IDENTITY inserts execute on save. Flushing the entire persistence context after every
        // packet makes a recovery backlog quadratic as snapshots and other rows accumulate.
        packetRepository.save(row);
      } catch (org.springframework.dao.DataIntegrityViolationException e) {
        // Lost-ACK retry racing a concurrent insert: re-read and compare idempotently.
        var winner =
            (v2
                    ? packetRepository
                        .findBySessionIdAndSourceSessionIdAndPacketSchemaAndObservationSequence(
                            sessionId,
                            packet.getSourceSessionId(),
                            packet.getSchema(),
                            packet.getSequence())
                    : packetRepository
                        .findBySessionIdAndSourceSessionIdAndPacketSchemaAndPacketSequence(
                            sessionId,
                            packet.getSourceSessionId(),
                            packet.getSchema(),
                            packet.getSequence()))
                .orElseThrow(() -> e);
        ensurePacketMatches(winner, sessionId, batch.getTick(), packet);
      }
    }
    packetRepository.flush();
  }

  private com.chromascape.web.debug.tickdebugger.entity.PacketObservation packetRow(
      String sessionId, TickBatchMessage.TickPacketEntry packet) {
    boolean v2 = "rsprox-packet/v2".equals(packet.getSchema());
    var row =
        new com.chromascape.web.debug.tickdebugger.entity.PacketObservation(
            sessionId,
            packet.getTick(),
            packet.getSourceSessionId(),
            packet.getSchema(),
            packet.getSequence(),
            v2 ? packet.getPacketSequence() : packet.getSequence());
    row.setCapturedAt(
        packet.getCapturedAt() == null ? null : parsePacketTimestamp(packet.getCapturedAt()));
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
    return row;
  }

  private void ensurePacketMatches(
      com.chromascape.web.debug.tickdebugger.entity.PacketObservation row,
      String sessionId,
      long batchTick,
      TickBatchMessage.TickPacketEntry packet) {
    if (!row.getSessionId().equals(sessionId)
        || !row.getSourceSessionId().equals(packet.getSourceSessionId())
        || !java.util.Objects.equals(row.getPacketSchema(), packet.getSchema())
        || !java.util.Objects.equals(row.getObservationSequence(), packet.getSequence())
        || !java.util.Objects.equals(
            row.getPacketSequence(),
            "rsprox-packet/v2".equals(packet.getSchema())
                ? packet.getPacketSequence()
                : packet.getSequence())
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
      return stored.equals(parsePacketTimestamp(incoming));
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
      String json = goForwardPayload(batch);

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

  String goForwardPayload(TickBatchMessage batch) throws JsonProcessingException {
    // Packets stay out of the optional Go forwarding path by default; the local
    // evidence is already durable and the Go contract has no versioned packet shape.
    ObjectNode goNode = objectMapper.valueToTree(batch);
    goNode.remove("packets");
    return objectMapper.writeValueAsString(goNode);
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

  private LocalDateTime parsePacketTimestamp(String timestamp) {
    if (timestamp == null || timestamp.isBlank()) {
      throw new IllegalArgumentException("packet capturedAt must be a non-blank ISO-8601 instant");
    }
    return LocalDateTime.ofInstant(Instant.parse(timestamp), ZoneId.systemDefault());
  }
}

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private final List<WebSocketSession> debuggerClients = new ArrayList<>();

  public TickStreamIngestionService(
      DebugSessionService sessionService,
      TickSnapshotRepository snapshotRepository,
      TickLogRepository logRepository,
      TickOverlayRepository overlayRepository,
      TimelineMarkerRepository markerRepository,
      ObjectMapper objectMapper) {
    this.sessionService = sessionService;
    this.snapshotRepository = snapshotRepository;
    this.logRepository = logRepository;
    this.overlayRepository = overlayRepository;
    this.markerRepository = markerRepository;
    this.objectMapper = objectMapper;
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
        log.warn("Received tick batch for unknown session: {}", sessionId);
        return;
      }

      long tick = batch.getTick();

      // Parse timestamp
      LocalDateTime timestamp = parseTimestamp(batch.getTimestamp());

      // 1. Save tick snapshot
      saveTickSnapshot(batch, timestamp);

      // 2. Save tick logs and collect errors for markers
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

      // 3. Save tick overlays
      List<TickOverlayEntry> overlays = batch.getOverlays();
      if (overlays != null && !overlays.isEmpty()) {
        saveTickOverlays(sessionId, tick, overlays);
      }

      // 4. Broadcast to live debugger clients
      broadcastToDebuggerClients(batch);

      log.debug("Processed tick batch: sessionId={}, tick={}", sessionId, tick);

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
      JsonNode equipment =
          batch.getEquipment() != null ? objectMapper.valueToTree(batch.getEquipment()) : null;
      JsonNode threats =
          batch.getThreats() != null ? objectMapper.valueToTree(batch.getThreats()) : null;
      JsonNode projectiles =
          batch.getProjectiles() != null ? objectMapper.valueToTree(batch.getProjectiles()) : null;
      JsonNode cooldowns =
          batch.getCooldowns() != null ? objectMapper.valueToTree(batch.getCooldowns()) : null;

      TickSnapshot snapshot =
          new TickSnapshot(batch.getSessionId(), batch.getTick(), timestamp, playerState);

      snapshot.setInventory(inventory);
      snapshot.setEquipment(equipment);
      snapshot.setThreats(threats);
      snapshot.setProjectiles(projectiles);
      snapshot.setCooldowns(cooldowns);

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

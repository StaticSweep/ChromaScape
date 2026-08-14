package com.chromascape.web.debug.tickdebugger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage.TickLogEntry;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests that {@code PLAYER_ACTION}/{@code MENU_CLICK} logs from a version-2 tick batch ingest into
 * {@code tick_logs} with the enclosing batch tick while {@code details.sourceTick} remains the
 * authoritative tick, and that old or malformed batches continue to ingest.
 */
class PlayerActionLogIngestionTest {

  private ObjectMapper objectMapper;
  private DebugSessionService sessionService;
  private TickSnapshotRepository snapshotRepository;
  private TickLogRepository logRepository;
  private TickStreamIngestionService ingestionService;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    sessionService = mock(DebugSessionService.class);
    snapshotRepository = mock(TickSnapshotRepository.class);
    logRepository = mock(TickLogRepository.class);
    TickOverlayRepository overlayRepository = mock(TickOverlayRepository.class);
    TimelineMarkerRepository markerRepository = mock(TimelineMarkerRepository.class);

    DebugSession session = new DebugSession("debug_1234", "acct", "script", null, 0L);
    when(sessionService.getSession("debug_1234")).thenReturn(Optional.of(session));

    ingestionService =
        new TickStreamIngestionService(
            sessionService,
            snapshotRepository,
            logRepository,
            overlayRepository,
            markerRepository,
            objectMapper,
            null,
            null);
  }

  @Test
  @DisplayName("PLAYER_ACTION/MENU_CLICK deserializes and persists from a v2 tick batch")
  void playerActionPersistsFromV2Batch() {
    TickBatchMessage batch = buildBatch();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sourceTick", 1234);
    details.put("sequence", 0);
    details.put("option", "Net");
    details.put("target", "<col=ffff00>Fishing spot");
    details.put("menuAction", "NPC_FIRST_OPTION");
    details.put("identifier", 17);
    details.put("param0", 17);
    details.put("param1", 0);
    batch.setLogs(
        List.of(
            new TickLogEntry(
                "PLAYER_ACTION", "INFO", "MENU_CLICK", "Net Fishing spot", details, null)));

    ingestionService.processTickBatch(batch);

    ArgumentCaptor<TickLog> logCaptor = ArgumentCaptor.forClass(TickLog.class);
    verify(logRepository).save(logCaptor.capture());
    TickLog saved = logCaptor.getValue();
    assertThat(saved.getSessionId()).isEqualTo("debug_1234");
    assertThat(saved.getSubsystem()).isEqualTo("PLAYER_ACTION");
    assertThat(saved.getEventType()).isEqualTo("MENU_CLICK");
    assertThat(saved.getTick()).isEqualTo(500L);

    JsonNode storedDetails = saved.getDetails();
    assertThat(storedDetails.get("sourceTick").asLong()).isEqualTo(1234L);
    assertThat(storedDetails.get("sequence").asInt()).isEqualTo(0);
    assertThat(storedDetails.get("target").asText()).isEqualTo("<col=ffff00>Fishing spot");
    assertThat(storedDetails.get("menuAction").asText()).isEqualTo("NPC_FIRST_OPTION");
  }

  @Test
  @DisplayName("stored tick uses the batch tick while sourceTick remains authoritative")
  void storedTickIsBatchTickButSourceTickIsAuthoritative() {
    TickBatchMessage batch = buildBatch();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sourceTick", 9876);
    details.put("sequence", 3);
    batch.setLogs(
        List.of(
            new TickLogEntry(
                "PLAYER_ACTION", "INFO", "MENU_CLICK", "Net Fishing spot", details, null)));

    ingestionService.processTickBatch(batch);

    ArgumentCaptor<TickLog> logCaptor = ArgumentCaptor.forClass(TickLog.class);
    verify(logRepository).save(logCaptor.capture());
    assertThat(logCaptor.getValue().getTick()).isEqualTo(500L);
    assertThat(logCaptor.getValue().getDetails().get("sourceTick").asLong()).isEqualTo(9876L);
  }

  @Test
  @DisplayName("old batches without player actions continue to ingest snapshots")
  void oldBatchWithoutPlayerActionsIngests() {
    TickBatchMessage batch = buildBatch();
    batch.setLogs(new ArrayList<>());

    ingestionService.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> snapshotCaptor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getSessionId()).isEqualTo("debug_1234");
    assertThat(snapshotCaptor.getValue().getTick()).isEqualTo(500L);
  }

  @Test
  @DisplayName("malformed optional action details do not reject the tick snapshot")
  void malformedActionDetailsDoNotRejectTickSnapshot() {
    TickBatchMessage batch = buildBatch();
    // Absent/empty action details are tolerated as an optional field.
    batch.setLogs(
        List.of(
            new TickLogEntry(
                "PLAYER_ACTION", "INFO", "MENU_CLICK", "Net Fishing spot", null, null)));

    ingestionService.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> snapshotCaptor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getTick()).isEqualTo(500L);
    assertThat(snapshotCaptor.getValue().getPlayerState()).isNotNull();
  }

  private TickBatchMessage buildBatch() {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId("debug_1234");
    batch.setTick(500L);
    batch.setTimestamp(Instant.now().toString());

    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", 3239);
    position.put("y", 3147);
    position.put("z", 3147);
    position.put("plane", 0);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("position", position);
    playerState.put("x", 3239);
    playerState.put("z", 3147);
    playerState.put("worldX", 3239);
    playerState.put("worldY", 3147);
    playerState.put("plane", 0);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(Map.of());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());
    batch.setQuest(Map.of());
    return batch;
  }
}

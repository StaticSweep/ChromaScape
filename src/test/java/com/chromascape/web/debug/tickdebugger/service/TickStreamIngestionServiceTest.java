package com.chromascape.web.debug.tickdebugger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.integration.leeuwbot.config.LeeuwBotConfig;
import com.chromascape.web.integration.leeuwbot.service.LeeuwBotBridgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link TickStreamIngestionService} covering questHelper persistence and tolerance. */
@ExtendWith(MockitoExtension.class)
class TickStreamIngestionServiceTest {

  @Mock private DebugSessionService sessionService;
  @Mock private DebugSessionRepository sessionRepository;
  @Mock private TickSnapshotRepository snapshotRepository;
  @Mock private TickLogRepository logRepository;
  @Mock private TickOverlayRepository overlayRepository;
  @Mock private TimelineMarkerRepository markerRepository;
  @Mock private LeeuwBotBridgeService leeuwBotBridgeService;
  @Mock private LeeuwBotConfig leeuwBotConfig;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private TickStreamIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new TickStreamIngestionService(
            sessionService,
            snapshotRepository,
            logRepository,
            overlayRepository,
            markerRepository,
            objectMapper,
            leeuwBotBridgeService,
            leeuwBotConfig);
  }

  private TickBatchMessage strictV2Batch(String sessionId, long tick) {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(tick);
    batch.setTimestamp("2025-06-15T12:00:00Z");

    Map<String, Object> playerState = new HashMap<>();
    playerState.put("x", 3200);
    playerState.put("z", 3200);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());

    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    quest.put("changed", false);
    batch.setQuest(quest);

    return batch;
  }

  @Test
  @DisplayName("processTickBatch persists questHelper when present")
  void processTickBatch_persistsQuestHelper() {
    String sessionId = "s1";
    when(sessionService.getSession(sessionId))
        .thenReturn(Optional.of(
            new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 100);

    Map<String, Object> questHelper = new HashMap<>();
    questHelper.put("schemaVersion", 1);
    questHelper.put("source", "micro6.questhelper");
    questHelper.put("collectionMode", "questhelper");
    Map<String, Object> activeQuest = new HashMap<>();
    activeQuest.put("questKey", "cooks_assistant");
    activeQuest.put("questName", "Cook's Assistant");
    activeQuest.put("questState", "IN_PROGRESS");
    questHelper.put("activeQuest", activeQuest);
    questHelper.put("currentStep", new HashMap<>());
    questHelper.put("steps", List.of());
    questHelper.put("requirements", new HashMap<>());
    questHelper.put("dialogue", new HashMap<>());
    questHelper.put("targets", List.of());
    questHelper.put("overlays", List.of());
    questHelper.put("varbits", new HashMap<>());
    questHelper.put("events", List.of());
    Map<String, Object> debug = new HashMap<>();
    debug.put("collectionMode", "questhelper");
    questHelper.put("debug", debug);
    batch.setQuestHelper(questHelper);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    assertThat(saved.getQuestHelper()).isNotNull();
    JsonNode qh = saved.getQuestHelper();
    assertThat(qh.path("collectionMode").asText()).isEqualTo("questhelper");
    assertThat(qh.path("activeQuest").path("questKey").asText()).isEqualTo("cooks_assistant");
  }

  @Test
  @DisplayName("processTickBatch allows missing questHelper")
  void processTickBatch_allowsMissingQuestHelper() {
    String sessionId = "s2";
    when(sessionService.getSession(sessionId))
        .thenReturn(Optional.of(
            new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 200);
    // questHelper intentionally omitted (left null)

    assertDoesNotThrow(() -> service.processTickBatch(batch));

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    assertThat(saved.getQuestHelper()).isNull();
  }

  @Test
  @DisplayName("Strict v2 accepts nested playerState.position.x/y/plane")
  void strictV2_acceptsNestedPosition() {
    String sessionId = "s3";
    when(sessionService.getSession(sessionId))
        .thenReturn(Optional.of(
            new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(300);
    batch.setTimestamp("2025-06-20T14:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", 3200);
    position.put("y", 3200);
    position.put("plane", 0);
    playerState.put("position", position);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());

    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    batch.setQuest(quest);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    assertThat(saved.getPlayerState().path("position").path("x").asInt()).isEqualTo(3200);
    // Normalization should have added flat aliases
    assertThat(saved.getPlayerState().has("x")).isTrue();
    assertThat(saved.getPlayerState().has("z")).isTrue();
  }

  @Test
  @DisplayName("Strict v2 accepts old flat x/z/plane")
  void strictV2_acceptsOldFlatPosition() {
    String sessionId = "s4";
    when(sessionService.getSession(sessionId))
        .thenReturn(Optional.of(
            new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 400);
    // strictV2Batch already sets flat x/z

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    // Old flat payload should be stored with synthesized nested position
    assertThat(saved.getPlayerState().has("position")).isTrue();
    assertThat(saved.getPlayerState().path("position").path("x").asInt()).isEqualTo(3200);
  }

  @Test
  @DisplayName("Old flat HP/prayer/energy/animation are stored with synthesized nested fields")
  void oldFlatPayload_synthesizesNestedFields() {
    String sessionId = "s5";
    when(sessionService.getSession(sessionId))
        .thenReturn(Optional.of(
            new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 500);
    Map<String, Object> playerState = new HashMap<>();
    playerState.put("x", 3200);
    playerState.put("z", 3200);
    playerState.put("currentHp", 80);
    playerState.put("maxHp", 99);
    playerState.put("currentPrayer", 60);
    playerState.put("maxPrayer", 70);
    playerState.put("runEnergy", 50);
    playerState.put("animationId", 808);
    batch.setPlayerState(playerState);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    JsonNode ps = saved.getPlayerState();
    assertThat(ps.path("hp").path("current").asInt()).isEqualTo(80);
    assertThat(ps.path("hp").path("max").asInt()).isEqualTo(99);
    assertThat(ps.path("prayer").path("current").asInt()).isEqualTo(60);
    assertThat(ps.path("prayer").path("max").asInt()).isEqualTo(70);
    assertThat(ps.path("energy").asInt()).isEqualTo(50);
    assertThat(ps.path("animation").asInt()).isEqualTo(808);
  }

  @Test
  @DisplayName("Missing both nested and flat position fails validation")
  void missingPosition_failsValidation() {
    String sessionId = "s6";
    when(sessionService.getSession(sessionId))
        .thenReturn(Optional.of(
            new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 600);
    // Remove position fields
    Map<String, Object> playerState = new HashMap<>();
    // No x, z, or position
    batch.setPlayerState(playerState);

    try {
      service.processTickBatch(batch);
      assertThat(false).withFailMessage("Should have thrown IllegalArgumentException").isTrue();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).contains("Missing required player position");
    }
  }
}

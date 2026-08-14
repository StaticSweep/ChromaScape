package com.chromascape.web.debug.tickdebugger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

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
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

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
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

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
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

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
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

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
  @DisplayName("Strict v2 rejects nested position without x")
  void strictV2_rejectsNestedPositionWithoutX() {
    String sessionId = "s7";

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(700);
    batch.setTimestamp("2025-06-21T10:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    Map<String, Object> position = new LinkedHashMap<>();
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

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects nested position without y or z")
  void strictV2_rejectsNestedPositionWithoutYOrZ() {
    String sessionId = "s8";

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(800);
    batch.setTimestamp("2025-06-21T10:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", 3200);
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

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 accepts nested position with x and z")
  void strictV2_acceptsNestedPositionWithXAndZ() {
    String sessionId = "s9";
    when(sessionService.getSession(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(900);
    batch.setTimestamp("2025-06-21T10:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", 3200);
    position.put("z", 3200);
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

    // Normalized position.y should be derived from z
    assertThat(saved.getPlayerState().path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("position").path("z").asInt()).isEqualTo(3200);
    // Flat aliases should be present
    assertThat(saved.getPlayerState().path("x").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("z").asInt()).isEqualTo(3200);
  }

  @Test
  @DisplayName("Strict v2 accepts flat x/worldY/plane")
  void strictV2_acceptsFlatXWorldY() {
    String sessionId = "s10";
    when(sessionService.getSession(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(1000);
    batch.setTimestamp("2025-06-21T10:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("x", 3200);
    playerState.put("worldY", 3200);
    playerState.put("plane", 0);
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
    assertThat(saved.getPlayerState().path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("position").path("z").asInt()).isEqualTo(3200);
  }

  @Test
  @DisplayName("Strict v2 accepts flat worldX/worldY/plane")
  void strictV2_acceptsWorldXWorldY() {
    String sessionId = "s12";
    when(sessionService.getSession(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 1200);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("worldX", 3200);
    playerState.put("worldY", 3200);
    playerState.put("plane", 0);
    batch.setPlayerState(playerState);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    JsonNode savedPlayerState = captor.getValue().getPlayerState();

    assertThat(savedPlayerState.path("position").path("x").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("z").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("x").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("z").asInt()).isEqualTo(3200);
  }

  @Test
  @DisplayName("Strict v2 rejects nested position with null x")
  void strictV2_rejectsNestedPositionWithNullX() {
    String sessionId = "s13";

    TickBatchMessage batch = strictV2Batch(sessionId, 1300);
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", null);
    position.put("y", 3200);
    position.put("plane", 0);
    batch.setPlayerState(new LinkedHashMap<>(Map.of("position", position)));

    service.processTickBatch(batch);

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects flat position with null x")
  void strictV2_rejectsFlatPositionWithNullX() {
    String sessionId = "s14";

    TickBatchMessage batch = strictV2Batch(sessionId, 1400);
    batch.getPlayerState().put("x", null);

    service.processTickBatch(batch);

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects a non-map position without escaping normalization")
  void strictV2_rejectsNonMapPosition() {
    String sessionId = "s15";

    TickBatchMessage batch = strictV2Batch(sessionId, 1500);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("position", "bad");
    batch.setPlayerState(playerState);

    service.processTickBatch(batch);

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Invalid nested position does not create null flat aliases")
  void normalizationDoesNotCreateNullFlatAliasesFromInvalidNestedPosition() {
    String sessionId = "s16";

    TickBatchMessage batch = strictV2Batch(sessionId, 1600);
    batch.setPlayerState(new LinkedHashMap<>(Map.of("position", Map.of("y", 3200))));

    service.processTickBatch(batch);

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
    assertThat(batch.getPlayerState()).doesNotContainKey("x");
  }

  @Test
  @DisplayName("Old flat x/z/plane synthesizes position.y")
  void oldFlatXZ_synthesizesPositionY() {
    String sessionId = "s11";
    when(sessionService.getSession(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(1100);
    batch.setTimestamp("2025-06-21T10:00:00Z");

    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("x", 3200);
    playerState.put("z", 3200);
    playerState.put("plane", 0);
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

    assertThat(saved.getPlayerState().has("position")).isTrue();
    assertThat(saved.getPlayerState().path("position").path("x").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(saved.getPlayerState().path("position").path("z").asInt()).isEqualTo(3200);
  }

  @Test
  @DisplayName("Strict v2 rejects flat position with non-numeric x")
  void strictV2_rejectsFlatPositionWithNonNumericX() {
    TickBatchMessage batch = strictV2Batch("s17", 1700);
    batch.getPlayerState().put("x", "bad");
    batch.getPlayerState().put("z", 3200);

    service.processTickBatch(batch);

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects nested position with boolean x")
  void strictV2_rejectsNestedPositionWithBooleanX() {
    TickBatchMessage batch = strictV2Batch("s18", 1800);
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", true);
    position.put("y", 3200);
    batch.setPlayerState(new LinkedHashMap<>(Map.of("position", position)));

    service.processTickBatch(batch);

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects flat position with object worldX")
  void strictV2_rejectsFlatPositionWithObjectWorldX() {
    TickBatchMessage batch = strictV2Batch("s19", 1900);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("worldX", Map.of("bad", true));
    playerState.put("worldY", 3200);
    batch.setPlayerState(playerState);

    service.processTickBatch(batch);

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 rejects invalid plane")
  void strictV2_rejectsInvalidPlane() {
    TickBatchMessage batch = strictV2Batch("s20", 2000);
    batch.getPlayerState().put("plane", "bad");

    service.processTickBatch(batch);

    verify(snapshotRepository, never()).save(any(TickSnapshot.class));
  }

  @Test
  @DisplayName("Strict v2 accepts and normalizes numeric string coordinates")
  void strictV2_acceptsNumericStringCoordinates() {
    String sessionId = "s21";
    when(sessionService.getSession(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 2100);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("x", "3200");
    playerState.put("z", "3200");
    playerState.put("plane", "0");
    batch.setPlayerState(playerState);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    JsonNode savedPlayerState = captor.getValue().getPlayerState();

    assertThat(savedPlayerState.path("position").path("x").isNumber()).isTrue();
    assertThat(savedPlayerState.path("position").path("x").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("z").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("plane").asInt()).isEqualTo(0);
    assertThat(savedPlayerState.path("x").isNumber()).isTrue();
    assertThat(savedPlayerState.path("z").isNumber()).isTrue();
    assertThat(savedPlayerState.path("plane").isNumber()).isTrue();
  }

  @Test
  @DisplayName("Strict v2 normalizes numeric string world coordinates")
  void strictV2_normalizesNumericStringWorldCoordinates() {
    String sessionId = "s22";
    when(sessionService.getSession(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));

    TickBatchMessage batch = strictV2Batch(sessionId, 2200);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("worldX", "3200");
    playerState.put("worldY", "3200");
    batch.setPlayerState(playerState);

    service.processTickBatch(batch);

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    JsonNode savedPlayerState = captor.getValue().getPlayerState();

    assertThat(savedPlayerState.path("position").path("x").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("position").path("y").asInt()).isEqualTo(3200);
    assertThat(savedPlayerState.path("x").isNumber()).isTrue();
    assertThat(savedPlayerState.path("z").isNumber()).isTrue();
    assertThat(savedPlayerState.path("worldX").isNumber()).isTrue();
    assertThat(savedPlayerState.path("worldY").isNumber()).isTrue();
  }
}

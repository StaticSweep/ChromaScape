package com.chromascape.web.aiplayer.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.combat.CombatTickRowService.LabelEvidence;
import com.chromascape.web.aiplayer.combat.CombatTickRowService.VideoSync;
import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies schema-4 combat ingestion mapping using a mocked repository. Database persistence and
 * migration behavior require the separate PostgreSQL integration test.
 */
class CombatTickRowIngestionRoundTripTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private TickSnapshotRepository snapshotRepository;
  private TickStreamIngestionService ingestionService;
  private JsonNode fixture;

  @BeforeEach
  void setUp() throws Exception {
    try (InputStream in =
        getClass()
            .getResourceAsStream("/replay/contracts/combat-tick-row-schema4-fixture-v1.json")) {
      assertThat(in).as("schema-4 golden fixture present").isNotNull();
      fixture = objectMapper.readTree(in);
    }

    DebugSessionService sessionService = mock(DebugSessionService.class);
    snapshotRepository = mock(TickSnapshotRepository.class);
    TickLogRepository logRepository = mock(TickLogRepository.class);
    TickOverlayRepository overlayRepository = mock(TickOverlayRepository.class);
    TimelineMarkerRepository markerRepository = mock(TimelineMarkerRepository.class);

    DebugSession session = new DebugSession("debug_combat_1", "acct", "script", null, 100L);
    when(sessionService.getSession("debug_combat_1")).thenReturn(Optional.of(session));

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

  private TickBatchMessage batch() {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(4);
    batch.setSessionId("debug_combat_1");
    batch.setTick(fixture.get("tick").asLong());
    batch.setTimestamp(fixture.get("timestamp").asText());
    batch.setPlayerState(map(fixture.get("playerState")));
    batch.setInventory(maps(fixture.get("inventory")));
    batch.setEquipment(maps(fixture.get("equipment")));
    batch.setNpcs(maps(fixture.get("npcs")));
    batch.setProjectiles(maps(fixture.get("projectiles")));
    batch.setGameObjects(maps(fixture.get("gameObjects")));
    batch.setGroundObjects(maps(fixture.get("groundObjects")));
    batch.setGroundItems(maps(fixture.get("groundItems")));
    batch.setPlayers(maps(fixture.get("players")));
    batch.setWidgets(maps(fixture.get("widgets")));
    batch.setQuest(map(fixture.get("quest")));
    batch.setCooldowns(
        Map.of(
            "damageDealtThisTick",
            fixture.get("cooldowns").get("damageDealtThisTick").asInt(),
            "damageTakenThisTick",
            fixture.get("cooldowns").get("damageTakenThisTick").asInt(),
            "killCount",
            fixture.get("cooldowns").get("killCount").asInt()));
    batch.setInputs(map(fixture.get("inputs")));
    batch.setTiles(maps(fixture.get("tiles")));
    batch.setHazards(maps(fixture.get("hazards")));
    batch.setPhase(map(fixture.get("phase")));
    return batch;
  }

  private RecordingAction action() {
    JsonNode node = fixture.get("action");
    RecordingAction action = new RecordingAction();
    action.setSchema(node.get("schema").asText());
    action.setSessionId(node.get("sessionId").asText());
    action.setTick(node.get("tick").asLong());
    action.setSequence(node.get("sequence").asInt());
    action.setActionType(node.get("actionType").asText());
    action.setSource(node.get("source").asText());
    action.setCapturedAt(node.get("capturedAt").asText());
    return action;
  }

  @Test
  @DisplayName("schema-4 combat batch persists every combat row section")
  void ingestionPersistsCombatSections() {
    ingestionService.processTickBatch(batch());

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    assertThat(saved.getPlayerState().get("specialAttack").asInt()).isEqualTo(50);
    assertThat(saved.getPlayerState().get("prayersActive").get(0).asText())
        .isEqualTo("PROTECT_FROM_MELEE");
    assertThat(saved.getTiles()).hasSize(3);
    assertThat(saved.getHazards()).hasSize(1);
    assertThat(saved.getHazards().get(0).get("kind").asText()).isEqualTo("NPC_ATTACKER");
    assertThat(saved.getPhase().get("encounter").asText()).isEqualTo("Abyssal demon");
    assertThat(saved.getInputs().get("mouseX").asInt()).isEqualTo(640);
    assertThat(saved.getInputs().get("menuOption").asText()).isEqualTo("Attack");
  }

  @Test
  @DisplayName("persisted schema-4 snapshot yields a row with zero missing fields")
  void persistedSnapshotYieldsCompleteRow() {
    ingestionService.processTickBatch(batch());

    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    TickSnapshot saved = captor.getValue();

    Map<String, Object> row =
        CombatTickRowService.build(
            saved,
            LabelEvidence.fromAction(action()),
            null,
            new VideoSync(fixture.get("startTick").asLong(), fixture.get("fps").asInt()));

    assertThat(row.get("deferred")).as("zero missing required fields").isEqualTo(List.of());
    CombatTickRowService.requireLabelSafe(row);

    Map<String, Object> player = map(row.get("player"));
    assertThat(player.get("specialAttack")).isEqualTo(50);
    Map<String, Object> environment = map(row, "environment");
    assertThat(environment.get("prayersActive")).isEqualTo(List.of("PROTECT_FROM_MELEE"));
    assertThat(list(environment, "tiles")).hasSize(3);
    assertThat(list(environment, "hazards")).hasSize(1);
    assertThat(map(environment, "phase").get("encounter")).isEqualTo("Abyssal demon");
    Map<String, Object> diagnostics = map(row, "diagnosticInputs");
    assertThat(diagnostics.get("mouseX")).isEqualTo(640);
    assertThat(diagnostics.get("mouseButton")).isEqualTo(1);
    assertThat(diagnostics.get("menuOption")).isEqualTo("Attack");
    assertThat(diagnostics.get("clickedEntity")).isEqualTo("npc:415:0");
    Map<String, Object> target = map(row, "target");
    assertThat(target.get("resolved")).isEqualTo(true);
    assertThat(target.get("targetHp")).isNull();
    assertThat(target.get("targetMaxHp")).isNull();
    assertThat(target.get("targetHealthRatio")).isEqualTo(204);
    assertThat(target.get("targetHealthScale")).isEqualTo(255);
    Map<String, Object> label = map(row, "label");
    assertThat(label.get("canonicalType")).isEqualTo("INTERACT_NPC");
    assertThat(label.get("unmapped")).isEqualTo(false);
  }

  @Test
  void negotiatedSchema3WirePayloadRetainsAdditiveCombatSections() throws Exception {
    var wire = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
    wire.put("type", "TICK_BATCH");
    wire.put("schemaVersion", 3);
    wire.put("sessionId", "debug_combat_1");
    ingestionService.processTickBatch(objectMapper.treeToValue(wire, TickBatchMessage.class));
    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    assertThat(captor.getValue().getInputs()).isEqualTo(fixture.get("inputs"));
    assertThat(captor.getValue().getTiles()).isEqualTo(fixture.get("tiles"));
    assertThat(captor.getValue().getHazards()).isEqualTo(fixture.get("hazards"));
    assertThat(captor.getValue().getPhase()).isEqualTo(fixture.get("phase"));
  }

  private Map<String, Object> map(JsonNode node) {
    return objectMapper.convertValue(node, new TypeReference<>() {});
  }

  private Map<String, Object> map(Object value) {
    return objectMapper.convertValue(value, new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> row, String key) {
    return (Map<String, Object>) row.get(key);
  }

  private List<Map<String, Object>> maps(JsonNode node) {
    return objectMapper.convertValue(node, new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Map<String, Object> row, String key) {
    return (List<Map<String, Object>>) (List<?>) row.get(key);
  }
}

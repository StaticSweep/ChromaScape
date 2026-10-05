package com.chromascape.web.aiplayer.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.aiplayer.combat.CombatTickRowService.DiagnosticEvidence;
import com.chromascape.web.aiplayer.combat.CombatTickRowService.LabelEvidence;
import com.chromascape.web.aiplayer.combat.CombatTickRowService.VideoSync;
import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Golden-fixture tests for the SC-1976 combat tick row. */
class CombatTickRowServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private JsonNode fixture;

  @BeforeEach
  void loadFixture() throws Exception {
    try (InputStream in =
        getClass().getResourceAsStream("/replay/contracts/combat-tick-row-fixture-v1.json")) {
      assertThat(in).as("golden fixture present").isNotNull();
      fixture = objectMapper.readTree(in);
    }
  }

  private TickSnapshot snapshot() {
    TickSnapshot snap =
        new TickSnapshot(
            "debug_combat_1",
            fixture.get("tick").asLong(),
            LocalDateTime.parse(fixture.get("timestamp").asText()),
            fixture.get("playerState"));
    snap.setInventory(fixture.get("inventory"));
    snap.setEquipment(fixture.get("equipment"));
    snap.setNpcs(fixture.get("npcs"));
    snap.setProjectiles(fixture.get("projectiles"));
    snap.setGroundObjects(fixture.get("groundObjects"));
    return snap;
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

  private VideoSync videoSync() {
    return new VideoSync(fixture.get("startTick").asLong(), fixture.get("fps").asInt());
  }

  @Test
  @DisplayName("builds the full combat row from the golden fixture")
  void buildsFullRow() {
    Map<String, Object> row =
        CombatTickRowService.build(
            snapshot(), LabelEvidence.fromAction(action()), null, videoSync());

    assertThat(row.get("schema")).isEqualTo("combat-tick-row/v1");
    assertThat(row.get("tick_id")).isEqualTo(110L);

    Map<String, Object> player = map(row, "player");
    assertThat(player.get("hp")).isEqualTo(87);
    assertThat(player.get("maxHp")).isEqualTo(99);
    assertThat(player.get("prayer")).isEqualTo(40);
    assertThat(player.get("runEnergy")).isEqualTo(65);
    assertThat(player.get("animationId")).isEqualTo(5816);
    assertThat(player.get("x")).isEqualTo(3200);
    assertThat(player.get("y")).isEqualTo(3205);

    Map<String, Object> target = map(row, "target");
    assertThat(target.get("resolved")).isEqualTo(true);
    assertThat(target.get("targetId")).isEqualTo(415);
    assertThat(target.get("targetHp")).isEqualTo(120);
    assertThat(target.get("targetMaxHp")).isEqualTo(150);
    assertThat(target.get("targetAnimation")).isEqualTo(1537);
    assertThat(target.get("x")).isEqualTo(3202);
    assertThat(target.get("y")).isEqualTo(3206);

    Map<String, Object> environment = map(row, "environment");
    List<Map<String, Object>> nearby = list(environment, "nearbyNpcs");
    assertThat(nearby).hasSize(2);
    assertThat(nearby.get(0).get("distance")).isEqualTo(2);
    assertThat(nearby.get(1).get("distance")).isEqualTo(10);
    assertThat(list(environment, "projectiles")).hasSize(1);
    assertThat(list(environment, "groundObjects")).hasSize(1);

    Map<String, Object> inventory = map(row, "inventory");
    assertThat(inventory.get("ids")).isEqualTo(List.of(385, 4151));
    assertThat(inventory.get("amounts")).isEqualTo(List.of(3, 1));
    assertThat(inventory.get("slots")).isEqualTo(List.of(0, 2));

    Map<String, Object> label = map(row, "label");
    assertThat(label.get("canonicalType")).isEqualTo("INTERACT_NPC");
    assertThat(label.get("unmapped")).isEqualTo(false);

    Map<String, Object> video = map(row, "video");
    assertThat(video.get("hasVideo")).isEqualTo(true);
    assertThat(video.get("frameStart")).isEqualTo(180);
    assertThat(video.get("frameEnd")).isEqualTo(197);

    assertThat(strings(row, "deferred"))
        .contains(
            "special_attack",
            "prayers_active",
            "tiles",
            "hazards",
            "phase",
            "mouse_x",
            "mouse_y",
            "mouse_button")
        .doesNotContain("per_tick_frames_stored");

    CombatTickRowService.requireLabelSafe(row);
  }

  @Test
  @DisplayName("live batch and snapshot builds agree on player, target, and inventory")
  void liveBatchAgreesWithSnapshot() {
    Map<String, Object> playerState = new LinkedHashMap<>();
    fixture
        .get("playerState")
        .fields()
        .forEachRemaining(e -> playerState.put(e.getKey(), e.getValue()));
    List<Map<String, Object>> npcs =
        objectMapper.convertValue(
            fixture.get("npcs"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    TickBatchMessage batch = new TickBatchMessage();
    batch.setTick(fixture.get("tick").asLong());
    batch.setTimestamp(fixture.get("timestamp").asText());
    batch.setPlayerState(playerState);
    batch.setNpcs(npcs);

    Map<String, Object> fromBatch =
        CombatTickRowService.build(batch, LabelEvidence.fromAction(action()), null, videoSync());
    Map<String, Object> fromSnapshot =
        CombatTickRowService.build(
            snapshot(), LabelEvidence.fromAction(action()), null, videoSync());

    assertThat(fromBatch.get("player")).isEqualTo(fromSnapshot.get("player"));
    assertThat(fromBatch.get("target")).isEqualTo(fromSnapshot.get("target"));
    assertThat(map(fromBatch, "video")).isEqualTo(map(fromSnapshot, "video"));
  }

  @Test
  @DisplayName("unresolvable target yields explicit nulls, not a silent join")
  void untargetedTickResolvesFalse() {
    TickSnapshot snap = snapshot();
    ((com.fasterxml.jackson.databind.node.ObjectNode) snap.getPlayerState()).remove("interacting");

    Map<String, Object> row =
        CombatTickRowService.build(snap, LabelEvidence.fromAction(action()), null, videoSync());
    Map<String, Object> target = map(row, "target");
    assertThat(target.get("resolved")).isEqualTo(false);
    assertThat(target.get("targetId")).isNull();
    assertThat(target.get("targetHp")).isNull();
  }

  @Test
  void liveHealthBarValuesStayDistinctFromAbsoluteHp() {
    TickSnapshot snap = snapshot();
    snap.setNpcs(
        objectMapper.valueToTree(
            List.of(Map.of("index", 0, "id", 415, "healthRatio", 204, "healthScale", 255))));
    Map<String, Object> row = CombatTickRowService.build(snap, null, null, videoSync());
    Map<String, Object> target = map(row, "target");
    assertThat(target.get("targetHp")).isNull();
    assertThat(target.get("targetMaxHp")).isNull();
    assertThat(target.get("targetHealthRatio")).isEqualTo(204);
    assertThat(target.get("targetHealthScale")).isEqualTo(255);
    assertThat(list(map(row, "environment"), "nearbyNpcs").get(0))
        .containsEntry("healthRatio", 204)
        .containsEntry("healthScale", 255)
        .containsEntry("hp", null);
  }

  @Test
  @DisplayName("session without video yields null frames and records the gap")
  void absentVideoStaysNull() {
    Map<String, Object> row =
        CombatTickRowService.build(
            snapshot(), LabelEvidence.fromAction(action()), null, VideoSync.absent(100L));
    Map<String, Object> video = map(row, "video");
    assertThat(video.get("hasVideo")).isEqualTo(false);
    assertThat(video.get("frameStart")).isNull();
    assertThat(video.get("frameEnd")).isNull();
    assertThat(strings(row, "deferred")).contains("per_tick_frames_stored");
  }

  @Test
  @DisplayName("combat vocabulary maps to canonical types; PRAYER_MAGIC stays unmapped")
  void vocabMapping() {
    assertThat(CombatTickRowService.mapVocabToCanonical("ATTACK")).isEqualTo("INTERACT_NPC");
    assertThat(CombatTickRowService.mapVocabToCanonical("EAT")).isEqualTo("INVENTORY_ACTION");
    assertThat(CombatTickRowService.mapVocabToCanonical("MOVE_TILE")).isEqualTo("WALK_TO");
    assertThat(CombatTickRowService.mapVocabToCanonical("CAST_SPELL")).isEqualTo("CAST_SPELL");
    assertThat(CombatTickRowService.mapVocabToCanonical("WAIT")).isEqualTo("WAIT_UNTIL_TICK");
    assertThat(CombatTickRowService.mapVocabToCanonical("PRAYER_MAGIC")).isNull();
    assertThat(CombatTickRowService.mapVocabToCanonical("BOGUS")).isNull();
    assertThat(CombatTickRowService.mapVocabToCanonical(null)).isNull();

    LabelEvidence prayer =
        LabelEvidence.ofVocab("PRAYER_MAGIC", "MICRO6_SEMANTIC_DISPATCH", 110L, 0);
    assertThat(prayer.unmapped()).isTrue();
    Map<String, Object> row = CombatTickRowService.build(snapshot(), prayer, null, videoSync());
    assertThat(map(row, "label").get("unmapped")).isEqualTo(true);
    CombatTickRowService.requireLabelSafe(row);
  }

  @Test
  @DisplayName("label fence rejects rows that smuggle diagnostic inputs into the label")
  void labelFenceRejectsSmuggledInputs() {
    Map<String, Object> row =
        CombatTickRowService.build(
            snapshot(), LabelEvidence.fromAction(action()), null, videoSync());
    map(row, "label").put("mouseX", 123);
    assertThatThrownBy(() -> CombatTickRowService.requireLabelSafe(row))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mouseX");
  }

  @Test
  @DisplayName("diagnostic evidence lands in the fenced block, never the label")
  void diagnosticsStayFenced() {
    DiagnosticEvidence evidence =
        new DiagnosticEvidence("Attack", "npc:415:0", Map.of("x", 3202, "y", 3206, "plane", 0));
    Map<String, Object> row =
        CombatTickRowService.build(
            snapshot(), LabelEvidence.fromAction(action()), evidence, videoSync());
    Map<String, Object> diagnostics = map(row, "diagnosticInputs");
    assertThat(diagnostics.get("menuOption")).isEqualTo("Attack");
    assertThat(diagnostics.get("clickedEntity")).isEqualTo("npc:415:0");
    assertThat(diagnostics.get("mouseX")).isNull();
    CombatTickRowService.requireLabelSafe(row);
  }

  @Test
  @DisplayName("published schema contract covers every proposed row section")
  void schemaContractCoversRow() throws Exception {
    JsonNode schema;
    try (InputStream in =
        getClass().getResourceAsStream("/contracts/replay/combat-tick-row-v1.schema.json")) {
      assertThat(in).as("schema contract present").isNotNull();
      schema = objectMapper.readTree(in);
    }
    JsonNode properties = schema.get("properties");
    assertThat(properties.get("tick_id")).isNotNull();
    for (String section :
        List.of(
            "player",
            "target",
            "environment",
            "inventory",
            "equipment",
            "diagnosticInputs",
            "label",
            "video",
            "deferred")) {
      assertThat(properties.get(section)).as("schema covers " + section).isNotNull();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> row, String key) {
    return (Map<String, Object>) row.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Map<String, Object> row, String key) {
    return (List<Map<String, Object>>) (List<?>) row.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Map<String, Object> row, String key) {
    return (List<String>) (List<?>) row.get(key);
  }
}

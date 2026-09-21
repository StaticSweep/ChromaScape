package com.chromascape.web.debug.tickdebugger.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link TickBatchMessage} deserializes the {@code questHelper} block and tolerates old
 * payloads that lack it (backward compatibility).
 */
class TickBatchMessageTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void deserializesQuestHelperBlock() throws Exception {
    String json =
        "{\"type\":\"TICK_BATCH\",\"schemaVersion\":2,\"sessionId\":\"s1\",\"tick\":100,"
            + "\"timestamp\":\"2025-06-15T12:00:00Z\","
            + "\"playerState\":{\"x\":3200,\"z\":3200},"
            + "\"inventory\":[],\"equipment\":[],\"threats\":[],\"projectiles\":[],"
            + "\"cooldowns\":{},\"logs\":[],\"overlays\":[],"
            + "\"quest\":{\"active\":true,\"name\":\"Cook's Assistant\"},"
            + "\"questHelper\":{\"schemaVersion\":1,\"collectionMode\":\"questhelper\","
            + "\"activeQuest\":{\"questName\":\"Cook's Assistant\",\"questState\":\"IN_PROGRESS\"},"
            + "\"currentStep\":{\"stepId\":\"c\",\"text\":\"Talk to the Cook.\"}},"
            + "\"npcs\":[],\"gameObjects\":[],\"groundObjects\":[],"
            + "\"groundItems\":[],\"players\":[],\"widgets\":[]}";

    TickBatchMessage msg = objectMapper.readValue(json, TickBatchMessage.class);
    assertThat(msg.getType()).isEqualTo("TICK_BATCH");
    assertThat(msg.getQuest()).isNotNull();
    assertThat(msg.getQuest().get("name")).isEqualTo("Cook's Assistant");

    Map<String, Object> questHelper = msg.getQuestHelper();
    assertNotNull(questHelper, "questHelper should be present");
    assertThat(questHelper.get("schemaVersion")).isEqualTo(1);
    assertThat(questHelper.get("collectionMode")).isEqualTo("questhelper");
    assertThat(questHelper.get("activeQuest")).isNotNull();
    assertThat(questHelper.get("currentStep")).isNotNull();
  }

  @Test
  void deserializesOldPayloadWithoutQuestHelper() throws Exception {
    String json =
        "{\"type\":\"TICK_BATCH\",\"schemaVersion\":2,\"sessionId\":\"s1\",\"tick\":50,"
            + "\"timestamp\":\"2025-06-15T12:00:00Z\","
            + "\"playerState\":{\"x\":3200,\"z\":3200},"
            + "\"inventory\":[],\"equipment\":[],\"threats\":[],\"projectiles\":[],"
            + "\"cooldowns\":{},\"logs\":[],\"overlays\":[],"
            + "\"quest\":{\"active\":true},"
            + "\"npcs\":[],\"gameObjects\":[],\"groundObjects\":[],"
            + "\"groundItems\":[],\"players\":[],\"widgets\":[]}";

    TickBatchMessage msg = objectMapper.readValue(json, TickBatchMessage.class);
    assertNull(msg.getQuestHelper(), "questHelper should be null when omitted from JSON");
    assertNotNull(msg.getQuest());
  }

  @Test
  void deserializesEmptyQuestHelperToNull() throws Exception {
    String json =
        "{\"type\":\"TICK_BATCH\",\"schemaVersion\":2,\"sessionId\":\"s1\",\"tick\":1,"
            + "\"timestamp\":\"2025-06-15T12:00:00Z\","
            + "\"playerState\":{\"x\":0,\"z\":0},"
            + "\"inventory\":[],\"equipment\":[],\"threats\":[],\"projectiles\":[],"
            + "\"cooldowns\":{},\"logs\":[],\"overlays\":[],"
            + "\"quest\":{},\"questHelper\":{},\"npcs\":[],\"gameObjects\":[],"
            + "\"groundObjects\":[],\"groundItems\":[],\"players\":[],\"widgets\":[]}";

    TickBatchMessage msg = objectMapper.readValue(json, TickBatchMessage.class);
    assertNotNull(msg.getQuestHelper());
    assertThat(msg.getQuestHelper()).isEmpty();
  }

  @Test
  void deserializesStructuredSc265QuestHelperFixture() throws Exception {
    Map<String, Object> questHelper = fixtureAsMap("full");
    String json = objectMapper.writeValueAsString(tickBatchEnvelope(questHelper));

    TickBatchMessage msg = objectMapper.readValue(json, TickBatchMessage.class);
    Map<String, Object> qh = msg.getQuestHelper();
    assertThat(qh).isNotNull();
    assertThat(qh.get("schema")).isEqualTo("quest.helper.runtime.v1");
    assertThat(qh.get("outcome")).isEqualTo("FULL");
    assertThat(qh.get("usable")).isEqualTo(true);
    assertThat(qh.get("complete")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> steps = (List<Map<String, Object>>) qh.get("steps");
    assertThat(steps).hasSize(2);
    assertThat(steps.get(0).get("stepId")).isEqualTo("cooks_assistant:7c63d1a2b4c5");
    assertThat(steps.get(0).get("ordinal")).isEqualTo(0);
  }

  @Test
  void deserializesDegradedUnavailableAndFailedQuestHelperFixtures() throws Exception {
    for (String fixtureName : new String[] {"degraded", "unavailable", "failed"}) {
      Map<String, Object> questHelper = fixtureAsMap(fixtureName);
      String json = objectMapper.writeValueAsString(tickBatchEnvelope(questHelper));

      TickBatchMessage msg = objectMapper.readValue(json, TickBatchMessage.class);
      assertThat(msg.getQuestHelper()).isNotNull();
      assertThat(msg.getQuestHelper().get("outcome")).isEqualTo(fixtureName.toUpperCase());
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> diagnostics =
          (List<Map<String, Object>>) msg.getQuestHelper().get("diagnostics");
      assertThat(diagnostics).isNotEmpty();
    }
  }

  @Test
  void fixtureCorpusLoadsFromMirroredTestResources() throws Exception {
    try (java.io.InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/quest/quest-helper-runtime-v1-fixtures.json")) {
      assertThat(input).as("mirrored fixture corpus").isNotNull();
      com.fasterxml.jackson.databind.JsonNode corpus =
          objectMapper.readTree(input).path("fixtures");
      assertThat(corpus.size()).isGreaterThanOrEqualTo(11);
      assertThat(corpus.has("legacyV1")).isTrue();
    }
  }

  private Map<String, Object> fixtureAsMap(String name) throws Exception {
    try (java.io.InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/quest/quest-helper-runtime-v1-fixtures.json")) {
      assertThat(input).as("fixture corpus").isNotNull();
      com.fasterxml.jackson.databind.JsonNode fixture =
          objectMapper.readTree(input).path("fixtures").path(name);
      return objectMapper.convertValue(
          fixture, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
  }

  private static Map<String, Object> tickBatchEnvelope(Map<String, Object> questHelper) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("type", "TICK_BATCH");
    envelope.put("schemaVersion", 2);
    envelope.put("sessionId", "s1");
    envelope.put("tick", 100);
    envelope.put("timestamp", "2025-06-15T12:00:00Z");
    envelope.put("playerState", Map.of("x", 3200, "z", 3200));
    envelope.put("inventory", List.of());
    envelope.put("equipment", List.of());
    envelope.put("threats", List.of());
    envelope.put("projectiles", List.of());
    envelope.put("cooldowns", Map.of());
    envelope.put("logs", List.of());
    envelope.put("overlays", List.of());
    envelope.put("quest", Map.of());
    envelope.put("questHelper", questHelper);
    envelope.put("npcs", List.of());
    envelope.put("gameObjects", List.of());
    envelope.put("groundObjects", List.of());
    envelope.put("groundItems", List.of());
    envelope.put("players", List.of());
    envelope.put("widgets", List.of());
    return envelope;
  }

  @Test
  void deserializesFullNestedPlayerState() throws Exception {
    String json =
        "{\"type\":\"TICK_BATCH\",\"schemaVersion\":2,\"sessionId\":\"s1\",\"tick\":200,"
            + "\"timestamp\":\"2025-07-30T12:00:00Z\","
            + "\"playerState\":{"
            + "\"schemaVersion\":1,\"tick\":200,\"name\":\"TestPlayer\","
            + "\"position\":{\"x\":3200,\"y\":3200,\"z\":3200,\"plane\":0,\"regionId\":12850,\"regionX\":50,\"regionY\":50},"
            + "\"localPosition\":{\"x\":6400,\"y\":6400,\"sceneX\":50,\"sceneY\":50},"
            + "\"hp\":{\"current\":99,\"max\":99,\"percent\":100},"
            + "\"prayer\":{\"current\":43,\"max\":99,\"percent\":43},"
            + "\"energy\":87,\"weight\":0,\"combatLevel\":126,"
            + "\"animation\":808,\"poseAnimation\":813,\"graphic\":-1,"
            + "\"isRunning\":true,\"movementSpeed\":2,\"isIdle\":false,\"isMoving\":true,"
            + "\"x\":3200,\"z\":3200,\"worldX\":3200,\"worldY\":3200,\"plane\":0,"
            + "\"currentHp\":99,\"maxHp\":99,\"hpPercent\":100,"
            + "\"currentPrayer\":43,\"maxPrayer\":99,\"prayerPercent\":43,"
            + "\"runEnergy\":87,\"animationId\":808,"
            + "\"skills\":{\"ATTACK\":{\"real\":99,\"boosted\":99,\"xp\":13034431}}"
            + "},"
            + "\"inventory\":[],\"equipment\":[],\"threats\":[],\"projectiles\":[],"
            + "\"cooldowns\":{},\"logs\":[],\"overlays\":[],"
            + "\"quest\":{},\"questHelper\":null,"
            + "\"npcs\":[],\"gameObjects\":[],\"groundObjects\":[],"
            + "\"groundItems\":[],\"players\":[],\"widgets\":[]}";

    TickBatchMessage msg = objectMapper.readValue(json, TickBatchMessage.class);
    assertThat(msg.getType()).isEqualTo("TICK_BATCH");
    Map<String, Object> ps = msg.getPlayerState();
    assertNotNull(ps);
    assertThat(ps.get("name")).isEqualTo("TestPlayer");

    @SuppressWarnings("unchecked")
    Map<String, Object> position = (Map<String, Object>) ps.get("position");
    assertNotNull(position);
    assertThat(position.get("x")).isEqualTo(3200);
    assertThat(position.get("y")).isEqualTo(3200);
    assertThat(position.get("plane")).isEqualTo(0);

    @SuppressWarnings("unchecked")
    Map<String, Object> hp = (Map<String, Object>) ps.get("hp");
    assertNotNull(hp);
    assertThat(hp.get("current")).isEqualTo(99);
    assertThat(hp.get("max")).isEqualTo(99);

    // Legacy flat aliases
    assertThat(ps.get("currentHp")).isEqualTo(99);
    assertThat(ps.get("x")).isEqualTo(3200);
    assertThat(ps.get("animationId")).isEqualTo(808);
  }
}

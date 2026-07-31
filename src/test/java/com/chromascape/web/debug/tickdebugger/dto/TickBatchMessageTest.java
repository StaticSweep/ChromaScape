package com.chromascape.web.debug.tickdebugger.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link TickBatchMessage} deserializes the {@code questHelper} block and
 * tolerates old payloads that lack it (backward compatibility).
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
            +   "\"activeQuest\":{\"questName\":\"Cook's Assistant\",\"questState\":\"IN_PROGRESS\"},"
            +   "\"currentStep\":{\"stepId\":\"c\",\"text\":\"Talk to the Cook.\"}},"
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
  void deserializesFullNestedPlayerState() throws Exception {
    String json =
        "{\"type\":\"TICK_BATCH\",\"schemaVersion\":2,\"sessionId\":\"s1\",\"tick\":200,"
            + "\"timestamp\":\"2025-07-30T12:00:00Z\","
            + "\"playerState\":{"
            +   "\"schemaVersion\":1,\"tick\":200,\"name\":\"TestPlayer\","
            +   "\"position\":{\"x\":3200,\"y\":3200,\"z\":3200,\"plane\":0,\"regionId\":12850,\"regionX\":50,\"regionY\":50},"
            +   "\"localPosition\":{\"x\":6400,\"y\":6400,\"sceneX\":50,\"sceneY\":50},"
            +   "\"hp\":{\"current\":99,\"max\":99,\"percent\":100},"
            +   "\"prayer\":{\"current\":43,\"max\":99,\"percent\":43},"
            +   "\"energy\":87,\"weight\":0,\"combatLevel\":126,"
            +   "\"animation\":808,\"poseAnimation\":813,\"graphic\":-1,"
            +   "\"isRunning\":true,\"movementSpeed\":2,\"isIdle\":false,\"isMoving\":true,"
            +   "\"x\":3200,\"z\":3200,\"worldX\":3200,\"worldY\":3200,\"plane\":0,"
            +   "\"currentHp\":99,\"maxHp\":99,\"hpPercent\":100,"
            +   "\"currentPrayer\":43,\"maxPrayer\":99,\"prayerPercent\":43,"
            +   "\"runEnergy\":87,\"animationId\":808,"
            +   "\"skills\":{\"ATTACK\":{\"real\":99,\"boosted\":99,\"xp\":13034431}}"
            +   "},"
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
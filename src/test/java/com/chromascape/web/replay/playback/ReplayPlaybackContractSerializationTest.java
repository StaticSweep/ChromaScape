package com.chromascape.web.replay.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.playback.dto.ReplayPlaybackCatalogEntry;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackCatalogResponse;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackFramePageResponse;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackFramePageResponse.FrameData;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies that the published playback DTOs serialize to their committed draft-2020-12 schemas. */
class ReplayPlaybackContractSerializationTest {

  private static final String CATALOG_SCHEMA_ID =
      "https://chromascape.dev/replay/replay-playback-catalog-v1.schema.json";
  private static final String FRAME_SCHEMA_ID =
      "https://chromascape.dev/replay/replay-playback-frame-page-v1.schema.json";
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

  private static JsonNode catalogSchemaDocument;
  private static JsonNode frameSchemaDocument;
  private static JsonSchema catalogSchema;
  private static JsonSchema frameSchema;

  @BeforeAll
  static void loadSchemas() throws IOException {
    catalogSchemaDocument = readResource("/contracts/replay/replay-playback-catalog-v1.schema.json");
    frameSchemaDocument = readResource("/contracts/replay/replay-playback-frame-page-v1.schema.json");
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    SchemaValidatorsConfig config = new SchemaValidatorsConfig();
    config.setFailFast(false);
    catalogSchema = factory.getSchema(catalogSchemaDocument, config);
    frameSchema = factory.getSchema(frameSchemaDocument, config);
  }

  @Test
  void schemasDeclareExactIdentifiersRequiredFieldsAndStrictWireShape() {
    assertEquals(CATALOG_SCHEMA_ID, catalogSchemaDocument.path("$id").asText());
    assertEquals(FRAME_SCHEMA_ID, frameSchemaDocument.path("$id").asText());
    assertEquals(
        Set.of("schema", "entries", "nextCursor", "hasMore"),
        requiredNames(catalogSchemaDocument));
    assertEquals(
        Set.of(
            "schema",
            "recordingId",
            "sessionId",
            "accountName",
            "startTick",
            "endTick",
            "frames",
            "nextCursor",
            "hasMore"),
        requiredNames(frameSchemaDocument));
    assertEquals(
        Set.of(
            "recordingId",
            "sessionId",
            "accountName",
            "scriptName",
            "startTime",
            "endTime",
            "startTick",
            "endTick",
            "tickCount",
            "positionFrameCount",
            "logCount",
            "positionCoveragePct",
            "replayable",
            "replayabilityReason"),
        requiredNames(catalogSchemaDocument.path("$defs").path("catalogEntry")));

    JsonNode frameProperties = frameSchemaDocument.path("properties");
    assertFalse(frameProperties.has("nextFromTick"));
    assertTypeIncludesNull(frameProperties.path("nextCursor"));
    assertTypeIncludesNull(frameSchemaDocument.path("$defs").path("frame").path("properties").path("timestamp"));
    JsonNode logProperties = frameSchemaDocument.path("$defs").path("tickLog").path("properties");
    assertTypeIncludesNull(logProperties.path("timestamp"));
    assertTypeIncludesNull(logProperties.path("correlationId"));
    JsonNode frameDefinition = frameSchemaDocument.path("$defs").path("frame");
    assertEquals(
        Set.of(
            "tick",
            "timestamp",
            "playerState",
            "inventory",
            "equipment",
            "threats",
            "projectiles",
            "cooldowns",
            "quest",
            "questHelper",
            "npcs",
            "gameObjects",
            "groundObjects",
            "groundItems",
            "players",
            "widgets",
            "schemaVersion",
            "logs"),
        requiredNames(frameDefinition));
    assertEquals("array", frameProperties.path("frames").path("type").asText());
    assertEquals("array", frameDefinition.path("properties").path("logs").path("type").asText());
    assertEquals(
        Set.of("ACTION", "DIALOGUE", "VARBIT"),
        arrayValues(logProperties.path("categories").path("items").path("enum")));
  }

  @Test
  void emptyAndPopulatedPagesValidateWithTheApplicationWireMapper() throws Exception {
    ReplayPlaybackCatalogResponse emptyCatalog = new ReplayPlaybackCatalogResponse();
    ReplayPlaybackFramePageResponse emptyFrames =
        new ReplayPlaybackFramePageResponse("recording-empty", "session-empty", List.of(), null, false);
    assertValid(catalogSchema, MAPPER.readTree(MAPPER.writeValueAsString(emptyCatalog)));
    assertValid(frameSchema, MAPPER.readTree(MAPPER.writeValueAsString(emptyFrames)));

    LocalDateTime timestamp = LocalDateTime.of(2026, 8, 28, 3, 12, 13);
    ReplayPlaybackCatalogEntry entry =
        new ReplayPlaybackCatalogEntry(
            "recording-1",
            "session-1",
            null,
            "fishing",
            timestamp,
            null,
            0L,
            null,
            2L,
            1L,
            3L,
            50.0,
            true,
            "Replayable observed tick evidence");
    ReplayPlaybackCatalogResponse catalog =
        new ReplayPlaybackCatalogResponse(List.of(entry), "catalog-cursor", true);

    FrameData frame = new FrameData();
    frame.setTick(4L);
    frame.setTimestamp(timestamp);
    frame.setPlayerState(MAPPER.createObjectNode().put("x", 3200).put("z", 3201));
    frame.setInventory(MAPPER.createArrayNode());
    frame.setEquipment(MAPPER.createArrayNode());
    frame.setThreats(MAPPER.createArrayNode());
    frame.setProjectiles(MAPPER.createArrayNode());
    frame.setCooldowns(MAPPER.createObjectNode());
    frame.setQuest(MAPPER.createObjectNode());
    frame.setQuestHelper(MAPPER.createObjectNode().put("step", 2));
    frame.setNpcs(MAPPER.createArrayNode());
    frame.setGameObjects(MAPPER.createArrayNode());
    frame.setGroundObjects(MAPPER.createArrayNode());
    frame.setGroundItems(MAPPER.createArrayNode());
    frame.setPlayers(MAPPER.createArrayNode());
    frame.setWidgets(MAPPER.createArrayNode());
    frame.setSchemaVersion(1);
    frame.setLogs(
        List.of(
            new ReplayPlaybackLog(
                10L,
                4L,
                null,
                "dialogue",
                "INFO",
                "DialogueOpened",
                "hello",
                MAPPER.createObjectNode(),
                null,
                List.of("ACTION", "DIALOGUE")),
            new ReplayPlaybackLog(
                11L,
                4L,
                timestamp,
                "Game",
                "INFO",
                "VarBitChanged",
                "changed",
                MAPPER.createObjectNode(),
                UUID.randomUUID(),
                List.of("ACTION", "VARBIT"))));
    ReplayPlaybackFramePageResponse frames =
        new ReplayPlaybackFramePageResponse(
            "recording-1", "session-1", List.of(frame), "frame-cursor", true, "account", 0L, 4L);

    assertValid(catalogSchema, MAPPER.readTree(MAPPER.writeValueAsString(catalog)));
    assertValid(frameSchema, MAPPER.readTree(MAPPER.writeValueAsString(frames)));
  }

  @Test
  void schemasRejectUnexpectedPropertiesAndUnknownLogCategories() throws Exception {
    ObjectNode extra =
        (ObjectNode)
            MAPPER.readTree(
                MAPPER.writeValueAsString(new ReplayPlaybackCatalogResponse()));
    extra.put("unexpected", true);
    assertFalse(catalogSchema.validate(extra).isEmpty());

    FrameData validFrame = new FrameData();
    validFrame.setTick(1L);
    validFrame.setPlayerState(MAPPER.createObjectNode());
    validFrame.setInventory(MAPPER.createArrayNode());
    validFrame.setEquipment(MAPPER.createArrayNode());
    validFrame.setThreats(MAPPER.createArrayNode());
    validFrame.setProjectiles(MAPPER.createArrayNode());
    validFrame.setCooldowns(MAPPER.createObjectNode());
    validFrame.setQuest(MAPPER.createObjectNode());
    validFrame.setQuestHelper(MAPPER.createObjectNode());
    validFrame.setNpcs(MAPPER.createArrayNode());
    validFrame.setGameObjects(MAPPER.createArrayNode());
    validFrame.setGroundObjects(MAPPER.createArrayNode());
    validFrame.setGroundItems(MAPPER.createArrayNode());
    validFrame.setPlayers(MAPPER.createArrayNode());
    validFrame.setWidgets(MAPPER.createArrayNode());
    validFrame.setSchemaVersion(1);
    validFrame.setLogs(
        List.of(
            new ReplayPlaybackLog(
                1L,
                1L,
                null,
                "test",
                "INFO",
                "test",
                "test",
                MAPPER.createObjectNode(),
                null,
                List.of("ACTION", "UNKNOWN"))));
    JsonNode invalidFramePage =
        MAPPER.readTree(
            MAPPER.writeValueAsString(
                new ReplayPlaybackFramePageResponse(
                    "recording", "session", List.of(validFrame), null, false)));
    assertFalse(frameSchema.validate(invalidFramePage).isEmpty());
  }

  private static JsonNode readResource(String resource) throws IOException {
    try (var input = ReplayPlaybackContractSerializationTest.class.getResourceAsStream(resource)) {
      assertNotNull(input, "missing schema resource " + resource);
      return MAPPER.readTree(input);
    }
  }

  private static Set<String> requiredNames(JsonNode schema) {
    return arrayValues(schema.path("required"));
  }

  private static Set<String> arrayValues(JsonNode array) {
    Set<String> values = new LinkedHashSet<>();
    array.forEach(value -> values.add(value.asText()));
    return Set.copyOf(values);
  }

  private static void assertTypeIncludesNull(JsonNode property) {
    assertTrue(property.path("type").isArray());
    assertTrue(arrayValues(property.path("type")).contains("null"));
  }

  private static void assertValid(JsonSchema schema, JsonNode value) {
    Set<ValidationMessage> errors = schema.validate(value);
    assertTrue(errors.isEmpty(), () -> "schema validation errors: " + errors);
  }
}

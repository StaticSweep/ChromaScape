package com.chromascape.web.aiplayer.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.integration.micro6.service.CatalogFixture;
import com.chromascape.web.integration.micro6.service.Micro6CatalogV2Contract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class CatalogContractTest {
  static final ObjectMapper JSON = new ObjectMapper();

  static JsonNode fishing() throws Exception {
    try (var stream = CatalogContractTest.class.getResourceAsStream("/sc641/fishing-fields.json")) {
      return JSON.readTree(stream);
    }
  }

  @Test
  void existingMicro6GoldenLightAndFullContractsRemainValid() {
    assertTrue(Micro6CatalogV2Contract.validateLightweight(CatalogFixture.lightweight()).valid());
    assertTrue(Micro6CatalogV2Contract.validateFull(CatalogFixture.full()).valid());
    assertEquals(
        CatalogFixture.GOLDEN_CATALOG_HASH,
        CatalogNormalizer.normalize(CatalogFixture.full(), true).path("catalog_hash").asText());
  }

  @Test
  void displayAliasesCollapseAndLightweightNeverReturnsSettings() {
    ObjectNode raw = CatalogFixture.full();
    ObjectNode display =
        JSON.createObjectNode()
            .put("task_key", "FISH_TRAINING")
            .put("target_enum", "FISHING_TRAINING")
            .put("task_type", "DISPLAY")
            .put("display_name", "Fish practice");
    raw.withArray("tasks").add(display);
    JsonNode normalized = CatalogNormalizer.normalize(raw, false);
    assertEquals(1, normalized.path("tasks").size());
    JsonNode task = normalized.path("tasks").get(0);
    assertFalse(task.has("args_schema"));
    assertEquals("NOT_REQUESTED", task.path("settings_schema_state").asText());
    assertTrue(task.path("search_aliases").toString().contains("FISH_PRACTICE"));
    assertEquals("UNKNOWN", task.path("live_eligibility").asText());
  }

  @Test
  void duplicateCanonicalKeysAndAliasCollisionsFailExplicitly() {
    ObjectNode raw = CatalogFixture.full();
    raw.withArray("tasks").add(raw.path("tasks").get(0).deepCopy());
    ObjectNode duplicate = raw;
    assertEquals(
        "INVALID_CATALOG",
        assertThrows(CatalogProblem.class, () -> CatalogNormalizer.normalize(duplicate, true))
            .code());
    raw = CatalogFixture.full();
    ObjectNode second = ((ObjectNode) raw.path("tasks").get(0)).deepCopy();
    second.put("task_key", "OTHER_TASK");
    raw.withArray("tasks").add(second);
    ObjectNode collision = raw;
    assertTrue(
        assertThrows(CatalogProblem.class, () -> CatalogNormalizer.normalize(collision, true))
            .getMessage()
            .contains("collision"));
  }

  @Test
  void fishingDefaultsRangesAndActualDependencyNamesAreValidated() throws Exception {
    JsonNode schema = fishing();
    ObjectNode values = TaskSettingsValidator.validate(schema, JSON.createObjectNode());
    assertEquals(20000, values.path("supplyBudget").asInt());
    assertEquals("Lumbridge Swamp", values.path("locationName").asText());
    values.put("targetFish", "Trout");
    assertTrue(
        assertThrows(CatalogProblem.class, () -> TaskSettingsValidator.validate(schema, values))
            .errors()
            .containsKey("locationName"));
    values
        .put("locationName", "Barbarian Village")
        .put("supplyBudget", 999)
        .put("supplyBatchSize", 5001)
        .put("useBank", "true")
        .put("arbitrary", 1);
    CatalogProblem errors =
        assertThrows(CatalogProblem.class, () -> TaskSettingsValidator.validate(schema, values));
    assertTrue(
        errors
            .errors()
            .keySet()
            .containsAll(
                java.util.Set.of("supplyBudget", "supplyBatchSize", "useBank", "arbitrary")));
  }

  @Test
  void inactiveValuesAreExcludedAndCannotBeSmuggled() throws Exception {
    ObjectNode values = JSON.createObjectNode().put("stopCondition", "Unlimited");
    assertFalse(TaskSettingsValidator.validate(fishing(), values).has("stopConditionValue"));
    values.put("stopConditionValue", 999);
    assertTrue(
        assertThrows(CatalogProblem.class, () -> TaskSettingsValidator.validate(fishing(), values))
            .errors()
            .containsKey("stopConditionValue"));
  }

  @Test
  void integerPrecisionMultiSelectAndCyclesAreRejected() throws Exception {
    JsonNode schema =
        JSON.readTree(
            "{\"fields\":[{\"key\":\"amount\",\"type\":\"integer\"},{\"key\":\"flags\",\"type\":\"multi_select\",\"options\":[\"A\",\"B\"]}]}");
    JsonNode bad = JSON.readTree("{\"amount\":9007199254740992,\"flags\":[\"A\",\"A\"]}");
    assertEquals(
        2,
        assertThrows(CatalogProblem.class, () -> TaskSettingsValidator.validate(schema, bad))
            .errors()
            .size());
    JsonNode circular =
        JSON.readTree(
            "{\"fields\":[{\"key\":\"a\",\"type\":\"string\",\"depends_on\":\"b\"},{\"key\":\"b\",\"type\":\"string\",\"depends_on\":\"a\"}]}");
    assertEquals(
        "INVALID_SCHEMA",
        assertThrows(CatalogProblem.class, () -> TaskSettingsValidator.schema(circular)).code());
    assertThrows(CatalogProblem.class, () -> TaskSettingsValidator.schema(JSON.createObjectNode()));
  }

  @Test
  void invalidDefaultsAndDependencyMapsAreUnavailableSchemas() throws Exception {
    JsonNode badDefault =
        JSON.readTree(
            "{\"fields\":[{\"key\":\"amount\",\"type\":\"integer\",\"default\":0,\"min\":1}]}");
    assertEquals(
        "INVALID_SCHEMA",
        assertThrows(CatalogProblem.class, () -> TaskSettingsValidator.schema(badDefault)).code());
    JsonNode missingParent =
        JSON.readTree(
            "{\"fields\":[{\"key\":\"location\",\"type\":\"select\",\"options_by_dependency\":{\"a\":[\"b\"]}}]}");
    assertThrows(CatalogProblem.class, () -> TaskSettingsValidator.schema(missingParent));
    JsonNode required =
        JSON.readTree("{\"fields\":[{\"key\":\"name\",\"type\":\"string\",\"required\":true}]}");
    assertDoesNotThrow(() -> TaskSettingsValidator.schema(required));
    assertThrows(
        CatalogProblem.class,
        () -> TaskSettingsValidator.validate(required, JSON.createObjectNode()));
  }
}

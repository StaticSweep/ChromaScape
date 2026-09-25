package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalSettingsHasherTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void canonicalJsonSortsObjectKeysButPreservesArrayOrder() throws Exception {
    String canonical =
        CanonicalSettingsHasher.canonicalJson(
            objectMapper.readTree("{\"z\":[{\"b\":true,\"a\":null},2],\"a\":\"café\"}"));

    assertThat(canonical).isEqualTo("{\"a\":\"café\",\"z\":[{\"a\":null,\"b\":true},2]}");
  }

  @Test
  void reorderedObjectsHaveTheSameHash() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("targetFish", "TROUT");
    first.put("stopConditionValue", 40);
    first.put("nested", Map.of("z", false, "a", List.of(1, 2)));

    Map<String, Object> second = new LinkedHashMap<>();
    second.put("nested", Map.of("a", List.of(1, 2), "z", false));
    second.put("stopConditionValue", 40);
    second.put("targetFish", "TROUT");

    assertThat(CanonicalSettingsHasher.sha256(objectMapper, first))
        .isEqualTo(CanonicalSettingsHasher.sha256(objectMapper, second))
        .startsWith("sha256:");
  }

  @Test
  void settingsContractNormalizesNumbersLineEndingsUnicodeAndRetainsMetadataKeys()
      throws Exception {
    JsonNode first =
        objectMapper.readTree(
            "{\"run_id\":\"r\",\"text\":\"e\\u0301\\r\\n\",\"value\":40,\"items\":[true,null]}");
    JsonNode second =
        objectMapper.readTree(
            "{\"items\":[true,null],\"value\":40.0,\"text\":\"é\\n\",\"run_id\":\"r\"}");

    assertThat(CanonicalSettingsHasher.canonicalJson(first))
        .isEqualTo(CanonicalSettingsHasher.canonicalJson(second))
        .contains("run_id")
        .contains("40");
    assertThat(CanonicalSettingsHasher.sha256(objectMapper, first))
        .isEqualTo(CanonicalSettingsHasher.sha256(objectMapper, second));
  }

  @Test
  void committedContractVectorsMatchTheHasher() throws Exception {
    try (InputStream input =
        getClass().getResourceAsStream("/contracts/builder-settings-canonical-json-v1.json")) {
      assertThat(input).isNotNull();
      JsonNode contract = objectMapper.readTree(input);
      assertThat(contract.path("contract").asText())
          .isEqualTo("builder-settings-canonical-json/v1");
      for (JsonNode vector : contract.path("cases")) {
        JsonNode settings = vector.path("settings");
        assertThat(CanonicalSettingsHasher.canonicalJson(settings))
            .isEqualTo(vector.path("canonical").asText());
        assertThat(CanonicalSettingsHasher.sha256(objectMapper, settings))
            .isEqualTo(vector.path("sha256").asText());
      }
    }
  }
}

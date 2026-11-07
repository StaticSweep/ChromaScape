package com.chromascape.web.integration.micro6.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Captured from the exact signed Micro6 8f5fff2d capture artifact; no authored hash. */
class CatalogLiveProducerCompatibilityTest {
  private ObjectNode payload(String mode) throws Exception {
    try (var input = getClass().getResourceAsStream("/micro6/live-producer-" + mode + ".json")) {
      return (ObjectNode) new ObjectMapper().readTree(input);
    }
  }

  @Test
  void acceptsActualFullAndLightweightCanonicalModel() throws Exception {
    var full = payload("full");
    var light = payload("lightweight");
    assertThat(Micro6CatalogV2Contract.validateFull(full).reasons()).isEmpty();
    assertThat(Micro6CatalogV2Contract.validateLightweight(light).reasons()).isEmpty();
    assertThat(Micro6CatalogV2Contract.validateFull(full).catalogHash())
        .isEqualTo("sha256:86690a8b63054a05701d65deb9eb5005d1246e706a6ec49689fd03c6f2ae2209");
  }

  @Test
  void refusesLightweightSchemaHashTampering() throws Exception {
    var light = payload("lightweight");
    ((ObjectNode) light.path("tasks").get(0))
        .put("settings_schema_hash", "sha256:" + "0".repeat(64));
    assertThat(Micro6CatalogV2Contract.validateLightweight(light).valid()).isFalse();
  }

  @Test
  void refusesChangedTaskMetadata() throws Exception {
    var full = payload("full");
    ((ObjectNode) full.path("tasks").get(0)).put("description", "changed bytes");
    assertThat(Micro6CatalogV2Contract.validateFull(full).reasons())
        .contains("catalog_hash_mismatch");
  }

  @Test
  void refusesChangedSchemaAndAliases() throws Exception {
    var schema = payload("full");
    ((ObjectNode) schema.path("tasks").get(0).path("args_schema"))
        .put("description", "changed schema");
    assertThat(Micro6CatalogV2Contract.validateFull(schema).reasons())
        .contains("catalog_hash_mismatch");
    var aliases = payload("full");
    ((ObjectNode) aliases.path("aliases").get(0)).put("alias", "changed alias");
    assertThat(Micro6CatalogV2Contract.validateFull(aliases).reasons())
        .contains("catalog_hash_mismatch");
  }

  @Test
  void refusesForgedHashAndIgnoresVolatileRuntimeState() throws Exception {
    var full = payload("full");
    full.put("generated_at", "2099-01-01T00:00:00Z");
    full.put("account_name", "different runtime account");
    assertThat(Micro6CatalogV2Contract.validateFull(full).reasons()).isEmpty();
    full.put("catalog_hash", "sha256:" + "0".repeat(64));
    assertThat(Micro6CatalogV2Contract.validateFull(full).reasons())
        .contains("catalog_hash_mismatch");
  }
}

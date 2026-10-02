package com.chromascape.web.integration.micro6.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared fixtures for Micro6 catalog v2 tests. The hashes are the golden values produced by the
 * real Micro6 {@code CanonicalJson} + {@code CatalogV2Publication} hash functions, so a valid
 * catalog here is byte-for-byte the same payload the executor would attest.
 */
public final class CatalogFixture {
  public static final String GOLDEN_CATALOG_HASH =
      "sha256:17b13670b40dba066bb869aa2d80dbaaf8f83c76113d8a53d201794ce02d5ad4";
  public static final String GOLDEN_SETTINGS_SCHEMA_HASH =
      "sha256:95d587eacd8492a5f65e56e34fcf6085ab0c85aa2643da7f69823b9be0cf0957";
  public static final String GOLDEN_SCHEMA_BUNDLE_HASH =
      "sha256:815b527d3704999719e42c882536740f6313401b1bba9ed4422b173c04a4f100";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CatalogFixture() {}

  public static ObjectNode lightweight() {
    ObjectNode payload = base("LIGHTWEIGHT");
    ArrayNode tasks = MAPPER.createArrayNode();
    tasks.add(task(false));
    payload.set("tasks", tasks);
    payload.set("aliases", aliases());
    payload.put("catalog_hash", GOLDEN_CATALOG_HASH);
    payload.put("schema_not_requested_count", 1);
    return payload;
  }

  public static ObjectNode full() {
    ObjectNode payload = base("FULL");
    ArrayNode tasks = MAPPER.createArrayNode();
    tasks.add(task(true));
    payload.set("tasks", tasks);
    payload.set("aliases", aliases());
    payload.put("catalog_hash", GOLDEN_CATALOG_HASH);
    payload.put("schema_bundle_hash", GOLDEN_SCHEMA_BUNDLE_HASH);
    return payload;
  }

  private static ObjectNode base(String mode) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("version", "2");
    payload.put("catalog_mode", mode);
    payload.put("catalog_state", "READY");
    payload.put("catalog_valid", true);
    payload.put("catalog_version", "2");
    payload.put("capability_version", "micro6-executor/v1");
    payload.put("source_client_type", "micro6");
    payload.put("executor_profile", "accountbuilder_builderpro");
    payload.put("executor_instance_id", "executor-fixture-1");
    return payload;
  }

  public static ObjectNode task(boolean withSchema) {
    ObjectNode task = MAPPER.createObjectNode();
    task.put("key", "FISHING_TRAINING");
    task.put("task_key", "FISHING_TRAINING");
    task.put("name", "Fishing Training");
    task.put("display_name", "Fishing Training");
    task.put("panel_display_name", "Fishing Training");
    task.put("category", "fishing");
    task.put("category_family", "fishing");
    task.put("enabled", true);
    task.put("runnable", true);
    task.put("task_type", "SKILLING");
    task.put("type", "SKILLING");
    task.put("stability_tier", "standard");
    task.put("membership", "FREE");
    task.put("primary_skill", "FISHING");
    task.put("description", "Train Fishing through configured spots.");
    ArrayNode searchAliases = MAPPER.createArrayNode();
    searchAliases.add("Fish Training");
    task.set("search_aliases", searchAliases);
    ArrayNode tags = MAPPER.createArrayNode();
    tags.add("fishing");
    tags.add("training");
    task.set("tags", tags);
    if (withSchema) {
      task.put("settings_schema_state", "AVAILABLE");
      task.put("settings_schema_version", "1");
      task.put("settings_schema_hash", GOLDEN_SETTINGS_SCHEMA_HASH);
      task.set("args_schema", schema());
    } else {
      task.put("settings_schema_state", "NOT_REQUESTED");
    }
    return task;
  }

  public static ObjectNode schema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("version", 1);
    schema.put("type", "object");
    ArrayNode fields = MAPPER.createArrayNode();
    ObjectNode stop = MAPPER.createObjectNode();
    stop.put("key", "stopConditionValue");
    stop.put("label", "Stop Condition Value");
    stop.put("type", "integer");
    stop.put("min", 1);
    stop.put("max", 99);
    stop.put("default", 50);
    fields.add(stop);
    ObjectNode fish = MAPPER.createObjectNode();
    fish.put("key", "targetFish");
    fish.put("label", "Target Fish");
    fish.put("type", "select");
    fish.put("default", "TROUT");
    ArrayNode options = MAPPER.createArrayNode();
    options.add("TROUT");
    options.add("SALMON");
    options.add("LOBSTER");
    fish.set("options", options);
    fields.add(fish);
    schema.set("fields", fields);
    return schema;
  }

  public static ArrayNode aliases() {
    ObjectNode alias = MAPPER.createObjectNode();
    alias.put("alias", "FISH_TRAINING");
    alias.put("normalized_alias", "FISH_TRAINING");
    alias.put("task_key", "FISHING_TRAINING");
    ArrayNode aliases = MAPPER.createArrayNode();
    aliases.add(alias);
    return aliases;
  }

  public static String json(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (Exception e) {
      throw new RuntimeException("failed to serialize fixture", e);
    }
  }

  /** Recomputes and rewrites the attested catalog hash after a fixture mutation. */
  public static ObjectNode rehash(ObjectNode node) {
    node.put("catalog_hash", Micro6CanonicalHash.catalogHash(node, "micro6-executor/v1"));
    return node;
  }

  /** Recomputes and rewrites the attested schema bundle hash after a task mutation. */
  public static ObjectNode rehashBundle(ObjectNode node) {
    String catalogHash = Micro6CanonicalHash.catalogHash(node, "micro6-executor/v1");
    node.put("catalog_hash", catalogHash);
    String bundle = Micro6CanonicalHash.schemaBundleHash(node, catalogHash);
    if (bundle == null) {
      node.remove("schema_bundle_hash");
    } else {
      node.put("schema_bundle_hash", bundle);
    }
    return node;
  }

  public static JsonNode parse(String json) throws Exception {
    return MAPPER.readTree(json);
  }
}

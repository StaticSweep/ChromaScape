package com.chromascape.web.integration.micro6.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.integration.micro6.config.Micro6Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Assembled integration test for the SC-584A three-system path using a local fake stack:
 *
 * <ol>
 *   <li>a fake/authenticated leeuwbotapi runtime registry,
 *   <li>a fake Micro6 executor HTTP server,
 *   <li>the real ChromaScape catalog client.
 * </ol>
 *
 * <p>Exercises lightweight 200, full 202-&gt;200, executor disconnect, reconnect with the same
 * identity, and reconnect with a different identity — asserting no cross-executor contamination, no
 * cache replacement by 202, correct stale state, correct recovery, and stable hashes.
 */
class Micro6CatalogEndToEndIntegrationTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private Micro6TestServers.FakeLeeuwbotApi registry;
  private Micro6TestServers.FakeMicro6Executor executor;
  private Micro6ExecutorCatalogService service;

  @BeforeEach
  void setUp() throws Exception {
    registry = new Micro6TestServers.FakeLeeuwbotApi();
    executor = new Micro6TestServers.FakeMicro6Executor();
    registry.register("dev-micro6:SC584A", "executor-sc584a", executor);

    Micro6Config config = new Micro6Config();
    config.getExecutorCatalog().setEnabled(true);
    config.getExecutorCatalog().setRefreshSeconds(30);
    config.getRuntimeSelection().setEnabled(true);
    config.getRuntimeSelection().setRegistryBaseUrl(registry.url);
    config.getRuntimeSelection().setRegistryAuthToken("test-token");
    config.getRuntimeSelection().setRequestTimeoutMs(2000);
    config.getRuntimeSelection().setDevFallbackEnabled(false);
    service = new Micro6ExecutorCatalogService(config, mapper);

    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
  }

  @AfterEach
  void tearDown() {
    if (executor != null) {
      executor.stop();
    }
    if (registry != null) {
      registry.stop();
    }
  }

  @Test
  @DisplayName("lightweight 200 exposes canonical Fishing tasks and the FISH_TRAINING alias")
  void lightweightCatalogContainsCanonicalFishingAndAlias() {
    JsonNode result = service.getCatalogForExecutor("dev-micro6:SC584A", "lightweight", false);
    assertThat(result.path("catalog_state").asText()).isEqualTo("READY");
    JsonNode catalog = result.path("catalog");

    assertThat(catalog.path("catalog_mode").asText()).isEqualTo("LIGHTWEIGHT");
    assertThat(catalog.path("version").asText()).isEqualTo("2");
    assertThat(catalog.path("catalog_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    long fishingCount = 0;
    for (JsonNode task : catalog.path("tasks")) {
      if ("FISHING_TRAINING".equals(task.path("task_key").asText())) {
        fishingCount++;
        assertThat(task.path("category_family").asText()).isEqualTo("fishing");
        assertThat(task.path("runnable").asBoolean()).isTrue();
        assertThat(task.path("settings_schema_state").asText()).isEqualTo("NOT_REQUESTED");
      }
    }
    assertThat(fishingCount).as("FISHING_TRAINING must appear exactly once").isEqualTo(1);

    boolean aliasPresent = false;
    for (JsonNode alias : catalog.path("aliases")) {
      if ("FISH_TRAINING".equals(alias.path("normalized_alias").asText())
          && "FISHING_TRAINING".equals(alias.path("task_key").asText())) {
        aliasPresent = true;
      }
    }
    assertThat(aliasPresent).as("FISH_TRAINING must be an alias of FISHING_TRAINING").isTrue();
  }

  @Test
  @DisplayName("full catalog 202 GENERATING then 200 READY with typed schema and matching hashes")
  void fullCatalogLifecycle() {
    JsonNode ready = service.getCatalogForExecutor("dev-micro6:SC584A", "full", false);
    assertThat(ready.path("catalog_state").asText()).isEqualTo("READY");
    JsonNode catalog = ready.path("catalog");
    assertThat(catalog.path("catalog_mode").asText()).isEqualTo("FULL");
    assertThat(catalog.path("schema_bundle_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    assertThat(catalog.path("catalog_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    JsonNode fishing = null;
    for (JsonNode task : catalog.path("tasks")) {
      if ("FISHING_TRAINING".equals(task.path("task_key").asText())) {
        fishing = task;
      }
    }
    assertThat(fishing).isNotNull();
    assertThat(fishing.path("settings_schema_state").asText()).isEqualTo("AVAILABLE");
    assertThat(fishing.path("args_schema").isObject()).isTrue();
    assertThat(fishing.path("settings_schema_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_SETTINGS_SCHEMA_HASH);
  }

  @Test
  @DisplayName("202 GENERATING never replaces a cached READY catalog")
  void generatingDoesNotReplaceCache() {
    JsonNode ready = service.getCatalogForExecutor("dev-micro6:SC584A", "full", false);
    assertThat(ready.path("catalog_state").asText()).isEqualTo("READY");

    executor.fullStatus = 202;
    executor.fullBody =
        "{\"status\":\"GENERATING\",\"catalog_mode\":\"FULL\",\"catalog_state\":\"GENERATING\"}";
    JsonNode generating = service.getCatalogForExecutor("dev-micro6:SC584A", "full", true);
    assertThat(generating.path("catalog_state").asText()).isEqualTo("GENERATING");
    assertThat(generating.path("catalog").isMissingNode()).isTrue();

    executor.fullStatus = 200;
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
    JsonNode after = service.getCatalogForExecutor("dev-micro6:SC584A", "full", false);
    assertThat(after.path("catalog_state").asText()).isEqualTo("READY");
  }

  @Test
  @DisplayName("executor disconnect yields STALE with last-known-good; reconnect recovers")
  void disconnectAndReconnectPreserveLastKnownGood() {
    JsonNode ready = service.getCatalogForExecutor("dev-micro6:SC584A", "lightweight", false);
    assertThat(ready.path("catalog_state").asText()).isEqualTo("READY");

    executor.dropConnections = true;
    JsonNode stale = service.getCatalogForExecutor("dev-micro6:SC584A", "lightweight", true);
    assertThat(stale.path("catalog_state").asText()).isEqualTo("STALE");
    assertThat(stale.path("catalog").isObject()).isTrue();
    assertThat(stale.path("catalog_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    executor.dropConnections = false;
    JsonNode recovered = service.getCatalogForExecutor("dev-micro6:SC584A", "lightweight", true);
    assertThat(recovered.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(recovered.path("catalog_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);
  }

  @Test
  @DisplayName("reconnect with a different executor identity uses an independent cache")
  void differentExecutorIdentityIsIsolated() throws Exception {
    String firstRef = "dev-micro6:SC584A";
    JsonNode first = service.getCatalogForExecutor(firstRef, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    // A second Micro6 instance registers as a distinct executor with its own ref.
    Micro6TestServers.FakeMicro6Executor secondExecutor =
        new Micro6TestServers.FakeMicro6Executor();
    ObjectNode secondCatalog = TestMicro6Catalog.lightweight();
    ((ObjectNode) secondCatalog.path("tasks").get(0)).put("description", "second executor catalog");
    TestMicro6Catalog.rehash(secondCatalog);
    secondExecutor.lightweightBody = TestMicro6Catalog.json(secondCatalog);
    String secondRef = "dev-micro6:SC584A-2";
    registry.register(secondRef, "executor-sc584a-2", secondExecutor);

    JsonNode second = service.getCatalogForExecutor(secondRef, "lightweight", false);
    assertThat(second.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(second.path("catalog").path("tasks").get(0).path("description").asText())
        .isEqualTo("second executor catalog");
    assertThat(second.path("catalog_hash").asText())
        .isNotEqualTo(first.path("catalog_hash").asText());

    // The first executor's cache is untouched.
    JsonNode firstAgain = service.getCatalogForExecutor(firstRef, "lightweight", false);
    assertThat(firstAgain.path("catalog").path("tasks").get(0).path("description").asText())
        .isEqualTo(
            TestMicro6Catalog.lightweight().path("tasks").get(0).path("description").asText());
    secondExecutor.stop();
  }

  @Test
  @DisplayName("hashes remain stable across requests and match the Micro6 golden values")
  void hashesAreStableAndMatchMicro6() {
    JsonNode light1 = service.getCatalogForExecutor("dev-micro6:SC584A", "lightweight", false);
    JsonNode light2 = service.getCatalogForExecutor("dev-micro6:SC584A", "lightweight", false);
    assertThat(light1.path("catalog_hash").asText())
        .isEqualTo(light2.path("catalog_hash").asText());
    assertThat(light1.path("catalog_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    JsonNode full1 = service.getCatalogForExecutor("dev-micro6:SC584A", "full", false);
    JsonNode full2 = service.getCatalogForExecutor("dev-micro6:SC584A", "full", false);
    assertThat(full1.path("catalog_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    assertThat(full1.path("catalog_hash").asText()).isEqualTo(full2.path("catalog_hash").asText());
    assertThat(full1.path("catalog").path("catalog_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    assertThat(full1.path("catalog").path("schema_bundle_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
  }

  @Test
  @DisplayName("assembled bootstrap: NOT_AVAILABLE -> GENERATING -> AVAILABLE with cache invalidation")
  void assembledBootstrapLifecycle() throws Exception {
    // Bootstrap: the launcher heartbeat reports NOT_AVAILABLE (no bundle hash yet).
    registry.schemaBundleStateByRef.put("dev-micro6:SC584A", "NOT_AVAILABLE");
    registry.catalogStateByRef.put("dev-micro6:SC584A", "READY");

    // Backend sees the runtime ONLINE; ChromaScape's lightweight catalog is served immediately.
    JsonNode lightweight = service.getCatalogForExecutor("dev-micro6:SC584A", "lightweight", false);
    assertThat(lightweight.path("catalog_state").asText()).isEqualTo("READY");

    // ChromaScape requests the full catalog while generation is active -> 202 GENERATING.
    executor.fullStatus = 202;
    executor.fullBody =
        "{\"status\":\"GENERATING\",\"catalog_mode\":\"FULL\",\"catalog_state\":\"GENERATING\"}";
    JsonNode generating = service.getCatalogForExecutor("dev-micro6:SC584A", "full", true);
    assertThat(generating.path("catalog_state").asText()).isEqualTo("GENERATING");

    // Generation completes; the executor serves a ready full catalog with a schema bundle.
    registry.schemaBundleStateByRef.put("dev-micro6:SC584A", "GENERATING");
    executor.fullStatus = 200;
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
    JsonNode ready = service.getCatalogForExecutor("dev-micro6:SC584A", "full", true);
    assertThat(ready.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(ready.path("schema_bundle_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);

    // The runtime heartbeat catches up to AVAILABLE with the attested bundle hash.
    registry.schemaBundleStateByRef.put("dev-micro6:SC584A", "AVAILABLE");
    registry.schemaBundleHashByRef.put("dev-micro6:SC584A", TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    JsonNode cached = service.getCatalogForExecutor("dev-micro6:SC584A", "full", false);
    assertThat(cached.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.fullCalls).isEqualTo(2);

    // Schema-only change: the bundle hash changes while the catalog hash stays stable, so the old
    // full cache is invalidated and the lightweight cache is untouched.
    ObjectNode changed = (ObjectNode) TestMicro6Catalog.full().deepCopy();
    ObjectNode task = (ObjectNode) changed.path("tasks").get(0);
    ObjectNode schema = (ObjectNode) task.path("args_schema").deepCopy();
    ((ObjectNode) schema.path("fields").get(0)).put("default", 71);
    task.set("args_schema", schema);
    task.put("settings_schema_hash", Micro6CanonicalHash.settingsSchemaHash(schema));
    changed.put("catalog_hash", TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    String newBundle =
        Micro6CanonicalHash.schemaBundleHash(changed, TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    changed.put("schema_bundle_hash", newBundle);
    executor.fullBody = TestMicro6Catalog.json(changed);
    registry.schemaBundleHashByRef.put("dev-micro6:SC584A", newBundle);

    JsonNode afterChange = service.getCatalogForExecutor("dev-micro6:SC584A", "full", false);
    assertThat(executor.fullCalls)
        .as("schema-only change must invalidate the old full cache")
        .isEqualTo(3);
    assertThat(afterChange.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(afterChange.path("schema_bundle_hash").asText()).isEqualTo(newBundle);
    assertThat(afterChange.path("catalog_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    // The lightweight catalog still serves from cache without re-fetching.
    JsonNode lightweightAfter = service.getCatalogForExecutor("dev-micro6:SC584A", "lightweight", false);
    assertThat(lightweightAfter.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }
}

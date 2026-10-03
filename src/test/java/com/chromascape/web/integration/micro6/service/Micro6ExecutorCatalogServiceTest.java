package com.chromascape.web.integration.micro6.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.chromascape.web.aiplayer.catalog.AiPlayerCatalogService;
import com.chromascape.web.aiplayer.catalog.CatalogStore;
import com.chromascape.web.integration.micro6.config.Micro6Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Micro6ExecutorCatalogServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private Micro6TestServers.FakeLeeuwbotApi registry;
  private Micro6TestServers.FakeMicro6Executor executor;
  private Micro6ExecutorCatalogService service;

  @AfterEach
  void tearDown() {
    if (executor != null) {
      executor.stop();
    }
    if (registry != null) {
      registry.stop();
    }
  }

  private void start(String endpointRef, String executorInstanceId) throws Exception {
    registry = new Micro6TestServers.FakeLeeuwbotApi();
    executor = new Micro6TestServers.FakeMicro6Executor();
    registry.register(endpointRef, executorInstanceId, executor);
    Micro6Config config = new Micro6Config();
    config.getExecutorCatalog().setEnabled(true);
    config.getExecutorCatalog().setRefreshSeconds(30);
    config.getRuntimeSelection().setEnabled(true);
    config.getRuntimeSelection().setRegistryBaseUrl(registry.url);
    config.getRuntimeSelection().setRegistryAuthToken("test-token");
    config.getRuntimeSelection().setRequestTimeoutMs(2000);
    config.getRuntimeSelection().setDevFallbackEnabled(false);
    service = new Micro6ExecutorCatalogService(config, mapper);
  }

  @Test
  void validLightweightCatalogIsCached() throws Exception {
    String ref = "dev-micro6:AAAA";
    start(ref, "executor-1");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(first.path("catalog").isObject()).isTrue();
    assertThat(first.path("catalog_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(second.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void validFullCatalogIsCached() throws Exception {
    String ref = "dev-micro6:BBBB";
    start(ref, "executor-2");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());

    JsonNode first = service.getCatalogForExecutor(ref, "full", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(first.path("schema_bundle_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    assertThat(executor.fullCalls).isEqualTo(1);

    service.getCatalogForExecutor(ref, "full", false);
    assertThat(executor.fullCalls).isEqualTo(1);
  }

  @Test
  void stale202FromReadyRuntimeNeverReplacesCache() throws Exception {
    String ref = "dev-micro6:CCCC";
    start(ref, "executor-3");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());

    JsonNode ready = service.getCatalogForExecutor(ref, "full", false);
    assertThat(ready.path("catalog_state").asText()).isEqualTo("READY");

    // A READY runtime returning a 202 is a stale generation attestation: the response is never
    // accepted as GENERATING, the acquisition does not settle, and the cache is never replaced.
    executor.fullStatus = 202;
    executor.fullBody =
        "{\"status\":\"GENERATING\",\"catalog_mode\":\"FULL\",\"catalog_state\":\"GENERATING\"}";
    JsonNode rejected = service.getCatalogForExecutor(ref, "full", true);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("RUNTIME_CHANGED");
    assertThat(rejected.path("catalog").isMissingNode()).isTrue();

    executor.fullStatus = 200;
    JsonNode cached = service.getCatalogForExecutor(ref, "full", false);
    assertThat(cached.path("catalog_state").asText()).isEqualTo("READY");
  }

  @Test
  void connectionFailureReturnsStaleWithLastKnownGood() throws Exception {
    String ref = "dev-micro6:DDDD";
    start(ref, "executor-4");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode ready = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(ready.path("catalog_state").asText()).isEqualTo("READY");

    executor.dropConnections = true;
    JsonNode stale = service.getCatalogForExecutor(ref, "lightweight", true);
    assertThat(stale.path("catalog_state").asText()).isEqualTo("STALE");
    assertThat(stale.path("catalog").isObject()).isTrue();
    assertThat(stale.path("last_known_good").asBoolean()).isTrue();
    assertThat(stale.path("reason_code").asText()).isEqualTo("EXECUTOR_ERROR");
    assertThat(stale.path("detailed_reason_code").asText()).isEqualTo("CATALOG_HTTP_502");
  }

  @Test
  void noCacheReturnsUnavailable() throws Exception {
    String ref = "dev-micro6:EEEE";
    start(ref, "executor-5");
    executor.dropConnections = true;

    JsonNode unavailable = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(unavailable.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(unavailable.path("last_known_good").asBoolean(false)).isFalse();
  }

  @Test
  void serviceUnavailable503DoesNotReplaceCache() throws Exception {
    String ref = "dev-micro6:FFFF";
    start(ref, "executor-6");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode ready = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(ready.path("catalog_state").asText()).isEqualTo("READY");

    executor.lightweightStatus = 503;
    executor.lightweightBody = "{\"error\":\"catalog_unavailable\"}";
    JsonNode after503 = service.getCatalogForExecutor(ref, "lightweight", true);
    assertThat(after503.path("catalog_state").asText()).isEqualTo("STALE");
    assertThat(after503.path("catalog").isObject()).isTrue();
    assertThat(after503.path("reason_code").asText()).isEqualTo("EXECUTOR_UNAVAILABLE");
    assertThat(after503.path("detailed_reason_code").asText()).isEqualTo("CATALOG_503");

    executor.lightweightStatus = 200;
    JsonNode restored = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(restored.path("catalog_state").asText()).isEqualTo("READY");
  }

  @Test
  void malformedCatalogIsRejectedAndNotCached() throws Exception {
    String ref = "dev-micro6:GGGG";
    start(ref, "executor-7");
    ObjectNode malformed = TestMicro6Catalog.lightweight();
    ((ObjectNode) malformed.path("tasks").get(0)).put("display_name", "Changed");
    malformed.put("catalog_hash", TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    executor.lightweightBody = TestMicro6Catalog.json(malformed);

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(executor.lightweightCalls).isEqualTo(1);

    // A subsequent refresh must re-fetch because nothing was cached.
    service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(executor.lightweightCalls).isEqualTo(2);
  }

  @Test
  void unknownEndpointReferenceIsRejected() throws Exception {
    start("dev-micro6:HHHH", "executor-8");
    JsonNode result = service.getCatalogForExecutor("dev-micro6:UNKNOWN", "lightweight", false);
    assertThat(result.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(result.path("reason_code").asText()).isEqualTo("UNKNOWN_ENDPOINT_REF");
  }

  @Test
  void expiredReferenceIsRejected() throws Exception {
    String ref = "dev-micro6:IIII";
    start(ref, "executor-9");
    registry.setState(ref, "STALE");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode result = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(result.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(result.path("reason_code").asText()).isEqualTo("RUNTIME_NOT_SELECTABLE");
    assertThat(executor.lightweightCalls).isZero();
  }

  @Test
  void twoExecutorsMaintainIndependentCaches() throws Exception {
    registry = new Micro6TestServers.FakeLeeuwbotApi();
    Micro6TestServers.FakeMicro6Executor executorA = new Micro6TestServers.FakeMicro6Executor();
    Micro6TestServers.FakeMicro6Executor executorB = new Micro6TestServers.FakeMicro6Executor();
    String refA = "dev-micro6:AAAA";
    String refB = "dev-micro6:BBBB";
    registry.register(refA, "executor-A", executorA);
    registry.register(refB, "executor-B", executorB);
    Micro6Config config = new Micro6Config();
    config.getExecutorCatalog().setEnabled(true);
    config.getRuntimeSelection().setEnabled(true);
    config.getRuntimeSelection().setRegistryBaseUrl(registry.url);
    config.getRuntimeSelection().setRegistryAuthToken("test-token");
    config.getRuntimeSelection().setRequestTimeoutMs(2000);
    service = new Micro6ExecutorCatalogService(config, mapper);
    executor = executorA;

    ObjectNode catalogA =
        TestMicro6Catalog.rehash(
            (ObjectNode) ((ObjectNode) TestMicro6Catalog.lightweight()).deepCopy());
    ((ObjectNode) catalogA.path("tasks").get(0)).put("description", "Executor A catalog");
    TestMicro6Catalog.rehash(catalogA);
    executorA.lightweightBody = TestMicro6Catalog.json(catalogA);
    ObjectNode catalogB = TestMicro6Catalog.lightweight();
    ((ObjectNode) catalogB.path("tasks").get(0)).put("description", "Executor B catalog");
    TestMicro6Catalog.rehash(catalogB);
    executorB.lightweightBody = TestMicro6Catalog.json(catalogB);

    JsonNode resultA = service.getCatalogForExecutor(refA, "lightweight", false);
    JsonNode resultB = service.getCatalogForExecutor(refB, "lightweight", false);

    assertThat(resultA.path("catalog").path("tasks").get(0).path("description").asText())
        .isEqualTo("Executor A catalog");
    assertThat(resultB.path("catalog").path("tasks").get(0).path("description").asText())
        .isEqualTo("Executor B catalog");
    assertThat(resultA.path("catalog_hash").asText())
        .isNotEqualTo(resultB.path("catalog_hash").asText());
  }

  @Test
  void executorsEndpointReturnsCandidates() throws Exception {
    String ref = "dev-micro6:JJJJ";
    start(ref, "executor-10");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    service.getCatalogForExecutor(ref, "lightweight", false);

    JsonNode executors = service.getExecutors();
    assertThat(executors.path("count").asInt()).isEqualTo(1);
    JsonNode candidate = executors.path("executors").get(0);
    assertThat(candidate.path("executor_instance_id").asText()).isEqualTo("executor-10");
    assertThat(candidate.path("runtime_state").asText()).isEqualTo("ONLINE");
    assertThat(candidate.path("selectable").asBoolean()).isTrue();
    assertThat(candidate.path("catalog_state").asText()).isEqualTo("READY");
  }

  @Test
  void devStaticRouteFallbackIsExplicitlyMarked() throws Exception {
    Micro6TestServers.FakeMicro6Executor local = new Micro6TestServers.FakeMicro6Executor();
    local.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    Micro6Config config = new Micro6Config();
    config.getExecutorCatalog().setEnabled(true);
    config.getExecutorCatalog().setBaseUrl(local.url);
    config.getRuntimeSelection().setEnabled(true);
    config.getRuntimeSelection().setDevFallbackEnabled(true);
    config.getRuntimeSelection().setRegistryBaseUrl("http://127.0.0.1:1");
    service = new Micro6ExecutorCatalogService(config, mapper);
    executor = local;

    JsonNode result = service.getCatalogForExecutor("", "lightweight", false);
    assertThat(result.path("route_source").asText()).isEqualTo("DEV_STATIC_ROUTE");
    assertThat(result.path("dev_static_route").asBoolean()).isTrue();
    assertThat(result.path("route_disclaimer").asText()).isNotBlank();
  }

  @Test
  void schemaOnlyChangeInvalidatesFullCache() throws Exception {
    String ref = "dev-micro6:SCHSCHEMA";
    start(ref, "executor-schema");
    ObjectNode initial = TestMicro6Catalog.full();
    executor.fullBody = TestMicro6Catalog.json(initial);
    registry.schemaBundleHashByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.schemaBundleStateByRef.put(ref, "AVAILABLE");

    JsonNode first = service.getCatalogForExecutor(ref, "full", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(first.path("schema_bundle_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    assertThat(executor.fullCalls).isEqualTo(1);

    // Schema-only change: same catalog hash, different schema bundle.
    ObjectNode changed = (ObjectNode) initial.deepCopy();
    ObjectNode task = (ObjectNode) changed.path("tasks").get(0);
    ObjectNode schema = (ObjectNode) task.path("args_schema").deepCopy();
    ((ObjectNode) schema.path("fields").get(0)).put("default", 33);
    task.set("args_schema", schema);
    task.put("settings_schema_hash", Micro6CanonicalHash.settingsSchemaHash(schema));
    changed.put("catalog_hash", TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    String newBundle =
        Micro6CanonicalHash.schemaBundleHash(changed, TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    changed.put("schema_bundle_hash", newBundle);
    executor.fullBody = TestMicro6Catalog.json(changed);
    registry.schemaBundleHashByRef.put(ref, newBundle);

    JsonNode second = service.getCatalogForExecutor(ref, "full", false);
    assertThat(executor.fullCalls)
        .as("schema-only change must invalidate the full cache")
        .isEqualTo(2);
    assertThat(second.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(second.path("schema_bundle_hash").asText()).isEqualTo(newBundle);
  }

  @Test
  void lightweightCacheSurvivesSchemaOnlyChange() throws Exception {
    String ref = "dev-micro6:SCHLIGHT";
    start(ref, "executor-schema");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.schemaBundleHashByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.schemaBundleStateByRef.put(ref, "AVAILABLE");

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(1);

    // A changed bundle must not touch the lightweight cache.
    registry.schemaBundleHashByRef.put(ref, "sha256:" + "c".repeat(64));
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(executor.lightweightCalls)
        .as("lightweight cache must ignore schema bundle changes")
        .isEqualTo(1);
    assertThat(second.path("catalog_state").asText()).isEqualTo("READY");
  }

  @Test
  void runtimeAvailableWithoutBundleIsContractInvalid() throws Exception {
    String ref = "dev-micro6:SCHAVAIL";
    start(ref, "executor-schema");
    registry.schemaBundleStateByRef.put(ref, "AVAILABLE");
    // No schema_bundle_hash is attested while the runtime claims AVAILABLE.

    JsonNode result = service.getCatalogForExecutor(ref, "full", false);
    assertThat(result.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(result.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(executor.fullCalls).isZero();
  }

  @Test
  void tenConcurrentCallersProduceOneRequest() throws Exception {
    String ref = "dev-micro6:TEN";
    start(ref, "executor-ten");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    executor.lightweightDelayMs = 400;

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(10);
    try {
      java.util.List<java.util.concurrent.Future<JsonNode>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < 10; i++) {
        futures.add(pool.submit(() -> service.getCatalogForExecutor(ref, "lightweight", false)));
      }
      for (java.util.concurrent.Future<JsonNode> future : futures) {
        assertThat(future.get().path("catalog_state").asText()).isEqualTo("READY");
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void failedWinnerRetriesAndAllCallersReceiveReplacementResult() throws Exception {
    String ref = "dev-micro6:RETRY";
    start(ref, "executor-retry");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    executor.lightweightDelayMs = 400;

    // Seed a failed in-flight future for the exact identity key so every caller observes an
    // exceptional winner, retries acquisition, and coalesces onto a single replacement fetch.
    String key = seedFailedInflight(ref);

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(10);
    try {
      java.util.List<java.util.concurrent.Future<JsonNode>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < 10; i++) {
        futures.add(pool.submit(() -> service.getCatalogForExecutor(ref, "lightweight", false)));
      }
      for (java.util.concurrent.Future<JsonNode> future : futures) {
        assertThat(future.get().path("catalog_state").asText()).isEqualTo("READY");
      }
    } finally {
      pool.shutdownNow();
    }

    // One failed winner + one replacement request.
    assertThat(executor.lightweightCalls)
        .as("failed winner followed by retry must produce only one replacement request")
        .isEqualTo(1);
    assertThat(inflightSize()).isZero();
  }

  @Test
  void inflightMapIsClearedAfterSuccess() throws Exception {
    String ref = "dev-micro6:CLEAR";
    start(ref, "executor-clear");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(inflightSize()).isZero();
  }

  private String seedFailedInflight(String ref) throws Exception {
    java.lang.reflect.Field field =
        Micro6ExecutorCatalogService.class.getDeclaredField("inflightFetches");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<JsonNode>>
        map =
            (java.util.concurrent.ConcurrentHashMap<
                    String, java.util.concurrent.CompletableFuture<JsonNode>>)
                field.get(service);
    String machineUuid = "machine-" + ref.hashCode();
    String sessionId = "session-" + ref.hashCode();
    String key = machineUuid + "|" + "executor-retry" + "|" + sessionId + "|lightweight";
    java.util.concurrent.CompletableFuture<JsonNode> failed =
        new java.util.concurrent.CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("winner failed"));
    map.put(key, failed);
    return key;
  }

  private int inflightSize() throws Exception {
    java.lang.reflect.Field field =
        Micro6ExecutorCatalogService.class.getDeclaredField("inflightFetches");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<JsonNode>>
        map =
            (java.util.concurrent.ConcurrentHashMap<
                    String, java.util.concurrent.CompletableFuture<JsonNode>>)
                field.get(service);
    return map.size();
  }

  @Test
  void cachedIntegrityFailureRetainsStructuredReasonAndAiPlayerRejectsIt() throws Exception {
    String ref = "dev-micro6:FENCE";
    start(ref, "executor-fence");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    assertThat(
            service.getCatalogForExecutor(ref, "lightweight", false).path("catalog_state").asText())
        .isEqualTo("READY");
    ObjectNode changed = TestMicro6Catalog.lightweight();
    ((ObjectNode) changed.path("tasks").get(0)).put("display_name", "Changed without rehashing");
    executor.lightweightBody = TestMicro6Catalog.json(changed);
    JsonNode stale = service.getCatalogForExecutor(ref, "lightweight", true);
    assertThat(stale.path("catalog_state").asText()).isEqualTo("STALE");
    assertThat(stale.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(stale.path("detailed_reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    CatalogStore storage = mock(CatalogStore.class);
    AiPlayerCatalogService player = new AiPlayerCatalogService(service, storage, mapper);
    assertThat(player.catalog(ref, true).path("state").asText()).isEqualTo("UNAVAILABLE");
    verifyNoInteractions(storage);
  }
}

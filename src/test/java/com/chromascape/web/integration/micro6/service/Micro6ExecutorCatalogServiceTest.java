package com.chromascape.web.integration.micro6.service;

import static org.assertj.core.api.Assertions.assertThat;

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
  void generating202LeavesCacheUnchanged() throws Exception {
    String ref = "dev-micro6:CCCC";
    start(ref, "executor-3");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());

    JsonNode ready = service.getCatalogForExecutor(ref, "full", false);
    assertThat(ready.path("catalog_state").asText()).isEqualTo("READY");

    executor.fullStatus = 202;
    executor.fullBody =
        "{\"status\":\"GENERATING\",\"catalog_mode\":\"FULL\",\"catalog_state\":\"GENERATING\"}";
    JsonNode generating = service.getCatalogForExecutor(ref, "full", true);
    assertThat(generating.path("catalog_state").asText()).isEqualTo("GENERATING");
    assertThat(generating.path("catalog").isMissingNode()).isTrue();

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
}

package com.chromascape.web.integration.micro6.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.integration.micro6.config.Micro6Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for the SC-584A post-fetch identity reconciliation contract: core ownership
 * (machine/executor/session) is re-confirmed against the post-fetch runtime, catalog identity drift
 * is retried once and never recursed beyond the fixed limit, and the payload/header lifecycle is
 * bound before anything is cached.
 */
class Micro6CatalogReconciliationTest {
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
  void executorChangeDuringFetchIsRuntimeChanged() throws Exception {
    String ref = "dev-micro6:EXEC-CHANGE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.beforeCatalogHook.put(
        ref, () -> registry.executorInstanceByRef.put(ref, "executor-B"));

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("RUNTIME_CHANGED");
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void sessionChangeDuringFetchIsRuntimeChanged() throws Exception {
    String ref = "dev-micro6:SESSION-CHANGE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.beforeCatalogHook.put(ref, () -> registry.sessionIdByRef.put(ref, "session-NEW"));

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("RUNTIME_CHANGED");
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void machineChangeDuringFetchIsRuntimeChanged() throws Exception {
    String ref = "dev-micro6:MACHINE-CHANGE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.beforeCatalogHook.put(ref, () -> registry.machineUuidByRef.put(ref, "machine-NEW"));

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("RUNTIME_CHANGED");
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void generatingRuntimeAcceptsCompletedCatalogOnlyWhenRuntimeAfterConfirms() throws Exception {
    String ref = "dev-micro6:GEN-ACCEPT";
    start(ref, "executor-A");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
    registry.catalogStateByRef.put(ref, "GENERATING");
    registry.schemaBundleStateByRef.put(ref, "GENERATING");
    // The response carries a completed catalog lifecycle...
    registry.catalogStateHeaderByRef.put(ref, "READY");
    registry.schemaBundleStateHeaderByRef.put(ref, "AVAILABLE");
    registry.schemaBundleHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    // ...and only the post-fetch resolve confirms AVAILABLE.
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.catalogStateByRef.put(ref, "READY");
            registry.schemaBundleStateByRef.put(ref, "AVAILABLE");
            registry.schemaBundleHashByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
          }
        });

    JsonNode accepted = service.getCatalogForExecutor(ref, "full", false);
    assertThat(accepted.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(accepted.path("schema_bundle_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
  }

  @Test
  void generatingRuntimeRejectsCompletedCatalogWithoutRuntimeAfterConfirmation() throws Exception {
    String ref = "dev-micro6:GEN-REJECT";
    start(ref, "executor-A");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
    registry.catalogStateByRef.put(ref, "GENERATING");
    registry.schemaBundleStateByRef.put(ref, "GENERATING");
    registry.catalogStateHeaderByRef.put(ref, "READY");
    registry.schemaBundleStateHeaderByRef.put(ref, "AVAILABLE");
    registry.schemaBundleHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    // runtimeAfter stays GENERATING, so the completed 200 response is never accepted.
    JsonNode rejected = service.getCatalogForExecutor(ref, "full", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(rejected.path("reasons").toString()).contains("catalog lifecycle conflict");
  }

  @Test
  void catalogHashChangeDuringFetchRetriesOnceThenSucceeds() throws Exception {
    String ref = "dev-micro6:HASH-RETRY";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    ObjectNode regenerated = (ObjectNode) TestMicro6Catalog.lightweight().deepCopy();
    ((ObjectNode) regenerated.path("tasks").get(0)).put("description", "regenerated catalog");
    TestMicro6Catalog.rehash(regenerated);
    String regeneratedHash = regenerated.get("catalog_hash").asText();
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    AtomicInteger hooks = new AtomicInteger();
    registry.beforeCatalogHook.put(
        ref,
        () -> {
          if (hooks.incrementAndGet() == 1) {
            // The runtime advances to a new catalog while the first response is still in flight.
            registry.catalogHashByRef.put(ref, regeneratedHash);
          } else {
            // The retry's response catches up with the confirmed runtime.
            registry.catalogHashHeaderByRef.put(ref, regeneratedHash);
            executor.lightweightBody = TestMicro6Catalog.json(regenerated);
          }
        });

    JsonNode accepted = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(accepted.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(accepted.path("catalog_hash").asText()).isEqualTo(regeneratedHash);
    assertThat(executor.lightweightCalls)
        .as("one bounded retry must re-acquire the catalog")
        .isEqualTo(2);
  }

  @Test
  void catalogHashChangeThatNeverSettlesIsRuntimeChanged() throws Exception {
    String ref = "dev-micro6:HASH-SETTLE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    ObjectNode regenerated = (ObjectNode) TestMicro6Catalog.lightweight().deepCopy();
    ((ObjectNode) regenerated.path("tasks").get(0)).put("description", "regenerated catalog");
    TestMicro6Catalog.rehash(regenerated);
    String regeneratedHash = regenerated.get("catalog_hash").asText();
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    registry.beforeCatalogHook.put(
        ref,
        () -> {
          // The runtime advances on the first attempt but the response never catches up.
          registry.catalogHashByRef.put(ref, regeneratedHash);
        });

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("RUNTIME_CHANGED");
    assertThat(executor.lightweightCalls).isEqualTo(2);
  }

  @Test
  void schemaBundleChangeDuringFetchNeverServesOldFullAsCurrent() throws Exception {
    String ref = "dev-micro6:BUNDLE-DRIFT";
    start(ref, "executor-A");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
    registry.schemaBundleHashByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.schemaBundleStateByRef.put(ref, "AVAILABLE");

    JsonNode first = service.getCatalogForExecutor(ref, "full", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    // The runtime now attests a different bundle while AVAILABLE and the refresh fails: the old
    // full cache no longer matches the identity and must not be served as current.
    registry.schemaBundleHashByRef.put(ref, "sha256:" + "c".repeat(64));
    executor.dropConnections = true;
    JsonNode second = service.getCatalogForExecutor(ref, "full", false);
    assertThat(second.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(second.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(second.path("catalog").isObject()).isFalse();
  }

  @Test
  void headerAndPayloadCatalogStateDisagreementIsIdentityConflict() throws Exception {
    String ref = "dev-micro6:STATE-CONFLICT";
    start(ref, "executor-A");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
    registry.catalogStateHeaderByRef.put(ref, "DEGRADED");
    registry.schemaBundleStateHeaderByRef.put(ref, "AVAILABLE");
    registry.schemaBundleHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    JsonNode rejected = service.getCatalogForExecutor(ref, "full", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_CONFLICT");
    assertThat(rejected.path("reasons").toString()).contains("catalog state conflict");
  }

  @Test
  void headerHashDisagreeingWithRuntimeHashIsRejected() throws Exception {
    String ref = "dev-micro6:HDR-RUNTIME-HASH";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    // The response header attests a different catalog hash than the stable runtime.
    registry.catalogHashHeaderByRef.put(ref, "sha256:" + "a".repeat(64));

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(rejected.path("reasons").toString()).contains("catalog hash mismatch");
  }

  @Test
  void onlineRuntimeWithoutCatalogHashIsContractInvalid() throws Exception {
    String ref = "dev-micro6:NO-RUNTIME-HASH";
    start(ref, "executor-A");
    registry.catalogHashByRef.put(ref, "");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(executor.lightweightCalls).isZero();
  }

  @Test
  void concurrentCallersCoalesceAcrossBoundedRetry() throws Exception {
    String ref = "dev-micro6:COALESCE-RETRY";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    executor.lightweightDelayMs = 200;
    ObjectNode regenerated = (ObjectNode) TestMicro6Catalog.lightweight().deepCopy();
    ((ObjectNode) regenerated.path("tasks").get(0)).put("description", "regenerated catalog");
    TestMicro6Catalog.rehash(regenerated);
    String regeneratedHash = regenerated.get("catalog_hash").asText();
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    AtomicInteger hooks = new AtomicInteger();
    registry.beforeCatalogHook.put(
        ref,
        () -> {
          if (hooks.incrementAndGet() == 1) {
            registry.catalogHashByRef.put(ref, regeneratedHash);
          } else {
            registry.catalogHashHeaderByRef.put(ref, regeneratedHash);
            executor.lightweightBody = TestMicro6Catalog.json(regenerated);
          }
        });

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<JsonNode>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        futures.add(pool.submit(() -> service.getCatalogForExecutor(ref, "lightweight", false)));
      }
      for (Future<JsonNode> future : futures) {
        assertThat(future.get().path("catalog_state").asText()).isEqualTo("READY");
      }
    } finally {
      pool.shutdownNow();
    }
    // The single winner performs the initial fetch plus one bounded retry; no waiter re-fetches.
    assertThat(executor.lightweightCalls).isEqualTo(2);
    assertThat(inflightSize()).isZero();

    // The confirmed post-fetch identity was cached once: a follow-up is served from cache.
    JsonNode cached = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(cached.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(cached.path("catalog_hash").asText()).isEqualTo(regeneratedHash);
    assertThat(executor.lightweightCalls).isEqualTo(2);
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
}

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for SC-584A identity binding and cache isolation: catalog payloads are rejected when
 * their identity conflicts with the resolved runtime, the cache is keyed by runtime identity (never
 * {@code endpoint_ref} alone), and concurrent requests coalesce onto a single fetch.
 */
class Micro6CatalogIdentityHardeningTest {
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
  void payloadExecutorInstanceMismatchIsRejectedAndNotCached() throws Exception {
    String ref = "dev-micro6:CONFLICT-INSTANCE";
    start(ref, "executor-A");
    registry.rewriteCatalogIdentity = false;
    ObjectNode payload = TestMicro6Catalog.lightweight();
    payload.put("executor_instance_id", "executor-WRONG");
    executor.lightweightBody = TestMicro6Catalog.json(payload);

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_CONFLICT");
    assertThat(rejected.path("reasons").toString()).contains("executor_instance_id conflict");

    // Nothing was cached: a subsequent refresh must re-fetch.
    service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(executor.lightweightCalls).isEqualTo(2);
  }

  @Test
  void catalogVersionConflictIsRejected() throws Exception {
    String ref = "dev-micro6:CONFLICT-VERSION";
    start(ref, "executor-B");
    registry.catalogVersionByRef.put(ref, "3");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_CONFLICT");
    assertThat(rejected.path("reasons").toString()).contains("catalog version conflict");
  }

  @Test
  void catalogHashConflictIsRejected() throws Exception {
    String ref = "dev-micro6:CONFLICT-HASH";
    start(ref, "executor-C");
    registry.catalogHashByRef.put(ref, "sha256:" + "a".repeat(64));
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_CONFLICT");
    assertThat(rejected.path("reasons").toString()).contains("catalog hash conflict");
  }

  @Test
  void runtimeChangedMidFetchDiscardsCatalog() throws Exception {
    String ref = "dev-micro6:CHANGED";
    start(ref, "executor-A");
    registry.rewriteCatalogIdentity = false;
    ObjectNode payload = TestMicro6Catalog.lightweight();
    payload.put("executor_instance_id", "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(payload);
    registry.beforeCatalogHook.put(
        ref, () -> registry.executorInstanceByRef.put(ref, "executor-B"));

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("RUNTIME_CHANGED");

    // Nothing was cached under the pre-fetch identity.
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void executorInstanceChangeInvalidatesCache() throws Exception {
    String ref = "dev-micro6:SWAP";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(1);

    // The same endpoint ref now resolves to a different executor instance: the cached copy under the
    // old identity must not be served, so the refresh re-fetches.
    registry.executorInstanceByRef.put(ref, "executor-B");
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(executor.lightweightCalls).isEqualTo(2);
    assertThat(second.path("catalog_state").asText()).isEqualTo("READY");
  }

  @Test
  void sessionChangeInvalidatesCache() throws Exception {
    String ref = "dev-micro6:SESSION";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(1);

    registry.sessionIdByRef.put(ref, "session-NEW");
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(executor.lightweightCalls).isEqualTo(2);
    assertThat(second.path("catalog_state").asText()).isEqualTo("READY");
  }

  @Test
  void catalogHashMetadataDriftMakesCacheStaleAndConflicts() throws Exception {
    String ref = "dev-micro6:HASHDRIFT";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(1);

    // Registry now attests a different catalog hash: the cached copy is stale (miss -> refresh) and
    // the new payload conflicts with the runtime identity, so it is never cached.
    registry.catalogHashByRef.put(ref, "sha256:" + "a".repeat(64));
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(executor.lightweightCalls).isEqualTo(2);
    assertThat(second.path("reason_code").asText()).isEqualTo("IDENTITY_CONFLICT");
  }

  @Test
  void catalogVersionDriftMakesCacheStaleAndConflicts() throws Exception {
    String ref = "dev-micro6:VERSIONDRIFT";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(1);

    registry.catalogVersionByRef.put(ref, "3");
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(executor.lightweightCalls).isEqualTo(2);
    assertThat(second.path("reason_code").asText()).isEqualTo("IDENTITY_CONFLICT");
  }

  @Test
  void nonSelectableRuntimeServesStaleOnlyForMatchingIdentity() throws Exception {
    String ref = "dev-micro6:OFFLINE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    registry.setState(ref, "STALE");
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(second.path("catalog_state").asText()).isEqualTo("STALE");
    assertThat(second.path("catalog").isObject()).isTrue();
    assertThat(second.path("last_known_good").asBoolean()).isTrue();
  }

  @Test
  void concurrentRequestsCoalesceOnIdentityKey() throws Exception {
    String ref = "dev-micro6:COALESCE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    executor.lightweightDelayMs = 400;

    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<JsonNode>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        futures.add(
            pool.submit(() -> service.getCatalogForExecutor(ref, "lightweight", false)));
      }
      for (Future<JsonNode> future : futures) {
        assertThat(future.get().path("catalog_state").asText()).isEqualTo("READY");
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void missingIdentityHeadersOn200ArePermanentlyUnverified() throws Exception {
    String ref = "dev-micro6:NOHDRS";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.omitIdentityHeaders = true;

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
  }

  @Test
  void responseExecutorHeaderMismatchIsRejected() throws Exception {
    String ref = "dev-micro6:HDRMISMATCH";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.executorHeaderByRef.put(ref, "executor-WRONG");

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(rejected.path("reasons").toString()).contains("executor_instance_id mismatch");
  }

  @Test
  void generatingRuntimeExposesPriorFullCatalogOnlyAsStale() throws Exception {
    String ref = "dev-micro6:GENSTALE";
    start(ref, "executor-A");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
    registry.schemaBundleHashByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.schemaBundleStateByRef.put(ref, "AVAILABLE");

    JsonNode first = service.getCatalogForExecutor(ref, "full", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    // The runtime transitions to GENERATING (re-generating its schema bundle). The prior
    // same-identity full catalog may only be exposed as STALE, never as fresh READY.
    registry.schemaBundleStateByRef.put(ref, "GENERATING");
    JsonNode second = service.getCatalogForExecutor(ref, "full", false);
    assertThat(second.path("catalog_state").asText()).isEqualTo("STALE");
    assertThat(second.path("catalog").isObject()).isTrue();
    assertThat(second.path("catalog").path("schema_bundle_hash").asText())
        .isEqualTo(TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
  }
}

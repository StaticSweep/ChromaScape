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
    // The runtime and the response header both attest a DEGRADED catalog...
    registry.catalogStateByRef.put(ref, "DEGRADED");
    registry.schemaBundleStateByRef.put(ref, "AVAILABLE");
    registry.schemaBundleHashByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.catalogStateHeaderByRef.put(ref, "DEGRADED");
    registry.schemaBundleStateHeaderByRef.put(ref, "AVAILABLE");
    registry.schemaBundleHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    // ...but the payload still attests READY, so the conflict is payload/header, not
    // header/runtime.
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
  void missingLifecycleHeadersStillCausesPostFetchResolve() throws Exception {
    String ref = "dev-micro6:MISSING-HEADERS";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.omitIdentityHeaders = true;
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(ref, resolves::incrementAndGet);

    // A 200 lifecycle response with no identity headers must still trigger the post-fetch resolve;
    // only then is the missing header reported as IDENTITY_UNVERIFIED.
    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(resolves.get()).isEqualTo(2);
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void generatingRuntimeWithValid202HeadersReturnsGenerating() throws Exception {
    String ref = "dev-micro6:GEN-202";
    start(ref, "executor-A");
    executor.lightweightStatus = 202;
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.catalogStateByRef.put(ref, "GENERATING");
    registry.schemaBundleStateByRef.put(ref, "GENERATING");

    JsonNode accepted = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(accepted.path("catalog_state").asText()).isEqualTo("GENERATING");
    assertThat(accepted.path("catalog").isObject()).isFalse();
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void stableReadyRuntimeReceivesStale202Then200() throws Exception {
    String ref = "dev-micro6:202-THEN-200";
    start(ref, "executor-A");
    executor.lightweightStatus = 202;
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    AtomicInteger catalogs = new AtomicInteger();
    registry.beforeCatalogHook.put(
        ref,
        () -> {
          if (catalogs.incrementAndGet() == 2) {
            executor.lightweightStatus = 200;
          }
        });

    // A stable READY runtime receiving a stale 202 re-acquires exactly once; the second fetch is a
    // real 200 and settles as READY.
    JsonNode accepted = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(accepted.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(2);
  }

  @Test
  void stableReadyRuntimeReceivingTwoStale202IsRuntimeChanged() throws Exception {
    String ref = "dev-micro6:202-TWICE";
    start(ref, "executor-A");
    executor.lightweightStatus = 202;
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    // A stable READY runtime that returns a stale 202 twice has crossed an identity/lifecycle
    // boundary without settling: RUNTIME_CHANGED after exactly two fetches.
    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("RUNTIME_CHANGED");
    assertThat(executor.lightweightCalls).isEqualTo(2);
  }

  @Test
  void runtimeBecomingReadyDuring202RefetchesOnceWithoutHashChange() throws Exception {
    String ref = "dev-micro6:202-BECOMES-READY";
    start(ref, "executor-A");
    executor.lightweightStatus = 202;
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.catalogStateByRef.put(ref, "GENERATING");
    registry.schemaBundleStateByRef.put(ref, "GENERATING");
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.catalogStateByRef.put(ref, "READY");
            registry.schemaBundleStateByRef.put(ref, "NOT_AVAILABLE");
          }
        });
    AtomicInteger catalogs = new AtomicInteger();
    registry.beforeCatalogHook.put(
        ref,
        () -> {
          if (catalogs.incrementAndGet() == 2) {
            executor.lightweightStatus = 200;
          }
        });

    // The runtime completes generation during the first 202; even though the catalog version and
    // hash never changed, the lifecycle crossing forces exactly one re-fetch that settles as READY.
    JsonNode accepted = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(accepted.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(2);
  }

  @Test
  void runtimeLosingCatalogHashWithMalformed200IsContractInvalidWithoutStale() throws Exception {
    String ref = "dev-micro6:HASH-LOSS-200";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    // The runtime loses its catalog hash between the request and the post-fetch resolve, while the
    // 200 response body is malformed. The post-fetch contract violation is reported before the
    // body or any stale fallback is considered.
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.catalogHashByRef.put(ref, "");
          }
        });
    executor.lightweightBody = "not-json-at-all";
    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", true);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(rejected.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(rejected.path("catalog").isObject()).isFalse();
  }

  @Test
  void runtimeLosingCatalogHashWith202IsContractInvalid() throws Exception {
    String ref = "dev-micro6:HASH-LOSS-202";
    start(ref, "executor-A");
    executor.lightweightStatus = 202;
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.catalogHashByRef.put(ref, "");
          }
        });

    // The runtime loses its catalog hash after the request; the 202 is rejected as CONTRACT_INVALID
    // with no re-acquisition and no cache fallback.
    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(rejected.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void runtimeLosingCatalogHashWith503IsContractInvalidWithoutStale() throws Exception {
    String ref = "dev-micro6:HASH-LOSS-503";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    // The runtime loses its catalog hash after the request; the 503 is CONTRACT_INVALID, never the
    // pre-existing cache served as STALE.
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.catalogHashByRef.put(ref, "");
          }
        });
    executor.lightweightStatus = 503;
    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", true);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(rejected.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(rejected.path("catalog").isObject()).isFalse();
  }

  @Test
  void headerCatalogStateDiffersFromStableRuntimeIsIdentityUnverified() throws Exception {
    String ref = "dev-micro6:STABLE-STATE-MISMATCH";
    start(ref, "executor-A");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
    registry.catalogStateByRef.put(ref, "READY");
    registry.schemaBundleStateByRef.put(ref, "AVAILABLE");
    registry.schemaBundleHashByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    // The stable runtime is READY but the response header attests DEGRADED.
    registry.catalogStateHeaderByRef.put(ref, "DEGRADED");
    registry.schemaBundleStateHeaderByRef.put(ref, "AVAILABLE");
    registry.schemaBundleHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    JsonNode rejected = service.getCatalogForExecutor(ref, "full", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(rejected.path("reasons").toString()).contains("catalog state mismatch");
    assertThat(executor.fullCalls).isEqualTo(1);
  }

  @Test
  void headerSchemaBundleStateDiffersFromStableRuntimeIsIdentityUnverified() throws Exception {
    String ref = "dev-micro6:STABLE-BUNDLE-STATE-MISMATCH";
    start(ref, "executor-A");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
    registry.catalogStateByRef.put(ref, "READY");
    registry.schemaBundleStateByRef.put(ref, "AVAILABLE");
    registry.schemaBundleHashByRef.put(ref, TestMicro6Catalog.GOLDEN_SCHEMA_BUNDLE_HASH);
    registry.catalogStateHeaderByRef.put(ref, "READY");
    // The stable runtime attests an AVAILABLE bundle but the header says NOT_AVAILABLE.
    registry.schemaBundleStateHeaderByRef.put(ref, "NOT_AVAILABLE");
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    JsonNode rejected = service.getCatalogForExecutor(ref, "full", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(rejected.path("reasons").toString()).contains("schema bundle state mismatch");
    assertThat(executor.fullCalls).isEqualTo(1);
  }

  @Test
  void payloadBundleWithRuntimeNotAvailableIsIdentityConflict() throws Exception {
    String ref = "dev-micro6:BUNDLE-NOT-AVAILABLE";
    start(ref, "executor-A");
    executor.fullBody = TestMicro6Catalog.json(TestMicro6Catalog.full());
    registry.catalogStateByRef.put(ref, "READY");
    registry.schemaBundleStateByRef.put(ref, "NOT_AVAILABLE");
    registry.schemaBundleHashByRef.put(ref, "");
    registry.catalogStateHeaderByRef.put(ref, "READY");
    registry.schemaBundleStateHeaderByRef.put(ref, "NOT_AVAILABLE");
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);
    // The payload still carries a valid bundle the runtime and header no longer attest.

    JsonNode rejected = service.getCatalogForExecutor(ref, "full", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_CONFLICT");
    assertThat(rejected.path("reasons").toString()).contains("schema bundle state conflict");
  }

  @Test
  void lightweightPayloadWithMalformedBundleHashIsIdentityConflict() throws Exception {
    String ref = "dev-micro6:BUNDLE-MALFORMED";
    start(ref, "executor-A");
    ObjectNode payload = TestMicro6Catalog.lightweight();
    payload.put("schema_bundle_hash", "sha256:not-a-real-hash");
    executor.lightweightBody = TestMicro6Catalog.json(payload);
    // Keep the response header matrix valid so the malformed payload field is what is tested.
    registry.catalogStateHeaderByRef.put(ref, "READY");
    registry.schemaBundleStateHeaderByRef.put(ref, "NOT_AVAILABLE");
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_CONFLICT");
    assertThat(rejected.path("reasons").toString()).contains("malformed bundle hash");
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

  @Test
  void readyToGeneratingWithStale202HeadersRetriesAndReturnsGenerating() throws Exception {
    String ref = "dev-micro6:202-REVERSE-RACE";
    start(ref, "executor-A");
    executor.lightweightStatus = 202;
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    // The first 202 crosses READY -> GENERATING during the post-fetch resolve while its response
    // still carries the stale READY/NOT_AVAILABLE headers. The second fetch exposes the matching
    // GENERATING headers.
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.catalogStateByRef.put(ref, "GENERATING");
            registry.schemaBundleStateByRef.put(ref, "GENERATING");
          }
        });

    JsonNode result = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(result.path("catalog_state").asText()).isEqualTo("GENERATING");
    assertThat(result.path("catalog").isObject()).isFalse();
    assertThat(executor.lightweightCalls)
        .as("the READY -> GENERATING race is retried exactly once")
        .isEqualTo(2);
  }

  @Test
  void readyToGeneratingWithPersistentStale202HeadersIsIdentityUnverified() throws Exception {
    String ref = "dev-micro6:202-REVERSE-STALE";
    start(ref, "executor-A");
    executor.lightweightStatus = 202;
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    // Pin the response headers to the stale READY/NOT_AVAILABLE pair so the retry never catches up
    // to the runtime's GENERATING attestation.
    registry.catalogStateHeaderByRef.put(ref, "READY");
    registry.schemaBundleStateHeaderByRef.put(ref, "NOT_AVAILABLE");
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.catalogStateByRef.put(ref, "GENERATING");
            registry.schemaBundleStateByRef.put(ref, "GENERATING");
          }
        });

    JsonNode result = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(result.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(result.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(executor.lightweightCalls)
        .as("the bounded retry must stop after the second stale 202")
        .isEqualTo(2);
  }

  @Test
  void missingPostFetchCatalogStateIsIdentityUnverified() throws Exception {
    String ref = "dev-micro6:MISSING-POST-CATALOG-STATE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    // The registry omits catalog_state only on the post-fetch resolve; the proxy headers remain
    // valid so the missing runtime attestation is what is exercised.
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.omitRuntimeCatalogStateByRef.add(ref);
          }
        });

    JsonNode result = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(result.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(result.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(result.path("reasons").toString()).contains("missing catalog_state");
    assertThat(result.path("catalog").isObject()).isFalse();
    assertThat(result.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void missingPostFetchSchemaBundleStateIsIdentityUnverified() throws Exception {
    String ref = "dev-micro6:MISSING-POST-BUNDLE-STATE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.omitRuntimeSchemaBundleStateByRef.add(ref);
          }
        });

    JsonNode result = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(result.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(result.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(result.path("reasons").toString()).contains("missing schema_bundle_state");
    assertThat(result.path("catalog").isObject()).isFalse();
    assertThat(result.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void missingInitialCatalogStateDoesNotServeLightweightCache() throws Exception {
    String ref = "dev-micro6:MISSING-INIT-CATALOG-STATE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    registry.omitRuntimeCatalogStateByRef.add(ref);
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(second.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(second.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(second.path("catalog").isObject()).isFalse();
    assertThat(executor.lightweightCalls)
        .as("the cache fast path must be rejected before any executor request")
        .isEqualTo(1);
  }

  @Test
  void missingInitialSchemaBundleStateDoesNotServeLightweightCache() throws Exception {
    String ref = "dev-micro6:MISSING-INIT-BUNDLE-STATE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    registry.omitRuntimeSchemaBundleStateByRef.add(ref);
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(second.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(second.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(second.path("catalog").isObject()).isFalse();
    assertThat(executor.lightweightCalls)
        .as("the cache fast path must be rejected before any executor request")
        .isEqualTo(1);
  }

  @Test
  void seededLightweightCacheThenUnknownCatalogStateIsContractInvalid() throws Exception {
    String ref = "dev-micro6:INIT-UNKNOWN-STATE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    registry.catalogStateByRef.put(ref, "UNKNOWN_STATE");
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(second.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(second.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(second.path("catalog").isObject()).isFalse();
    assertThat(second.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(executor.lightweightCalls)
        .as("an invalid initial runtime must not satisfy the cache fast path or hit the executor")
        .isEqualTo(1);
  }

  @Test
  void seededLightweightCacheThenImpossibleStatePairIsContractInvalid() throws Exception {
    String ref = "dev-micro6:INIT-IMPOSSIBLE-PAIR";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    registry.catalogStateByRef.put(ref, "READY");
    registry.schemaBundleStateByRef.put(ref, "GENERATING");
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(second.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(second.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(second.path("reasons").toString()).contains("catalog/schema-bundle matrix");
    assertThat(second.path("catalog").isObject()).isFalse();
    assertThat(second.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void seededLightweightCacheThenInvalidAvailableBundleIsContractInvalid() throws Exception {
    String ref = "dev-micro6:INIT-INVALID-AVAILABLE-BUNDLE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    registry.catalogStateByRef.put(ref, "READY");
    registry.schemaBundleStateByRef.put(ref, "AVAILABLE");
    registry.schemaBundleHashByRef.put(ref, "sha256:invalid-hash-value");
    JsonNode second = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(second.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(second.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(second.path("reasons").toString()).contains("catalog/schema-bundle matrix");
    assertThat(second.path("catalog").isObject()).isFalse();
    assertThat(second.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void postFetchInvalidLifecycleOn200IsContractInvalidWithoutStale() throws Exception {
    String ref = "dev-micro6:POST-200-INVALID-LIFECYCLE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    // The runtime attests an unknown catalog state only on the post-fetch resolve, so the 200 is
    // rejected as a contract violation before any header validation or stale fallback.
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.catalogStateByRef.put(ref, "BOGUS_STATE");
          }
        });
    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", true);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(rejected.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(rejected.path("catalog").isObject()).isFalse();
  }

  @Test
  void postFetchInvalidLifecycleOn503IsContractInvalidWithoutStale() throws Exception {
    String ref = "dev-micro6:POST-503-INVALID-LIFECYCLE";
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(
        ref,
        () -> {
          if (resolves.incrementAndGet() == 2) {
            registry.catalogStateByRef.put(ref, "BOGUS_STATE");
          }
        });
    executor.lightweightStatus = 503;
    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", true);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("CONTRACT_INVALID");
    assertThat(rejected.path("last_known_good").asBoolean(false)).isFalse();
    assertThat(rejected.path("catalog").isObject()).isFalse();
  }

  @Test
  void lightweightPayloadWithObjectBundleHashIsIdentityConflict() throws Exception {
    String ref = "dev-micro6:BUNDLE-OBJECT";
    start(ref, "executor-A");
    ObjectNode payload = TestMicro6Catalog.lightweight();
    payload.set("schema_bundle_hash", mapper.createObjectNode());
    executor.lightweightBody = TestMicro6Catalog.json(payload);
    registry.catalogStateHeaderByRef.put(ref, "READY");
    registry.schemaBundleStateHeaderByRef.put(ref, "NOT_AVAILABLE");
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_CONFLICT");
    assertThat(rejected.path("reasons").toString()).contains("malformed bundle hash");
    assertThat(executor.lightweightCalls).isEqualTo(1);

    // No cache write: a follow-up valid request must reach the executor again.
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    JsonNode retry = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(retry.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(2);
  }

  @Test
  void lightweightPayloadWithArrayBundleHashIsIdentityConflict() throws Exception {
    String ref = "dev-micro6:BUNDLE-ARRAY";
    start(ref, "executor-A");
    ObjectNode payload = TestMicro6Catalog.lightweight();
    payload.set("schema_bundle_hash", mapper.createArrayNode());
    executor.lightweightBody = TestMicro6Catalog.json(payload);
    registry.catalogStateHeaderByRef.put(ref, "READY");
    registry.schemaBundleStateHeaderByRef.put(ref, "NOT_AVAILABLE");
    registry.catalogHashHeaderByRef.put(ref, TestMicro6Catalog.GOLDEN_CATALOG_HASH);

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_CONFLICT");
    assertThat(rejected.path("reasons").toString()).contains("malformed bundle hash");
    assertThat(executor.lightweightCalls).isEqualTo(1);

    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    JsonNode retry = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(retry.path("catalog_state").asText()).isEqualTo("READY");
    assertThat(executor.lightweightCalls).isEqualTo(2);
  }

  @Test
  void missingIdentityHeadersOn202IsIdentityUnverified() throws Exception {
    String ref = "dev-micro6:MISSING-HEADERS-202";
    start(ref, "executor-A");
    executor.lightweightStatus = 202;
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.omitIdentityHeaders = true;
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(ref, resolves::incrementAndGet);

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(resolves.get()).isEqualTo(2);
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void missingIdentityHeadersOn503IsIdentityUnverified() throws Exception {
    String ref = "dev-micro6:MISSING-HEADERS-503";
    start(ref, "executor-A");
    executor.lightweightStatus = 503;
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());
    registry.omitIdentityHeaders = true;
    AtomicInteger resolves = new AtomicInteger();
    registry.beforeResolveHook.put(ref, resolves::incrementAndGet);

    JsonNode rejected = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(rejected.path("catalog_state").asText()).isEqualTo("UNAVAILABLE");
    assertThat(rejected.path("reason_code").asText()).isEqualTo("IDENTITY_UNVERIFIED");
    assertThat(resolves.get()).isEqualTo(2);
    assertThat(executor.lightweightCalls).isEqualTo(1);
  }

  @Test
  void generatingRuntimeServesLightweightCacheOnlyAsStale() throws Exception {
    assertLightweightCacheIsStaleWhenRuntimeIsNotReady(
        "dev-micro6:CACHE-GENERATING", "GENERATING", "GENERATING");
  }

  @Test
  void degradedRuntimeServesLightweightCacheOnlyAsStale() throws Exception {
    assertLightweightCacheIsStaleWhenRuntimeIsNotReady(
        "dev-micro6:CACHE-DEGRADED", "DEGRADED", "NOT_AVAILABLE");
  }

  @Test
  void unavailableRuntimeServesLightweightCacheOnlyAsStale() throws Exception {
    assertLightweightCacheIsStaleWhenRuntimeIsNotReady(
        "dev-micro6:CACHE-UNAVAILABLE", "UNAVAILABLE", "NOT_AVAILABLE");
  }

  private void assertLightweightCacheIsStaleWhenRuntimeIsNotReady(
      String ref, String catalogState, String schemaBundleState) throws Exception {
    start(ref, "executor-A");
    executor.lightweightBody = TestMicro6Catalog.json(TestMicro6Catalog.lightweight());

    JsonNode first = service.getCatalogForExecutor(ref, "lightweight", false);
    assertThat(first.path("catalog_state").asText()).isEqualTo("READY");

    registry.catalogStateByRef.put(ref, catalogState);
    registry.schemaBundleStateByRef.put(ref, schemaBundleState);
    registry.schemaBundleHashByRef.remove(ref);
    JsonNode cached = service.getCatalogForExecutor(ref, "lightweight", false);

    assertThat(cached.path("catalog_state").asText()).isEqualTo("STALE");
    assertThat(cached.path("catalog").isObject()).isTrue();
    assertThat(cached.path("last_known_good").asBoolean()).isTrue();
    assertThat(executor.lightweightCalls)
        .as("a valid non-ready runtime may expose last-known-good but must not fetch or look fresh")
        .isEqualTo(1);
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

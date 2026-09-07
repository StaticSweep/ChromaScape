package com.chromascape.web.gameplay.orchestration.v2;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.Failure;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2Bundle;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2ReserveResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StartResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2State;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StatusResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2Transport;
import com.chromascape.web.gameplay.provider.v2.ClaudeGameplayV2ProviderAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SC-1449 ChromaScape live-gate tests: the closed sequence admits exactly one valid full sequence,
 * denies every mutation before transport, transmits exactly the validated canonical bytes with the
 * cross-repository wire contract, verifies every receipt fully, and reconciles transport boundaries
 * without redispatch across a shared durable store.
 */
class GameplayV2LiveGateServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-03T12:04:00Z");
  private ObjectMapper objectMapper;
  private GameplayV2ContractValidator validator;
  private JsonNode fixtures;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    validator = new GameplayV2ContractValidator(objectMapper);
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v2.json")) {
      fixtures = objectMapper.readTree(input);
    }
  }

  @Test
  void validFullSequenceReservesDeliveryPendingWithOneTransportCall() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult result = gate.reserve(bundle());
    assertThat(result.state()).isEqualTo(V2State.DELIVERY_PENDING);
    assertThat(result.failureCode()).isNull();
    assertThat(transport.reserves.get()).isEqualTo(1);
    assertThat(transport.starts.get()).isZero();
    var started = gate.start(result.requestId(), 1L, 1L);
    assertThat(started.state()).isEqualTo(V2State.ACTIVE);
    assertThat(transport.starts.get()).isEqualTo(1);
  }

  @Test
  void transmittedBytesCarryCompleteBundleWithCanonicalProviderDecisionKey() throws Exception {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult result = gate.reserve(bundle());
    assertThat(result.state()).isEqualTo(V2State.DELIVERY_PENDING);
    JsonNode transmitted = objectMapper.readTree(transport.lastReserveBytes);
    for (String member :
        List.of(
            "catalogSnapshot",
            "semanticObservation",
            "planningInput",
            "eligibilityResult",
            "providerDecision",
            "goalPlan",
            "semanticAction",
            "executionEnvelope",
            "approvedScope",
            "authority")) {
      assertThat(transmitted.has(member)).as("member " + member).isTrue();
    }
    assertThat(transmitted.has("providerOutput")).isFalse();
    assertThat(transmitted.has("envelope")).isFalse();
  }

  @Test
  void mutationAfterValidationDoesNotChangeTransmittedBytes() throws Exception {
    CapturingTransport transport = new CapturingTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2Bundle shared = bundle();
    JsonNode liveHandle = shared.executionEnvelope();
    transport.beforeReserve = () -> ((ObjectNode) shared.executionEnvelope()).put("fence", 4242L);
    V2ReserveResult result = gate.reserve(shared);
    assertThat(result.state()).isEqualTo(V2State.DELIVERY_PENDING);
    JsonNode transmitted = objectMapper.readTree(transport.lastReserveBytes);
    String transmittedDigest =
        GameplayV2EligibilityFilter.canonicalDigest(
            objectMapper, transmitted.path("executionEnvelope"));
    String validatedDigest =
        GameplayV2EligibilityFilter.canonicalDigest(
            objectMapper, fixtures.path("executionEnvelope"));
    assertThat(transmittedDigest).isEqualTo(validatedDigest);
    assertThat(transmitted.path("executionEnvelope").path("fence").asLong()).isEqualTo(1L);
    assertThat(liveHandle.path("fence").asLong()).isEqualTo(4242L);
  }

  @Test
  void fabricatedEligibilityIsDeniedBeforeTransport() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2Bundle bundle = bundle();
    ((ObjectNode) bundle.eligibilityResult().path("entries").path(0))
        .put("taskKey", "SOMETHING_ELSE");
    V2ReserveResult result = gate.reserve(bundle);
    assertThat(result.state()).isEqualTo(V2State.FAILED);
    assertThat(result.failureCode()).isEqualTo("IDENTITY_MISMATCH");
    assertThat(transport.reserves.get()).isZero();
  }

  @Test
  void mutatedProviderDecisionGoalPlanOrEnvelopeIsDeniedBeforeTransport() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2Bundle mutatedProvider = bundle();
    ((ObjectNode)
            mutatedProvider
                .providerDecision()
                .path("goalPlan")
                .path("nodes")
                .path(0)
                .path("settings"))
        .put("targetLevel", 99);
    V2ReserveResult providerDenied = gate.reserve(mutatedProvider);
    assertThat(providerDenied.state()).isEqualTo(V2State.FAILED);
    V2Bundle mutatedEnvelope = bundle();
    ((ObjectNode) mutatedEnvelope.executionEnvelope()).put("fence", 9999L);
    V2ReserveResult envelopeDenied = gate.reserve(mutatedEnvelope);
    assertThat(envelopeDenied.state()).isEqualTo(V2State.FAILED);
    assertThat(transport.reserves.get()).isZero();
  }

  @Test
  void substitutedCatalogIsDeniedBeforeTransport() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2Bundle bundle = bundle();
    ((ObjectNode) bundle.catalogSnapshot()).put("catalogId", "catalog-substituted");
    V2ReserveResult result = gate.reserve(bundle);
    assertThat(result.state()).isEqualTo(V2State.FAILED);
    assertThat(transport.reserves.get()).isZero();
  }

  @Test
  void invalidSchemaOrMissingAuthorityIsDeniedBeforeTransport() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2Bundle badSchema = bundle();
    ((ObjectNode) badSchema.executionEnvelope().path("settings")).put("targetLevel", 100);
    V2ReserveResult schemaDenied = gate.reserve(badSchema);
    assertThat(schemaDenied.state()).isEqualTo(V2State.FAILED);
    V2Bundle missingAuthority = bundle();
    ((ObjectNode) missingAuthority.executionEnvelope()).putNull("authorityId");
    V2ReserveResult authorityDenied = gate.reserve(missingAuthority);
    assertThat(authorityDenied.state()).isEqualTo(V2State.FAILED);
    assertThat(authorityDenied.failureCode()).isEqualTo("AUTHORITY_MISSING");
    assertThat(transport.reserves.get()).isZero();
  }

  @Test
  void authorityReuseWithDifferentBytesIsDenied() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult first = gate.reserve(bundle());
    assertThat(first.state()).isEqualTo(V2State.DELIVERY_PENDING);
    V2Bundle replay = bundle();
    ((ObjectNode) replay.executionEnvelope()).put("fence", 2L);
    V2ReserveResult denied = gate.reserve(replay);
    assertThat(denied.state()).isEqualTo(V2State.FAILED);
    assertThat(denied.failureCode()).isEqualTo("AUTHORITY_CHANGED");
    assertThat(transport.reserves.get()).isEqualTo(1);
    assertThat(transport.reserves.get()).isEqualTo(1);
  }

  @Test
  void idempotencyConflictOnDifferentRequestWithSameKey() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult first = gate.reserve(bundle());
    assertThat(first.state()).isEqualTo(V2State.DELIVERY_PENDING);
    V2Bundle conflicting = bundleWithSameKeyDifferentRequest();
    V2ReserveResult conflict = gate.reserve(conflicting);
    assertThat(conflict.state()).isEqualTo(V2State.FAILED);
    assertThat(conflict.failureCode()).isEqualTo(Failure.IDEMPOTENCY_CONFLICT);
    assertThat(transport.reserves.get()).isEqualTo(1);
  }

  @Test
  void concurrentIdenticalReserveAdmitsExactlyOneTransportCall() throws Exception {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    int racers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    CountDownLatch ready = new CountDownLatch(racers);
    CountDownLatch start = new CountDownLatch(1);
    V2Bundle shared = bundle();
    try {
      List<Future<V2ReserveResult>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < racers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return gate.reserve(shared);
                }));
      }
      ready.await();
      start.countDown();
      int fresh = 0;
      int reconciled = 0;
      for (Future<V2ReserveResult> future : futures) {
        V2ReserveResult result = future.get();
        if (result.reconciled()) {
          reconciled++;
        } else {
          fresh++;
        }
      }
      assertThat(fresh).isEqualTo(1);
      assertThat(reconciled).isEqualTo(racers - 1);
      assertThat(transport.reserves.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void crashAfterPersistentReservationReconcilesWithoutRedispatch() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult first = gate.reserve(bundle());
    assertThat(first.state()).isEqualTo(V2State.DELIVERY_PENDING);
    V2StatusResult status = gate.status(first.requestId());
    assertThat(status.state()).isEqualTo(V2State.DELIVERY_PENDING);
    V2ReserveResult replay = gate.reserve(bundle());
    assertThat(replay.reconciled()).isTrue();
    assertThat(replay.state()).isEqualTo(V2State.DELIVERY_PENDING);
    assertThat(transport.reserves.get()).isEqualTo(1);
  }

  @Test
  void restartWithSharedDurableStoreKeepsReservationAndReconciles() {
    InMemoryGameplayV2GateStore shared = new InMemoryGameplayV2GateStore();
    FakeTransport firstTransport = new FakeTransport();
    GameplayV2LiveGateService first = gate(firstTransport, shared);
    V2ReserveResult reserved = first.reserve(bundle());
    assertThat(reserved.state()).isEqualTo(V2State.DELIVERY_PENDING);

    GameplayV2LiveGateService restarted = gate(new FakeTransport(), shared);
    V2StatusResult afterRestart = restarted.status(reserved.requestId());
    assertThat(afterRestart.state()).isEqualTo(V2State.DELIVERY_PENDING);
    V2ReserveResult replay = restarted.reserve(bundle());
    assertThat(replay.reconciled()).isTrue();
    assertThat(firstTransport.reserves.get()).isEqualTo(1);

    List<V2StatusResult> recovered = restarted.recoverAfterRestart();
    assertThat(recovered).hasSize(1);
    assertThat(recovered.get(0).state()).isEqualTo(V2State.NEEDS_RECONCILIATION);
    assertThat(restarted.status(reserved.requestId()).state())
        .isEqualTo(V2State.NEEDS_RECONCILIATION);
  }

  @Test
  void reconciliationOfRemoteReservedRestoresStartableDeliveryPending() {
    InMemoryGameplayV2GateStore shared = new InMemoryGameplayV2GateStore();
    DisconnectTransport disconnect = new DisconnectTransport();
    GameplayV2LiveGateService uncertainGate = gate(disconnect, shared);
    V2ReserveResult uncertain = uncertainGate.reserve(bundle());
    assertThat(uncertain.state()).isEqualTo(V2State.NEEDS_RECONCILIATION);

    GameplayV2LiveGateService healthy = gate(new RemoteReservedTransport(fixtures), shared);
    V2StatusResult reconciled = healthy.reconcile(uncertain.requestId());
    assertThat(reconciled.state()).isEqualTo(V2State.DELIVERY_PENDING);
    var started = healthy.start(uncertain.requestId(), 1L, 1L);
    assertThat(started.state()).isEqualTo(V2State.ACTIVE);
  }

  @Test
  void concurrentStartsDispatchExactlyOneMicro6Start() throws Exception {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult reserved = gate.reserve(bundle());
    int racers = 4;
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    CountDownLatch ready = new CountDownLatch(racers);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<V2StartResult>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < racers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return gate.start(reserved.requestId(), 1L, 1L);
                }));
      }
      ready.await();
      start.countDown();
      int active = 0;
      for (Future<V2StartResult> future : futures) {
        V2StartResult result = future.get();
        if (result.state() == V2State.ACTIVE) {
          active++;
        }
      }
      assertThat(active).isGreaterThanOrEqualTo(1);
      for (Future<V2StartResult> future : futures) {
        assertThat(future.get().state()).isIn(V2State.ACTIVE, V2State.STARTING);
      }
      assertThat(transport.starts.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void definitiveMicro6StartRejectionMarksRowFailed() {
    DenyingStartTransport transport = new DenyingStartTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult reserved = gate.reserve(bundle());
    var started = gate.start(reserved.requestId(), 1L, 1L);
    assertThat(started.state()).isEqualTo(V2State.FAILED);
    assertThat(started.failureCode()).isEqualTo("START_REJECTED");
    assertThat(gate.status(reserved.requestId()).state()).isEqualTo(V2State.FAILED);
    assertThat(transport.starts.get()).isEqualTo(1);
  }

  @Test
  void malformedStartReceiptNeedsReconciliationWithoutRedispatch() {
    IncompleteStartTransport transport = new IncompleteStartTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult reserved = gate.reserve(bundle());
    var started = gate.start(reserved.requestId(), 1L, 1L);
    assertThat(started.state()).isEqualTo(V2State.NEEDS_RECONCILIATION);
    var retried = gate.start(reserved.requestId(), 1L, 1L);
    assertThat(retried.state()).isEqualTo(V2State.NEEDS_RECONCILIATION);
    assertThat(transport.starts.get()).isEqualTo(1);
  }

  @Test
  void verifiedStopPersistsEvidenceBeforeReportingStopped() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult reserved = gate.reserve(bundle());
    V2StatusResult stopped = gate.stop(reserved.requestId(), "operator-request", false);
    assertThat(stopped.state()).isEqualTo(V2State.STOPPED);
    assertThat(stopped.evidenceRefs())
        .contains(
            "micro6:v2:stop:" + reserved.requestId(), "chromascape:stop:" + reserved.requestId());
    assertThat(gate.status(reserved.requestId()).evidenceRefs())
        .contains("micro6:v2:stop:" + reserved.requestId());
    var repeat = gate.stop(reserved.requestId(), "operator-request", false);
    assertThat(repeat.state()).isEqualTo(V2State.STOPPED);
    assertThat(repeat.reconciled()).isTrue();
    assertThat(transport.stops.get()).isEqualTo(1);
  }

  @Test
  void malformedStopReceiptNeedsReconciliation() {
    IncompleteStopTransport transport = new IncompleteStopTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult reserved = gate.reserve(bundle());
    V2StatusResult stopped = gate.stop(reserved.requestId(), "operator-request", false);
    assertThat(stopped.state()).isEqualTo(V2State.NEEDS_RECONCILIATION);
    assertThat(gate.status(reserved.requestId()).state()).isEqualTo(V2State.NEEDS_RECONCILIATION);
  }

  @Test
  void disconnectBeforeAndAfterAcknowledgementReconcilesWithoutRedispatch() {
    FlakyTransport flaky = new FlakyTransport(true);
    GameplayV2LiveGateService gate = gate(flaky);
    V2ReserveResult uncertain = gate.reserve(bundle());
    assertThat(uncertain.state()).isEqualTo(V2State.NEEDS_RECONCILIATION);
    var reconciled = gate.reconcile(uncertain.requestId());
    assertThat(reconciled.state()).isEqualTo(V2State.NEEDS_RECONCILIATION);
    assertThat(flaky.reserves.get()).isEqualTo(1);
    FakeTransport healthy = new FakeTransport();
    GameplayV2LiveGateService recovered = gate(healthy);
    assertThat(recovered).isNotNull();
  }

  @Test
  void staleLeaseAndFenceAreDeniedBeforeTransport() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate = gate(transport);
    V2ReserveResult first = gate.reserve(bundle());
    assertThat(first.state()).isEqualTo(V2State.DELIVERY_PENDING);
    var start = gate.start(first.requestId(), 0L, 0L);
    assertThat(start.failureCode()).isEqualTo(Failure.FENCE_MISMATCH);
    assertThat(transport.starts.get()).isZero();
  }

  @Test
  void restartRecoveryMarksInFlightRowsNeedsReconciliation() {
    DisconnectTransport disconnect = new DisconnectTransport();
    GameplayV2LiveGateService gate = gate(disconnect);
    V2ReserveResult uncertain = gate.reserve(bundle());
    assertThat(uncertain.state()).isEqualTo(V2State.NEEDS_RECONCILIATION);
    var recovered = gate.recoverAfterRestart();
    assertThat(recovered).isEmpty();
    var status = gate.status(uncertain.requestId());
    assertThat(status.state()).isEqualTo(V2State.NEEDS_RECONCILIATION);
    assertThat(disconnect.reserves.get()).isEqualTo(1);
  }

  @Test
  void disabledLiveFlagDeniesBeforeTransport() {
    FakeTransport transport = new FakeTransport();
    GameplayV2LiveGateService gate =
        new GameplayV2LiveGateService(
            validator,
            new ClaudeGameplayV2ProviderAdapter(validator),
            objectMapper,
            Micro6GameplayV2Gateway.wrapping(objectMapper, transport),
            new InMemoryGameplayV2GateStore(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            false,
            fixtures.path("testSigningSecret").asText());
    V2ReserveResult denied = gate.reserve(bundle());
    assertThat(denied.state()).isEqualTo(V2State.FAILED);
    assertThat(denied.failureCode()).isEqualTo(Failure.LIVE_DISABLED);
    assertThat(transport.reserves.get()).isZero();
  }

  private GameplayV2LiveGateService gate(V2Transport transport) {
    return gate(transport, new InMemoryGameplayV2GateStore());
  }

  private GameplayV2LiveGateService gate(V2Transport transport, InMemoryGameplayV2GateStore store) {
    Micro6GameplayV2Gateway gateway = Micro6GameplayV2Gateway.wrapping(objectMapper, transport);
    return new GameplayV2LiveGateService(
        validator,
        new ClaudeGameplayV2ProviderAdapter(validator),
        objectMapper,
        gateway,
        store,
        Clock.fixed(NOW, ZoneOffset.UTC),
        true,
        fixtures.path("testSigningSecret").asText());
  }

  private V2Bundle bundle() {
    return bundleWithRequestId(fixtures.path("executionEnvelope").path("requestId").asText());
  }

  private V2Bundle bundleWithRequestId(String requestId) {
    JsonNode envelope = fixtures.path("executionEnvelope").deepCopy();
    if (!envelope.path("requestId").asText().equals(requestId)) {
      ((ObjectNode) envelope).put("requestId", requestId);
    }
    return new V2Bundle(
        fixtures.path("catalogSnapshot"),
        fixtures.path("semanticObservation"),
        fixtures.path("planningInput"),
        fixtures.path("eligibilityResult"),
        fixtures.path("claudeDecision"),
        fixtures.path("goalPlan"),
        fixtures.path("semanticAction"),
        envelope,
        fixtures.path("approvedScope"),
        fixtures.path("authority"),
        GameplayV2DispatchModels.canonicalSchemaDigest(),
        "CLAUDE",
        "claude-fixture-v2",
        "orchestration-" + requestId,
        "chromascape-v2-live",
        NOW);
  }

  /**
   * Builds a conflicting bundle that carries a different request id but reuses the fixture
   * idempotency key. Only the request id (and its matching authority binding) changes, so the
   * fingerprint differs on the shared key: the gate must report the conflict before consuming
   * anything new.
   */
  private V2Bundle bundleWithSameKeyDifferentRequest() {
    String requestId = "request-sc1429-099";
    String authorityId = "auth-sc1429-099";
    JsonNode envelope = fixtures.path("executionEnvelope").deepCopy();
    ((ObjectNode) envelope).put("requestId", requestId);
    ((ObjectNode) envelope).put("authorityId", authorityId);
    String envelopeDigest = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope);
    JsonNode authority = fixtures.path("authority").deepCopy();
    ((ObjectNode) authority).put("authorityId", authorityId);
    ((ObjectNode) authority).put("envelopeDigest", envelopeDigest);
    ((ObjectNode) authority)
        .put("signature", resign(authority, fixtures.path("testSigningSecret").asText()));
    return new V2Bundle(
        fixtures.path("catalogSnapshot"),
        fixtures.path("semanticObservation"),
        fixtures.path("planningInput"),
        fixtures.path("eligibilityResult"),
        fixtures.path("claudeDecision"),
        fixtures.path("goalPlan"),
        fixtures.path("semanticAction"),
        envelope,
        fixtures.path("approvedScope"),
        authority,
        GameplayV2DispatchModels.canonicalSchemaDigest(),
        "CLAUDE",
        "claude-fixture-v2",
        "orchestration-" + requestId,
        "chromascape-v2-live",
        NOW);
  }

  private String resign(JsonNode authority, String secret) {
    try {
      ObjectNode unsigned = authority.deepCopy();
      unsigned.remove("signature");
      byte[] canonical = GameplayV2EligibilityFilter.canonicalDigestBytes(objectMapper, unsigned);
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(
              secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
      return "hmac-sha256:" + java.util.HexFormat.of().formatHex(mac.doFinal(canonical));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to re-sign test authority", exception);
    }
  }

  private static String digestOf(JsonNode envelope) {
    return GameplayV2EligibilityFilter.canonicalDigest(new ObjectMapper(), envelope);
  }

  /**
   * The shape Micro6's {@code /api/v2/gameplay/status} actually returns, including the truthful
   * HTTP stamp the gateway adds on every response. Receipt verification requires all of it.
   */
  private static ObjectNode statusReceipt(String requestId, String status, String envelopeDigest) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("success", true);
    payload.put("requestId", requestId);
    payload.put("status", status);
    if (envelopeDigest != null) {
      payload.put("envelopeDigest", envelopeDigest);
    }
    payload.put("deliveryAcknowledged", true);
    payload.put("terminalReconciled", false);
    payload.put("_httpSuccess", true);
    payload.put("_httpStatus", 200);
    payload.set(
        "evidenceRefs",
        JsonNodeFactory.instance.arrayNode().add("micro6:v2:execution:" + requestId));
    return payload;
  }

  private static ObjectNode receipt(String requestId, JsonNode envelope, String status) {
    ObjectNode ack = JsonNodeFactory.instance.objectNode();
    ack.put("success", true);
    ack.put("requestId", requestId);
    ack.put("envelopeDigest", digestOf(envelope));
    ack.put("status", status);
    ack.put("_httpSuccess", true);
    ack.put("_httpStatus", 201);
    ack.put("deliveryAcknowledged", true);
    ack.put("reconciled", false);
    ack.set(
        "evidenceRefs",
        JsonNodeFactory.instance
            .arrayNode()
            .add("micro6:v2:" + status.toLowerCase() + ":" + requestId));
    return ack;
  }

  /** Transport that reproduces the Micro6 v2 receipt shapes over the byte[] reserve seam. */
  private static class FakeTransportBase implements V2Transport {
    final AtomicInteger reserves = new AtomicInteger();
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger stops = new AtomicInteger();
    final ConcurrentMap<String, JsonNode> stored = new ConcurrentHashMap<>();
    volatile byte[] lastReserveBytes;

    @Override
    public JsonNode reserve(byte[] bundleBytes, String idempotencyKey) {
      reserves.incrementAndGet();
      lastReserveBytes = bundleBytes.clone();
      try {
        JsonNode bundle = new ObjectMapper().readTree(bundleBytes);
        JsonNode envelope = bundle.path("executionEnvelope");
        String requestId = envelope.path("requestId").asText();
        stored.put(requestId, envelope.deepCopy());
        return receipt(requestId, envelope, "RESERVED");
      } catch (Exception exception) {
        throw new IllegalStateException(exception);
      }
    }

    @Override
    public JsonNode start(JsonNode envelope, String idempotencyKey) {
      starts.incrementAndGet();
      return receipt(envelope.path("requestId").asText(), envelope, "ACTIVE");
    }

    @Override
    public JsonNode status(String requestId) {
      JsonNode envelope = stored.get(requestId);
      ObjectNode status =
          statusReceipt(
              requestId,
              envelope == null ? "UNKNOWN" : "RESERVED",
              envelope == null ? null : digestOf(envelope));
      return status;
    }

    @Override
    public JsonNode stop(String requestId, String reason, boolean killSwitch) {
      stops.incrementAndGet();
      JsonNode envelope = stored.get(requestId);
      ObjectNode stopped = JsonNodeFactory.instance.objectNode();
      stopped.put("success", true);
      stopped.put("requestId", requestId);
      stopped.put("status", "STOPPED");
      // Micro6's v2StopResponse binds the receipt to the reservation with the envelope digest and
      // acknowledges delivery; a receipt without those cannot be verified and must not be used as
      // a fixture that quietly proves less than production does.
      stopped.put("envelopeDigest", envelope == null ? "unknown" : digestOf(envelope));
      stopped.put("killSwitch", killSwitch);
      stopped.put("reconciled", false);
      stopped.put("deliveryAcknowledged", true);
      stopped.put("terminalReconciled", true);
      stopped.put("_httpSuccess", true);
      stopped.put("_httpStatus", 200);
      stopped.set(
          "evidenceRefs", JsonNodeFactory.instance.arrayNode().add("micro6:v2:stop:" + requestId));
      return stopped;
    }

    @Override
    public JsonNode catalog() {
      return JsonNodeFactory.instance.objectNode();
    }

    @Override
    public JsonNode observe(String clientId) {
      return JsonNodeFactory.instance.objectNode();
    }
  }

  private static final class FakeTransport extends FakeTransportBase {}

  /** Captures transmitted bytes and runs a caller hook before responding. */
  private static final class CapturingTransport extends FakeTransportBase {
    volatile Runnable beforeReserve;

    @Override
    public JsonNode reserve(byte[] bundleBytes, String idempotencyKey) {
      if (beforeReserve != null) {
        beforeReserve.run();
      }
      return super.reserve(bundleBytes, idempotencyKey);
    }
  }

  /** Reports a Micro6-confirmed RESERVED with the fixture digest for reconciliation tests. */
  private static final class RemoteReservedTransport extends FakeTransportBase {
    private final JsonNode fixtures;

    RemoteReservedTransport(JsonNode fixtures) {
      this.fixtures = fixtures;
    }

    @Override
    public JsonNode status(String requestId) {
      return statusReceipt(requestId, "RESERVED", digestOf(fixtures.path("executionEnvelope")));
    }
  }

  /** Rejects start definitively the way Micro6's closed denial payloads do. */
  private static final class DenyingStartTransport extends FakeTransportBase {
    @Override
    public JsonNode start(JsonNode envelope, String idempotencyKey) {
      starts.incrementAndGet();
      ObjectNode denial = JsonNodeFactory.instance.objectNode();
      denial.put("success", false);
      denial.put("outcome", "DENIED");
      denial.put("failureCode", "START_REJECTED");
      denial.put("message", "queue rejected the task");
      denial.put("deliveryAcknowledged", false);
      denial.put("_httpSuccess", false);
      denial.put("_httpStatus", 403);
      return denial;
    }
  }

  /** Start acknowledgement missing the mandatory delivery acknowledgement. */
  private static final class IncompleteStartTransport extends FakeTransportBase {
    @Override
    public JsonNode start(JsonNode envelope, String idempotencyKey) {
      starts.incrementAndGet();
      ObjectNode ack = receipt(envelope.path("requestId").asText(), envelope, "ACTIVE");
      ack.remove("deliveryAcknowledged");
      return ack;
    }
  }

  /** Stop acknowledgement missing the mandatory status. */
  private static final class IncompleteStopTransport extends FakeTransportBase {
    @Override
    public JsonNode stop(String requestId, String reason, boolean killSwitch) {
      stops.incrementAndGet();
      ObjectNode stopped = JsonNodeFactory.instance.objectNode();
      stopped.put("success", true);
      stopped.put("requestId", requestId);
      stopped.put("stopped", true);
      return stopped;
    }
  }

  private static final class FlakyTransport extends FakeTransportBase {
    private final boolean failFirst;
    private final AtomicInteger calls = new AtomicInteger();

    FlakyTransport(boolean failFirst) {
      this.failFirst = failFirst;
    }

    @Override
    public JsonNode reserve(byte[] bundleBytes, String idempotencyKey) {
      if (failFirst && calls.getAndIncrement() == 0) {
        super.reserves.incrementAndGet();
        throw new GameplayV2DispatchModels.V2TransportException("disconnect", null);
      }
      return super.reserve(bundleBytes, idempotencyKey);
    }
  }

  private static final class DisconnectTransport extends FakeTransportBase {
    @Override
    public JsonNode reserve(byte[] bundleBytes, String idempotencyKey) {
      super.reserves.incrementAndGet();
      throw new GameplayV2DispatchModels.V2TransportException("disconnect", null);
    }
  }
}

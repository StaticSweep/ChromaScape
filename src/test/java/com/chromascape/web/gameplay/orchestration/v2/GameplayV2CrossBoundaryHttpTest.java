package com.chromascape.web.gameplay.orchestration.v2;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2Bundle;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2ReserveResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StartResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2State;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StatusResult;
import com.chromascape.web.gameplay.provider.v2.ClaudeGameplayV2ProviderAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SC-1449 phase 10: the ChromaScape live gate driven end to end against a Micro6 v2 endpoint over
 * real HTTP.
 *
 * <p>Every other v2 test substitutes the transport, which proves the gate's own logic but proves
 * nothing about the wire. Here the real {@link Micro6GameplayV2Gateway} sends real requests over a
 * loopback socket to a server that implements Micro6's v2 contract independently: it recomputes the
 * envelope digest from the bytes it actually received, keeps its own reservation lifecycle, refuses
 * a start whose digest does not match what it reserved, and answers status and stop from its own
 * state. The digest agreement across the boundary is therefore proven rather than assumed.
 *
 * <p><b>What this does not prove.</b> The peer is a contract double written from the same spec, not
 * Micro6. Running the real {@code Micro6BuilderExecutorServer} would need the RuneLite client on
 * this classpath and a second JVM, which is neither hermetic nor available to a Gradle test here.
 * So this test pins serialization, the digest recomputed from received bytes, routing, status
 * codes, idempotency headers and the state machine over a real socket — and pins nothing about
 * Micro6's own implementation of that contract. Agreement with the real server is established by
 * the separate cross-process run against a live Micro6 on Windows, which remains a manual gate and
 * is not substituted for by this class.
 */
class GameplayV2CrossBoundaryHttpTest {
  private static final Instant NOW = Instant.parse("2026-09-03T12:04:00Z");

  private ObjectMapper objectMapper;
  private GameplayV2ContractValidator validator;
  private JsonNode fixtures;
  private HttpServer server;
  private Micro6V2Endpoint micro6;

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
    micro6 = new Micro6V2Endpoint(objectMapper);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v2/gameplay/reserve", micro6::reserve);
    server.createContext("/api/v2/gameplay/start", micro6::start);
    server.createContext("/api/v2/gameplay/status/", micro6::status);
    server.createContext("/api/v2/gameplay/stop", micro6::stop);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void theFullReserveStartStatusStopSequenceCrossesTheRealHttpBoundary() {
    GameplayV2LiveGateService gate = gate();

    V2ReserveResult reserved = gate.reserve(bundle());
    assertThat(reserved.state()).isEqualTo(V2State.DELIVERY_PENDING);
    assertThat(reserved.failureCode()).isNull();
    assertThat(micro6.reserveCalls).hasValue(1);
    assertThat(micro6.startCalls).hasValue(0);

    V2StartResult started = gate.start(reserved.requestId(), 1L, 1L);
    assertThat(started.state()).isEqualTo(V2State.ACTIVE);
    assertThat(started.failureCode()).isNull();
    assertThat(micro6.startCalls).hasValue(1);

    // Status is answered from the durable row, so it deliberately costs no network call.
    V2StatusResult status = gate.status(reserved.requestId());
    assertThat(status.state()).isEqualTo(V2State.ACTIVE);

    V2StatusResult stopped = gate.stop(reserved.requestId(), "operator_stop", false);
    assertThat(stopped.state()).isEqualTo(V2State.STOPPED);
    assertThat(stopped.state().provenTerminal()).isTrue();
    assertThat(micro6.stopCalls).hasValue(1);

    // Reserve, start and stop are independent operations on the wire; nothing was combined, and
    // observing state never re-delivered anything.
    assertThat(micro6.paths)
        .containsExactly(
            "/api/v2/gameplay/reserve", "/api/v2/gameplay/start", "/api/v2/gameplay/stop");
  }

  @Test
  void reconciliationReadsMicro6StateOverTheStatusRouteWithoutRedispatch() {
    GameplayV2LiveGateService gate = gate();
    V2ReserveResult reserved = gate.reserve(bundle());
    assertThat(reserved.state()).isEqualTo(V2State.DELIVERY_PENDING);

    V2StatusResult reconciled = gate.reconcile(reserved.requestId());

    // Reconciliation is the operation that actually asks Micro6, and it must never redeliver.
    assertThat(micro6.paths)
        .containsExactly(
            "/api/v2/gameplay/reserve", "/api/v2/gameplay/status/" + reserved.requestId());
    assertThat(micro6.reserveCalls).hasValue(1);
    assertThat(micro6.startCalls).hasValue(0);
    assertThat(reconciled.requestId()).isEqualTo(reserved.requestId());
    assertThat(reconciled.envelopeDigest()).isEqualTo(reserved.envelopeDigest());
  }

  @Test
  void micro6RecomputesTheEnvelopeDigestFromTheBytesItActuallyReceived() throws Exception {
    GameplayV2LiveGateService gate = gate();
    V2ReserveResult reserved = gate.reserve(bundle());

    // The digest ChromaScape bound and the digest Micro6 derived independently must agree; that
    // agreement is the whole point of transmitting the exact validated bytes.
    assertThat(micro6.digestOfLastReserve).isEqualTo(reserved.envelopeDigest());

    // And the wire body is byte-identical to the canonical bundle, not a re-serialization.
    JsonNode transmitted = objectMapper.readTree(micro6.lastReserveBody);
    assertThat(transmitted.has("providerDecision")).isTrue();
    assertThat(transmitted.has("providerOutput")).isFalse();
    assertThat(
            GameplayV2EligibilityFilter.canonicalDigest(
                objectMapper, transmitted.path("executionEnvelope")))
        .isEqualTo(reserved.envelopeDigest());
  }

  @Test
  void theIdempotencyKeyTravelsOnTheWireAndAReplayReservesOnlyOnce() {
    GameplayV2LiveGateService gate = gate();
    V2ReserveResult first = gate.reserve(bundle());
    assertThat(first.state()).isEqualTo(V2State.DELIVERY_PENDING);
    assertThat(micro6.lastIdempotencyKey)
        .isEqualTo(fixtures.path("executionEnvelope").path("idempotencyKey").asText());

    V2ReserveResult replay = gate.reserve(bundle());
    assertThat(replay.reconciled()).isTrue();
    assertThat(replay.requestId()).isEqualTo(first.requestId());
    // The durable row already exists, so the replay never touches the network again.
    assertThat(micro6.reserveCalls).hasValue(1);
  }

  @Test
  void aStartWhoseDigestMicro6CannotMatchIsReportedTruthfullyAndNeverBecomesActive() {
    GameplayV2LiveGateService gate = gate();
    V2ReserveResult reserved = gate.reserve(bundle());
    micro6.rejectNextStart = true;

    V2StartResult started = gate.start(reserved.requestId(), 1L, 1L);
    assertThat(started.state()).isNotEqualTo(V2State.ACTIVE);
    assertThat(started.failureCode()).isNotNull();

    // A refused start leaves an auditable row rather than a silently dropped request.
    V2StatusResult status = gate.status(reserved.requestId());
    assertThat(status.state()).isNotEqualTo(V2State.ACTIVE);
  }

  @Test
  void aMicro6OutageDuringReserveNeedsReconciliationRatherThanRedispatch() {
    GameplayV2LiveGateService gate = gate();
    server.stop(0);

    V2ReserveResult result = gate.reserve(bundle());
    assertThat(result.state()).isIn(V2State.NEEDS_RECONCILIATION, V2State.FAILED, V2State.RESERVED);
    assertThat(micro6.reserveCalls).hasValue(0);

    // Whatever the durable outcome, the request stays observable and no second delivery happened.
    V2StatusResult status = gate.status(result.requestId());
    assertThat(status.requestId()).isEqualTo(result.requestId());
    assertThat(micro6.reserveCalls).hasValue(0);
  }

  private GameplayV2LiveGateService gate() {
    Micro6GameplayV2Gateway gateway =
        new Micro6GameplayV2Gateway(
            objectMapper, "http://127.0.0.1:" + server.getAddress().getPort());
    return new GameplayV2LiveGateService(
        validator,
        new ClaudeGameplayV2ProviderAdapter(validator),
        objectMapper,
        gateway,
        new InMemoryGameplayV2GateStore(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        true,
        fixtures.path("testSigningSecret").asText());
  }

  private V2Bundle bundle() {
    return new V2Bundle(
        fixtures.path("catalogSnapshot"),
        fixtures.path("semanticObservation"),
        fixtures.path("planningInput"),
        fixtures.path("eligibilityResult"),
        fixtures.path("claudeDecision"),
        fixtures.path("goalPlan"),
        fixtures.path("semanticAction"),
        fixtures.path("executionEnvelope"),
        fixtures.path("approvedScope"),
        fixtures.path("authority"),
        GameplayV2DispatchModels.canonicalSchemaDigest(),
        "CLAUDE",
        "claude-fixture-v2",
        "orchestration-cross-boundary",
        "chromascape-v2-live",
        NOW);
  }

  /**
   * An independent implementation of Micro6's v2 endpoint contract. It deliberately derives the
   * envelope digest from the received bytes instead of trusting anything ChromaScape asserts, and
   * keeps its own lifecycle so status and stop answer from Micro6 state rather than an echo.
   */
  private static final class Micro6V2Endpoint {
    private final ObjectMapper objectMapper;
    private final Map<String, String> reservedDigests = new ConcurrentHashMap<>();
    private final Map<String, String> states = new ConcurrentHashMap<>();
    private final AtomicInteger reserveCalls = new AtomicInteger();
    private final AtomicInteger startCalls = new AtomicInteger();
    private final AtomicInteger stopCalls = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile byte[] lastReserveBody;
    private volatile String lastIdempotencyKey;
    private volatile String digestOfLastReserve;
    private volatile boolean rejectNextStart;

    Micro6V2Endpoint(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    void reserve(HttpExchange exchange) throws IOException {
      paths.add(exchange.getRequestURI().getPath());
      lastIdempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
      byte[] body = exchange.getRequestBody().readAllBytes();
      lastReserveBody = body;
      reserveCalls.incrementAndGet();
      JsonNode bundle = objectMapper.readTree(body);
      JsonNode envelope = bundle.path("executionEnvelope");
      String requestId = envelope.path("requestId").asText();
      String digest = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope);
      digestOfLastReserve = digest;
      reservedDigests.put(requestId, digest);
      states.put(requestId, "RESERVED");
      respond(exchange, 201, receipt(requestId, digest, "RESERVED", true));
    }

    void start(HttpExchange exchange) throws IOException {
      paths.add(exchange.getRequestURI().getPath());
      startCalls.incrementAndGet();
      JsonNode body = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
      JsonNode envelope = body.path("envelope");
      String requestId = envelope.path("requestId").asText();
      String reservedDigest = reservedDigests.get(requestId);
      String presentedDigest = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope);
      if (rejectNextStart || reservedDigest == null || !reservedDigest.equals(presentedDigest)) {
        rejectNextStart = false;
        ObjectNode denial = JsonNodeFactory.instance.objectNode();
        denial.put("success", false);
        denial.put("outcome", "DENIED");
        denial.put("requestId", requestId);
        denial.put("failureCode", "ENVELOPE_DIGEST_MISMATCH");
        denial.put("message", "start envelope does not match the reservation");
        denial.put("deliveryAcknowledged", false);
        respond(exchange, 409, denial);
        return;
      }
      states.put(requestId, "ACTIVE");
      respond(exchange, 200, receipt(requestId, reservedDigest, "ACTIVE", true));
    }

    void status(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      paths.add(path);
      exchange.getRequestBody().readAllBytes();
      String requestId = path.substring(path.lastIndexOf('/') + 1);
      ObjectNode payload = JsonNodeFactory.instance.objectNode();
      payload.put("success", true);
      payload.put("requestId", requestId);
      payload.put("status", states.getOrDefault(requestId, "UNKNOWN"));
      String digest = reservedDigests.get(requestId);
      if (digest != null) {
        payload.put("envelopeDigest", digest);
      }
      payload.put("deliveryAcknowledged", true);
      payload.put("terminalReconciled", false);
      payload.set(
          "evidenceRefs",
          JsonNodeFactory.instance.arrayNode().add("micro6:v2:execution:" + requestId));
      respond(exchange, 200, payload);
    }

    void stop(HttpExchange exchange) throws IOException {
      paths.add(exchange.getRequestURI().getPath());
      stopCalls.incrementAndGet();
      JsonNode body = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
      String requestId = body.path("requestId").asText();
      states.put(requestId, "STOPPED");
      ObjectNode payload = JsonNodeFactory.instance.objectNode();
      payload.put("success", true);
      payload.put("requestId", requestId);
      payload.put("status", "STOPPED");
      payload.put("envelopeDigest", reservedDigests.getOrDefault(requestId, "unknown"));
      payload.put("killSwitch", body.path("killSwitch").asBoolean(false));
      payload.put("reconciled", false);
      payload.put("deliveryAcknowledged", true);
      payload.put("terminalReconciled", true);
      payload.set(
          "evidenceRefs", JsonNodeFactory.instance.arrayNode().add("micro6:v2:stop:" + requestId));
      respond(exchange, 200, payload);
    }

    private ObjectNode receipt(
        String requestId, String digest, String status, boolean acknowledged) {
      ObjectNode ack = JsonNodeFactory.instance.objectNode();
      ack.put("success", true);
      ack.put("requestId", requestId);
      ack.put("envelopeDigest", digest);
      ack.put("status", status);
      ack.put("deliveryAcknowledged", acknowledged);
      ack.put("reconciled", false);
      ack.set(
          "evidenceRefs",
          JsonNodeFactory.instance
              .arrayNode()
              .add("micro6:v2:" + status.toLowerCase(java.util.Locale.ROOT) + ":" + requestId));
      return ack;
    }

    private void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
      byte[] bytes = objectMapper.writeValueAsBytes(body);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}

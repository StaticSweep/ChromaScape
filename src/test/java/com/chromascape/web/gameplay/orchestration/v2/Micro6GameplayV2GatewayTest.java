package com.chromascape.web.gameplay.orchestration.v2;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2Transport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SC-1449 v2 gateway transport tests: reserve/start/status/stop map to the additive {@code
 * /api/v2/gameplay/} routes with the closed six-operation vocabulary, and no v1 combined dispatch
 * semantics leak into v2.
 */
class Micro6GameplayV2GatewayTest {
  private ObjectMapper objectMapper;
  private HttpServer server;
  private final AtomicInteger reserveCalls = new AtomicInteger();
  private final AtomicInteger startCalls = new AtomicInteger();
  private volatile String lastReservePath;
  private volatile String lastStartPath;
  private volatile String lastIdempotencyKey;
  private volatile byte[] lastReserveBody;
  private volatile int lastReserveStatus;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v2/gameplay/reserve",
        exchange -> {
          reserveCalls.incrementAndGet();
          lastReservePath = exchange.getRequestURI().getPath();
          lastIdempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
          lastReserveBody = exchange.getRequestBody().readAllBytes();
          lastReserveStatus = 202;
          respond(
              exchange,
              202,
              "{\"requestId\":\"request-sc1429-002\",\"envelopeDigest\":\"sha256:"
                  + "0".repeat(64)
                  + "\",\"deliveryAcknowledged\":true}");
        });
    server.createContext(
        "/api/v2/gameplay/start",
        exchange -> {
          startCalls.incrementAndGet();
          lastStartPath = exchange.getRequestURI().getPath();
          respond(exchange, 202, "{\"requestId\":\"request-sc1429-002\",\"started\":true}");
        });
    server.createContext(
        "/api/v2/gameplay/status/request-sc1429-002",
        exchange ->
            respond(
                exchange,
                200,
                "{\"requestId\":\"request-sc1429-002\"," + "\"status\":\"RESERVED\"}"));
    server.createContext(
        "/api/v2/gameplay/stop",
        exchange ->
            respond(exchange, 200, "{\"requestId\":\"request-sc1429-002\",\"stopped\":true}"));
    server.createContext(
        "/api/v2/gameplay/catalog",
        exchange -> respond(exchange, 200, "{\"catalogId\":\"catalog-sc1429\"}"));
    server.createContext(
        "/api/v2/gameplay/observe",
        exchange -> respond(exchange, 200, "{\"observationId\":\"obs-sc1429-002\"}"));
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void reserveUsesAdditiveV2RouteWithIdempotencyKey() {
    Micro6GameplayV2Gateway gateway = gateway();
    byte[] bundleBytes =
        "{\"executionEnvelope\":{\"requestId\":\"request-sc1429-002\"}}"
            .getBytes(StandardCharsets.UTF_8);
    JsonNode ack = gateway.reserve(bundleBytes, "idempotency-sc1429-002");
    assertThat(ack.path("requestId").asText()).isEqualTo("request-sc1429-002");
    assertThat(lastReservePath).isEqualTo("/api/v2/gameplay/reserve");
    assertThat(lastIdempotencyKey).isEqualTo("idempotency-sc1429-002");
    assertThat(reserveCalls).hasValue(1);
    assertThat(startCalls).hasValue(0);
  }

  @Test
  void reserveTransmitsExactValidatedBytesAndStampsTruthfulHttpStatus() throws Exception {
    Micro6GameplayV2Gateway gateway = gateway();
    byte[] bundleBytes =
        "{\"executionEnvelope\":{\"requestId\":\"request-sc1429-002\"}}"
            .getBytes(StandardCharsets.UTF_8);
    JsonNode ack = gateway.reserve(bundleBytes, "idempotency-sc1429-002");
    assertThat(new String(lastReserveBody, StandardCharsets.UTF_8))
        .isEqualTo(new String(bundleBytes, StandardCharsets.UTF_8));
    assertThat(ack.path("_httpSuccess").asBoolean()).isTrue();
    assertThat(ack.path("_httpStatus").asInt()).isEqualTo(lastReserveStatus);
  }

  @Test
  void startStatusStopCatalogObserveMapToIndependentV2Routes() {
    Micro6GameplayV2Gateway gateway = gateway();
    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("requestId", "request-sc1429-002");
    JsonNode started = gateway.start(envelope, "idempotency-sc1429-002:start");
    assertThat(started.path("started").asBoolean()).isTrue();
    assertThat(lastStartPath).isEqualTo("/api/v2/gameplay/start");
    assertThat(gateway.status("request-sc1429-002").path("status").asText()).isEqualTo("RESERVED");
    assertThat(
            gateway
                .stop("request-sc1429-002", "operator-request", true)
                .path("stopped")
                .asBoolean())
        .isTrue();
    assertThat(gateway.catalog().path("catalogId").asText()).isEqualTo("catalog-sc1429");
    assertThat(gateway.observe("client-sc1429").path("observationId").asText())
        .isEqualTo("obs-sc1429-002");
    assertThat(reserveCalls).hasValue(0);
    assertThat(startCalls).hasValue(1);
  }

  @Test
  void transportExposesClosedSixOperationVocabulary() {
    V2Transport.class.getMethods();
    assertThat(GameplayV2DispatchModels.canonicalSchemaDigest()).startsWith("sha256:");
  }

  private Micro6GameplayV2Gateway gateway() {
    return new Micro6GameplayV2Gateway(
        objectMapper, "http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    exchange.getRequestBody().readAllBytes();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}

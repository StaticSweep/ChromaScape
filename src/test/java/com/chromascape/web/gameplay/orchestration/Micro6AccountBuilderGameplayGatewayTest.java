package com.chromascape.web.gameplay.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Micro6AccountBuilderGameplayGatewayTest {
  private ObjectMapper objectMapper;
  private HttpServer server;
  private AtomicInteger statusRequests;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    statusRequests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/gameplay/dispatch",
        exchange -> respond(exchange, 202, "{\"success\":true,\"entry_id\":17}"));
    server.createContext(
        "/api/v1/status/17",
        exchange -> {
          statusRequests.incrementAndGet();
          respond(
              exchange,
              200,
              "{\"status\":\"COMPLETED\",\"gameplay_verification_outcome\":\"CONFIRMED\"}");
        });
    server.createContext(
        "/api/v1/gameplay/stop",
        exchange ->
            respond(
                exchange,
                200,
                "{\"success\":true,\"outcome\":\"STOPPED\",\"deliveryAcknowledged\":true,\"terminalReconciled\":true,\"evidenceRefs\":[\"micro6:entry:17\"]}"));
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void acceptedDispatchIsNotCalledConfirmedUntilTerminalStatusIsReconciled() throws Exception {
    Fixture fixture = fixture();
    var gateway =
        new Micro6AccountBuilderGameplayGateway(
            objectMapper, "http://127.0.0.1:" + server.getAddress().getPort());

    var result = gateway.dispatch(fixture.envelope(), fixture.authority());

    assertThat(result.outcome()).isEqualTo(Outcome.CONFIRMED);
    assertThat(result.deliveryAcknowledged()).isTrue();
    assertThat(result.terminalReconciled()).isTrue();
    assertThat(result.evidenceRefs()).containsExactly("micro6:entry:17", "micro6:status:completed");
    assertThat(statusRequests).hasValue(1);
  }

  @Test
  void stopUsesTheDedicatedBoundedGameplayEndpoint() throws Exception {
    var gateway =
        new Micro6AccountBuilderGameplayGateway(
            objectMapper, "http://127.0.0.1:" + server.getAddress().getPort());

    var result = gateway.stop("request-sc1429-001", "operator-request", true);

    assertThat(result.outcome()).isEqualTo(Outcome.STOPPED);
    assertThat(result.terminalReconciled()).isTrue();
  }

  private Fixture fixture() throws Exception {
    var root =
        objectMapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
    var envelopeNode =
        (com.fasterxml.jackson.databind.node.ObjectNode) root.path("executionEnvelope").deepCopy();
    envelopeNode.put("deadline", Instant.now().plusSeconds(5).toString());
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelopeNode.path("budgets"))
        .put("maxElapsedMs", 2000);
    ExecutionEnvelope envelope = objectMapper.treeToValue(envelopeNode, ExecutionEnvelope.class);
    HumanAuthority authority =
        new HumanAuthority(
            "sc-1429/v1",
            envelope.authorityId(),
            "HUMAN",
            "test-operator",
            Instant.now().minusSeconds(10),
            Instant.now().plusSeconds(300),
            new CanonicalGameplayJson(objectMapper).digest(envelope),
            root.at("/expectedDigests/approvedScope").asText(),
            true,
            "hmac-sha256:" + "0".repeat(64));
    return new Fixture(envelope, authority);
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    exchange.getRequestBody().readAllBytes();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private record Fixture(ExecutionEnvelope envelope, HumanAuthority authority) {}
}

package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Micro6V2AccountBuilderOrchestrationGatewayTest {
  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

  private ObjectMapper objectMapper;
  private CanonicalGameplayJson canonicalJson;
  private HttpServer server;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    canonicalJson = new CanonicalGameplayJson(objectMapper);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v2/gameplay/catalog",
        exchange ->
            respond(
                exchange,
                200,
                "{\"contractVersion\":\"sc-1429/v2\",\"enabled\":true,\"buildId\":\"build-v1\","
                    + "\"catalogHash\":\"sha256:"
                    + "c".repeat(64)
                    + "\","
                    + "\"capabilityDigest\":\"sha256:"
                    + "a".repeat(64)
                    + "\","
                    + "\"observationRevision\":7,\"taskRevision\":9}"));
    server.createContext(
        "/api/v2/gameplay/observe",
        exchange ->
            respond(
                exchange,
                200,
                "{\"contractVersion\":\"sc-1429/v2\",\"clientId\":\"client-1\",\"buildId\":\"build-v1\","
                    + "\"catalogHash\":\"sha256:"
                    + "c".repeat(64)
                    + "\","
                    + "\"capabilityDigest\":\"sha256:"
                    + "a".repeat(64)
                    + "\","
                    + "\"observationRevision\":7,\"taskRevision\":9}"));
    server.createContext(
        "/api/v2/gameplay/reserve",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          respond(
              exchange,
              201,
              "{\"success\":true,\"requestId\":\"req-1\",\"status\":\"RESERVED\","
                  + "\"envelopeDigest\":\"sha256:"
                  + "e".repeat(64)
                  + "\","
                  + "\"deliveryAcknowledged\":true,\"terminalReconciled\":false,"
                  + "\"lease\":{\"reservationId\":\"reservation:req-1\",\"generation\":1,\"fence\":1,"
                  + "\"fenceTokenDigest\":\"sha256:"
                  + "d".repeat(64)
                  + "\","
                  + "\"expiresAt\":\"2026-08-30T13:00:00Z\"},"
                  + "\"evidenceRefs\":[\"micro6:v2:reserve:req-1\"]}");
        });
    server.createContext(
        "/api/v2/gameplay/status/req-1",
        exchange ->
            respond(
                exchange,
                200,
                "{\"success\":true,\"requestId\":\"req-1\",\"status\":\"STOPPED\","
                    + "\"envelopeDigest\":\"sha256:"
                    + "e".repeat(64)
                    + "\","
                    + "\"deliveryAcknowledged\":true,\"terminalReconciled\":true,"
                    + "\"evidenceRefs\":[\"micro6:v2:status:req-1\"]}"));
    server.createContext(
        "/api/v2/gameplay/status/req-active",
        exchange ->
            respond(
                exchange,
                200,
                "{\"success\":true,\"requestId\":\"req-active\",\"status\":\"ACTIVE\","
                    + "\"envelopeDigest\":\"sha256:"
                    + "e".repeat(64)
                    + "\","
                    + "\"deliveryAcknowledged\":true,\"terminalReconciled\":false,"
                    + "\"evidenceRefs\":[\"micro6:v2:status:req-active\"]}"));
    server.createContext(
        "/api/v2/gameplay/stop",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          respond(
              exchange,
              200,
              "{\"success\":true,\"requestId\":\"req-1\",\"status\":\"STOPPED\","
                  + "\"envelopeDigest\":\"sha256:"
                  + "e".repeat(64)
                  + "\","
                  + "\"deliveryAcknowledged\":true,\"terminalReconciled\":true,"
                  + "\"evidenceRefs\":[\"micro6:v2:stop:req-1\"]}");
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void capabilitiesMapMicro6V2IdentityWithoutLossyFallback() {
    var gateway = gateway();
    CapabilitySnapshot snapshot = gateway.capabilities(request());

    assertThat(snapshot.buildId()).isEqualTo("build-v1");
    assertThat(snapshot.catalogDigest()).isEqualTo("sha256:" + "c".repeat(64));
    assertThat(snapshot.capabilityDigest()).isEqualTo("sha256:" + "a".repeat(64));
    assertThat(snapshot.observationRevision()).isEqualTo(7);
    assertThat(snapshot.taskRevision()).isEqualTo(9);
    assertThat(snapshot.liveDeliveryReady()).isTrue();
  }

  @Test
  void reserveStatusAndStopMapToTypedReceipts() {
    var gateway = gateway();
    OrchestrationRequest request = request();
    String envelopeDigest = "sha256:" + "e".repeat(64);

    GatewayReceipt reserved = gateway.reserve(request, envelopeDigest);
    assertThat(reserved.state()).isEqualTo(State.RESERVED);
    assertThat(reserved.failureCode()).isEqualTo(FailureCode.NONE);
    assertThat(reserved.lease()).isNotNull();
    assertThat(reserved.lease().reservationId()).isEqualTo("reservation:req-1");
    assertThat(reserved.lease().generation()).isEqualTo(1);
    assertThat(reserved.lease().fence()).isEqualTo(1);
    assertThat(reserved.lease().fenceTokenDigest()).isEqualTo("sha256:" + "d".repeat(64));
    assertThat(reserved.lease().expiresAt()).isEqualTo(NOW.plusSeconds(3600));

    GatewayCorrelation correlation =
        new GatewayCorrelation(
            "sc-1431/v1",
            "orch-1",
            "req-1",
            "idem-1",
            envelopeDigest,
            AccountBuilderOrchestrationModels.Mode.BOOTSTRAP_RECORDING,
            "PROVIDER:CLAUDE",
            "FISHING_TRAINING",
            "auth-1",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64),
            1,
            1,
            NOW.plusSeconds(300));
    GatewayReceipt status = gateway.status(correlation, null);
    assertThat(status.state()).isEqualTo(State.STOPPED);

    GatewayReceipt stopped = gateway.stop(correlation, null, "kill-switch:operator", true);
    assertThat(stopped.state()).isEqualTo(State.STOPPED);
  }

  @Test
  void unknownWireStateFailsClosedToReconciliation() {
    var gateway = gateway();
    assertThat(State.NEEDS_RECONCILIATION.terminal()).isTrue();
    assertThat(FailureCode.DELIVERY_UNCERTAIN).isNotEqualTo(FailureCode.NONE);
  }

  @Test
  void awaitTerminalReturnsProvenTerminalReceiptWhileWithinDeadline() {
    var gateway = gateway();
    GatewayReceipt terminal =
        gateway.awaitTerminal(
            correlation("req-1", "sha256:" + "e".repeat(64)), null, NOW.plusSeconds(5));
    assertThat(terminal.state()).isEqualTo(State.STOPPED);
    assertThat(terminal.terminalReconciled()).isTrue();
  }

  @Test
  void awaitTerminalAtDeadlineIsReconciliationOnlyWhenStillNonterminal() {
    var gateway = gateway();
    GatewayReceipt uncertain =
        gateway.awaitTerminal(
            correlation("req-active", "sha256:" + "e".repeat(64)), null, NOW.minusSeconds(1));
    assertThat(uncertain.state()).isEqualTo(State.NEEDS_RECONCILIATION);
    assertThat(uncertain.failureCode()).isEqualTo(FailureCode.DELIVERY_UNCERTAIN);
    assertThat(uncertain.terminalReconciled()).isFalse();
  }

  @Test
  void reserveMissingRequestIdFailsClosed() {
    reconfigure(
        "/api/v2/gameplay/reserve",
        successReserve("", envelopeOk(), "\"evidenceRefs\":[\"micro6:v2:reserve:req-1\"]"));
    var gateway = gateway();
    assertThatThrownBy(() -> gateway.reserve(request(), "sha256:" + "e".repeat(64)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("requestId");
  }

  @Test
  void reserveBlankRequestIdFailsClosed() {
    reconfigure(
        "/api/v2/gameplay/reserve",
        successReserve(
            "\"requestId\":\"   \",",
            envelopeOk(),
            "\"evidenceRefs\":[\"micro6:v2:reserve:req-1\"]"));
    var gateway = gateway();
    assertThatThrownBy(() -> gateway.reserve(request(), "sha256:" + "e".repeat(64)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("requestId");
  }

  @Test
  void reserveMismatchedRequestIdFailsClosed() {
    reconfigure(
        "/api/v2/gameplay/reserve",
        successReserve(
            "\"requestId\":\"req-other\",",
            envelopeOk(),
            "\"evidenceRefs\":[\"micro6:v2:reserve:req-other\"]"));
    var gateway = gateway();
    assertThatThrownBy(() -> gateway.reserve(request(), "sha256:" + "e".repeat(64)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void reserveMissingEnvelopeDigestFailsClosed() {
    reconfigure(
        "/api/v2/gameplay/reserve",
        successReserve(
            "\"requestId\":\"req-1\",", "", "\"evidenceRefs\":[\"micro6:v2:reserve:req-1\"]"));
    var gateway = gateway();
    assertThatThrownBy(() -> gateway.reserve(request(), "sha256:" + "e".repeat(64)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("envelopeDigest");
  }

  @Test
  void reserveBlankEnvelopeDigestFailsClosed() {
    reconfigure(
        "/api/v2/gameplay/reserve",
        successReserve(
            "\"requestId\":\"req-1\",",
            "\"envelopeDigest\":\"  \",",
            "\"evidenceRefs\":[\"micro6:v2:reserve:req-1\"]"));
    var gateway = gateway();
    assertThatThrownBy(() -> gateway.reserve(request(), "sha256:" + "e".repeat(64)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("envelopeDigest");
  }

  @Test
  void reserveMismatchedEnvelopeDigestFailsClosed() {
    reconfigure(
        "/api/v2/gameplay/reserve",
        successReserve(
            "\"requestId\":\"req-1\",",
            "\"envelopeDigest\":\"sha256:" + "f".repeat(64) + "\",",
            "\"evidenceRefs\":[\"micro6:v2:reserve:req-1\"]"));
    var gateway = gateway();
    assertThatThrownBy(() -> gateway.reserve(request(), "sha256:" + "e".repeat(64)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void reserveMissingWorkerEvidenceFailsClosed() {
    reconfigure(
        "/api/v2/gameplay/reserve",
        successReserve("\"requestId\":\"req-1\",", envelopeOk(), "\"evidenceRefs\":[]"));
    var gateway = gateway();
    assertThatThrownBy(() -> gateway.reserve(request(), "sha256:" + "e".repeat(64)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("evidence");
  }

  @Test
  void statusMissingIdentityFailsClosed() {
    reconfigure(
        "/api/v2/gameplay/status/req-1",
        "{\"success\":true,\"status\":\"STOPPED\","
            + envelopeOk()
            + "\"deliveryAcknowledged\":true,\"terminalReconciled\":true,"
            + "\"evidenceRefs\":[\"micro6:v2:status:req-1\"]}");
    var gateway = gateway();
    assertThatThrownBy(() -> gateway.status(correlation("req-1", "sha256:" + "e".repeat(64)), null))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("requestId");
  }

  @Test
  void failureResponseWithoutIdentityMapsToReconciliation() {
    reconfigure(
        "/api/v2/gameplay/reserve",
        "{\"success\":false,\"status\":\"NEEDS_RECONCILIATION\","
            + "\"failureCode\":\"DELIVERY_UNCERTAIN\","
            + "\"deliveryAcknowledged\":false,\"terminalReconciled\":false}");
    var gateway = gateway();
    GatewayReceipt receipt = gateway.reserve(request(), "sha256:" + "e".repeat(64));
    assertThat(receipt.state()).isEqualTo(State.NEEDS_RECONCILIATION);
    assertThat(receipt.evidenceRefs()).isEmpty();
  }

  private void reconfigure(String path, String body) {
    server.removeContext(path);
    server.createContext(
        path,
        exchange -> {
          try {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, body);
          } catch (java.io.IOException exception) {
            throw new RuntimeException(exception);
          }
        });
  }

  private String successReserve(
      String requestIdFragment, String envelopeFragment, String evidence) {
    return "{\"success\":true,"
        + requestIdFragment
        + "\"status\":\"RESERVED\","
        + envelopeFragment
        + "\"deliveryAcknowledged\":true,\"terminalReconciled\":false,"
        + "\"lease\":{\"reservationId\":\"reservation:req-1\",\"generation\":1,\"fence\":1,"
        + "\"fenceTokenDigest\":\"sha256:"
        + "d".repeat(64)
        + "\","
        + "\"expiresAt\":\"2026-08-30T13:00:00Z\"},"
        + evidence
        + "}";
  }

  private String envelopeOk() {
    return "\"envelopeDigest\":\"sha256:" + "e".repeat(64) + "\",";
  }

  private GatewayCorrelation correlation(String requestId, String envelopeDigest) {
    return new GatewayCorrelation(
        "sc-1431/v1",
        "orch-1",
        requestId,
        "idem-1",
        envelopeDigest,
        AccountBuilderOrchestrationModels.Mode.BOOTSTRAP_RECORDING,
        "PROVIDER:CLAUDE",
        "FISHING_TRAINING",
        "auth-1",
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64),
        1,
        1,
        NOW.plusSeconds(300));
  }

  private Micro6V2AccountBuilderOrchestrationGateway gateway() {
    return new Micro6V2AccountBuilderOrchestrationGateway(
        objectMapper,
        canonicalJson,
        "http://127.0.0.1:" + server.getAddress().getPort(),
        3000,
        10000,
        300000,
        1000,
        true,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private OrchestrationRequest request() {
    return new OrchestrationRequest(
        "orch-1",
        "req-1",
        "idem-1",
        "sha256:" + "f".repeat(64),
        AccountBuilderOrchestrationModels.Mode.BOOTSTRAP_RECORDING,
        "PROVIDER:CLAUDE",
        null,
        null,
        null,
        null,
        null,
        NOW.plusSeconds(300));
  }

  private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}

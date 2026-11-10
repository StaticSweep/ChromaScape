package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EnvelopeRules;
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
  /** Mutable so a test can script the catalog's per-task attestation. */
  private volatile String catalogBody;
  /** Exact bytes the gateway transmitted on the last reserve call. */
  private volatile String lastReserveBody;
  /** Exact bytes the gateway transmitted on the last start call. */
  private volatile String lastStartBody;
  /** Idempotency key the gateway presented on the last start call. */
  private volatile String lastStartIdempotencyKey;
  /** Mutable so a test can script malformed worker evidence on the status route. */
  private volatile String badStatusBody;
  /** Scripted per-request delay for the slow-status deadline test. */
  private volatile long statusDelayMillis;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    canonicalJson = new CanonicalGameplayJson(objectMapper);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    catalogBody =
        "{\"contractVersion\":\"sc-1429/v2\",\"enabled\":true,\"buildId\":\"build-v1\","
            + "\"catalogHash\":\"sha256:"
            + "c".repeat(64)
            + "\","
            + "\"capabilityDigest\":\"sha256:"
            + "a".repeat(64)
            + "\","
            + "\"tasks\":[{\"taskKey\":\"FISHING_TRAINING\",\"eligible\":true,"
            + "\"schemaDigest\":\""
            + com.chromascape.web.gameplay.contract.v2.GameplayV2EnvelopeRules.canonicalSchemaDigest()
            + "\","
            + "\"minimumBuildId\":\"build-v1\",\"denialReasons\":[]}],"
            + "\"observationRevision\":7,\"taskRevision\":9}";
    server.createContext(
        "/api/v2/gameplay/catalog",
        exchange -> respond(exchange, 200, catalogBody));
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
          lastReserveBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
                  + "\"receiptId\":\"micro6:v2:reserve:req-1\","
                  + "\"evidenceRefs\":[\"micro6:v2:reserve:req-1\"]}");
        });
    server.createContext(
        "/api/v2/gameplay/start",
        exchange -> {
          lastStartBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          lastStartIdempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
          // Micro6 echoes the start idempotency key and recomputes the envelope digest from the
          // transmitted bytes; the scripted response attests the same digest the request carries.
          exchange.getResponseHeaders().set("Idempotency-Key", "idem-1:start");
          respond(
              exchange,
              200,
              "{\"success\":true,\"requestId\":\"req-1\",\"status\":\"ACTIVE\","
                  + "\"envelopeDigest\":\"sha256:"
                  + "e".repeat(64)
                  + "\","
                  + "\"deliveryAcknowledged\":true,\"terminalReconciled\":false,"
                  + "\"lease\":{\"reservationId\":\"reservation:req-1\",\"generation\":1,"
                  + "\"fence\":1,\"fenceTokenDigest\":\"sha256:"
                  + "d".repeat(64)
                  + "\","
                  + "\"expiresAt\":\"2026-08-30T13:00:00Z\"},"
                  + "\"receiptId\":\"micro6:v2:start:req-1\","
                  + "\"evidenceRefs\":[\"micro6:v2:start:req-1\"]}");
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
        "/api/v2/gameplay/status/req-slow",
        exchange -> {
          try {
            Thread.sleep(statusDelayMillis);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          respond(
              exchange,
              200,
              "{\"success\":true,\"requestId\":\"req-slow\",\"status\":\"ACTIVE\","
                  + "\"envelopeDigest\":\"sha256:"
                  + "e".repeat(64)
                  + "\",\"deliveryAcknowledged\":true,\"terminalReconciled\":false,"
                  + "\"evidenceRefs\":[\"micro6:v2:status:req-slow\"]}");
        });
    server.createContext(
        "/api/v2/gameplay/status/req-bad",
        exchange -> respond(exchange, 200, badStatusBody));
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
    // The capability entry must attest the canonical v2 tool-schema digest for the requested task
    // key, never the catalog hash.
    assertThat(snapshot.tasks()).hasSize(1);
    assertThat(snapshot.tasks().get(0).taskKey()).isEqualTo("FISHING_TRAINING");
    assertThat(snapshot.tasks().get(0).schemaDigest())
        .isEqualTo(GameplayV2EnvelopeRules.canonicalSchemaDigest());
    assertThat(snapshot.tasks().get(0).schemaDigest()).isNotEqualTo(snapshot.catalogDigest());
  }

  @Test
  void capabilitiesWithoutRequestEnvelopeFailClosed() {
    var gateway = gateway();
    OrchestrationRequest noEnvelope =
        new OrchestrationRequest(
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
    assertThat(gateway.capabilities(noEnvelope).liveDeliveryReady()).isFalse();
  }

  @Test
  void capabilitiesWrongClientFailsClosed() {
    reconfigure(
        "/api/v2/gameplay/observe",
        "{\"contractVersion\":\"sc-1429/v2\",\"clientId\":\"client-other\",\"buildId\":\"build-v1\","
            + "\"catalogHash\":\"sha256:"
            + "c".repeat(64)
            + "\","
            + "\"capabilityDigest\":\"sha256:"
            + "a".repeat(64)
            + "\","
            + "\"observationRevision\":7,\"taskRevision\":9}");
    assertThat(gateway().capabilities(request()).liveDeliveryReady()).isFalse();
  }

  @Test
  void capabilitiesDisagreeingCatalogFailsClosed() {
    reconfigure(
        "/api/v2/gameplay/observe",
        "{\"contractVersion\":\"sc-1429/v2\",\"clientId\":\"client-1\",\"buildId\":\"build-v1\","
            + "\"catalogHash\":\"sha256:"
            + "b".repeat(64)
            + "\","
            + "\"capabilityDigest\":\"sha256:"
            + "a".repeat(64)
            + "\","
            + "\"observationRevision\":7,\"taskRevision\":9}");
    assertThat(gateway().capabilities(request()).liveDeliveryReady()).isFalse();
  }

  @Test
  void stopRecordsTheRequestedKillSwitchMode() {
    var gateway = gateway();
    GatewayCorrelation correlation = correlation("req-1", "sha256:" + "e".repeat(64));
    assertThat(gateway.stop(correlation, null, "kill-switch:human", true).killSwitch()).isTrue();
    assertThat(gateway.stop(correlation, null, "operator:stop", false).killSwitch()).isFalse();
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

  /**
   * The bootstrap reserve body must be the complete SC-1429/v2 bundle transmitted verbatim. Micro6's
   * {@code GameplayEnvelopeV2Gate} requires ten objects and rejects the legacy five-key shape with
   * {@code INVALID_BUNDLE}, so a five-key transmission can never reserve. The captured bytes are
   * written to {@code build/sc1498/bootstrap-reserve-body.json} so the Micro6 side can run them
   * through the real gate.
   */
  @Test
  void reserveTransmitsTheFrozenBundleVerbatim() throws Exception {
    var gateway = gateway();
    byte[] frozen = frozenBundleBytes();
    gateway.reserve(deliveryRequest(frozen), "sha256:" + "e".repeat(64));

    assertThat(lastReserveBody).isNotNull();
    // Capture the exact transmitted bytes first: the Micro6 cross-boundary test consumes them,
    // and the artifact must exist even while this assertion is still failing.
    java.nio.file.Path captured =
        java.nio.file.Path.of("build", "sc1498", "bootstrap-reserve-body.json");
    java.nio.file.Files.createDirectories(captured.getParent());
    java.nio.file.Files.writeString(captured, lastReserveBody);
    assertThat(lastReserveBody.getBytes(StandardCharsets.UTF_8)).isEqualTo(frozen);
    com.fasterxml.jackson.databind.JsonNode body = objectMapper.readTree(lastReserveBody);
    java.util.List<String> members = new java.util.ArrayList<>();
    body.fieldNames().forEachRemaining(members::add);
    assertThat(members)
        .containsExactlyInAnyOrder(
            "executionEnvelope",
            "catalogSnapshot",
            "semanticObservation",
            "planningInput",
            "eligibilityResult",
            "providerDecision",
            "goalPlan",
            "semanticAction",
            "approvedScope",
            "authority");
    assertThat(body.path("executionEnvelope").path("contractVersion").asText())
        .isEqualTo("sc-1429/v2");
    assertThat(body.path("executionEnvelope").path("mode").asText()).isEqualTo("LIVE_TASK");
  }

  /**
   * A delivery request without a frozen bundle is a proven pre-submission denial. Falling back to a
   * partial payload would consume authority and then be refused by Micro6, which is exactly the
   * defect this adapter must never reintroduce.
   */
  @Test
  void reserveWithoutAFrozenBundleFailsClosedBeforeAnyWireCall() {
    var gateway = gateway();
    assertThatThrownBy(
            () -> gateway.reserve(requestWithoutFrozenBundle(), "sha256:" + "e".repeat(64)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("frozen SC-1429/v2 bundle");
    assertThat(lastReserveBody).isNull();
  }

  @Test
  void reserveWithAMismatchedFrozenEnvelopeDigestFailsClosed() {
    var gateway = gateway();
    OrchestrationRequest delivery = deliveryRequest(frozenBundleBytesUnchecked());
    assertThatThrownBy(() -> gateway.reserve(delivery, "sha256:" + "f".repeat(64)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("envelope digest");
    assertThat(lastReserveBody).isNull();
  }

  /**
   * Start is the envelope-only contract Micro6 implements: it recomputes the envelope digest from
   * the transmitted bytes and compares it to the reservation. Sending a re-serialisation of the v1
   * carrier record would therefore be a 409, so the gateway must transmit the approved envelope
   * exactly as it appears in the frozen bundle.
   */
  @Test
  void dispatchTransmitsTheApprovedExecutionEnvelopeVerbatim() throws Exception {
    var gateway = gateway();
    byte[] frozen = frozenBundleBytes();
    GatewayReceipt receipt =
        gateway.dispatch(
            deliveryRequest(frozen),
            "sha256:" + "e".repeat(64),
            new AccountBuilderOrchestrationModels.Lease(
                "reservation:req-1", 1, 1, "sha256:" + "d".repeat(64), NOW.plusSeconds(3600)));

    assertThat(receipt.state()).isEqualTo(State.ACTIVE);
    assertThat(receipt.failureCode()).isEqualTo(FailureCode.NONE);
    assertThat(receipt.lease()).isNotNull();
    assertThat(lastStartIdempotencyKey).isEqualTo("idem-1:start");

    com.fasterxml.jackson.databind.JsonNode body = objectMapper.readTree(lastStartBody);
    java.util.List<String> members = new java.util.ArrayList<>();
    body.fieldNames().forEachRemaining(members::add);
    assertThat(members).as("start accepts exactly the envelope member").containsExactly("envelope");
    com.fasterxml.jackson.databind.JsonNode approved =
        objectMapper.readTree(frozen).path("executionEnvelope");
    assertThat(body.path("envelope"))
        .as("the transmitted envelope is the approved one, not the carrier record")
        .isEqualTo(approved);
    assertThat(body.path("envelope").path("mode").asText())
        .as("a v1 carrier projection would have dropped the v2-only mode member")
        .isEqualTo("LIVE_TASK");
    assertThat(body.path("envelope").path("contractVersion").asText()).isEqualTo("sc-1429/v2");
  }

  /** A bundle that is not JSON can never be the approved envelope, so start must not be attempted. */
  @Test
  void dispatchWithUnparseableFrozenBundleFailsClosedBeforeAnyWireCall() {
    var gateway = gateway();
    OrchestrationRequest request =
        deliveryRequest("not-json".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                gateway.dispatch(
                    request,
                    "sha256:" + "e".repeat(64),
                    new AccountBuilderOrchestrationModels.Lease(
                        "reservation:req-1",
                        1,
                        1,
                        "sha256:" + "d".repeat(64),
                        NOW.plusSeconds(3600))))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .hasMessageContaining("not valid JSON");
    assertThat(lastStartBody).isNull();
  }

  /**
   * Micro6's failure vocabulary is wider than this adapter's enum. An unmapped denial must keep the
   * verbatim code as durable provenance rather than collapsing silently into a generic
   * classification, and a code the adapter does understand must not gain a spurious reference.
   */
  @Test
  void unmappedWireDenialCodeIsPreservedVerbatim() {
    reconfigure(
        "/api/v2/gameplay/reserve",
        "{\"success\":false,\"outcome\":\"DENIED\",\"failureCode\":\"EXECUTOR_QUARANTINED\","
            + "\"message\":\"executor quarantined\",\"deliveryAcknowledged\":false,"
            + "\"terminalReconciled\":true,\"evidenceRefs\":[]}");
    GatewayReceipt unmapped = gateway().reserve(request(), "sha256:" + "e".repeat(64));
    assertThat(unmapped.state()).isEqualTo(State.DENIED);
    assertThat(unmapped.failureCode()).isEqualTo(FailureCode.DELIVERY_UNCERTAIN);
    assertThat(unmapped.evidenceRefs()).containsExactly("micro6:denial:EXECUTOR_QUARANTINED");

    reconfigure(
        "/api/v2/gameplay/reserve",
        "{\"success\":false,\"outcome\":\"DENIED\",\"failureCode\":\"DEADLINE_EXPIRED\","
            + "\"message\":\"deadline expired\",\"deliveryAcknowledged\":false,"
            + "\"terminalReconciled\":true,\"evidenceRefs\":[]}");
    GatewayReceipt mapped = gateway().reserve(request(), "sha256:" + "e".repeat(64));
    assertThat(mapped.failureCode()).isEqualTo(FailureCode.DEADLINE_EXPIRED);
    assertThat(mapped.evidenceRefs())
        .as("a code the adapter classifies needs no extra provenance")
        .isEmpty();
  }

  @Test
  void unknownWireStateFailsClosedToReconciliation() {
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

  @Test
  void theTerminalWaitNeverExceedsItsDeadline() {
    statusDelayMillis = 3000L;
    var gateway =
        new Micro6V2AccountBuilderOrchestrationGateway(
            objectMapper,
            canonicalJson,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            5000,
            10000,
            1000,
            100,
            true,
            Clock.fixed(NOW, ZoneOffset.UTC));
    GatewayCorrelation correlation =
        correlation("req-slow", "sha256:" + "e".repeat(64));

    long started = System.nanoTime();
    GatewayReceipt receipt = gateway.awaitTerminal(correlation, null, NOW.plusMillis(1000));
    long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(receipt.state()).isEqualTo(State.NEEDS_RECONCILIATION);
    // The configured bound, not the per-request timeout or an unconditional final lookup, is the
    // ceiling: a status call at the deadline must not double the wait.
    assertThat(elapsedMillis)
        .as("terminal wait stayed within its bound (elapsed %s ms)", elapsedMillis)
        .isLessThan(2500L);
  }

  @Test
  void malformedWorkerEvidenceIsRejectedAsABoundaryViolation() {
    String digest = "sha256:" + "e".repeat(64);
    // Blank, non-textual, duplicate and empty acknowledged evidence are all boundary violations:
    // none of them may become durable provenance.
    for (String evidence : java.util.List.of("[\"\"]", "[17]", "[\"a\",\"a\"]", "[]")) {
      badStatusBody = successfulStatus(evidence, true);
      assertThatThrownBy(() -> gateway().status(correlation("req-bad", digest), null))
          .as("evidence %s must be rejected", evidence)
          .isInstanceOfSatisfying(
              com.chromascape.web.gameplay.orchestration.accountbuilder
                  .AccountBuilderOrchestrationException.class,
              exception -> assertThat(exception.failureCode()).isEqualTo(FailureCode.BOUNDARY_VIOLATION));
    }
  }

  private String successfulStatus(String evidenceRefs, boolean acknowledged) {
    return "{\"success\":true,\"requestId\":\"req-bad\",\"status\":\"ACTIVE\","
        + "\"envelopeDigest\":\"sha256:"
        + "e".repeat(64)
        + "\",\"deliveryAcknowledged\":"
        + acknowledged
        + ",\"terminalReconciled\":false,\"evidenceRefs\":"
        + evidenceRefs
        + "}";
  }

  @Test
  void anAbsentCatalogTaskIsNeverFabricatedAsEligible() {
    catalogBody = catalogWith("\"tasks\":[]");
    var gateway = gateway();

    CapabilitySnapshot snapshot = gateway.capabilities(request());

    assertThat(snapshot.liveDeliveryReady()).isTrue();
    assertThat(snapshot.tasks()).isEmpty();
    assertThat(snapshot.task("FISHING_TRAINING"))
        .as("an absent task has no capability entry")
        .isNull();
  }

  @Test
  void anIneligibleCatalogTaskKeepsItsAttestedDenial() {
    catalogBody =
        catalogWith(
            "\"tasks\":[{\"taskKey\":\"FISHING_TRAINING\",\"eligible\":false,"
                + "\"schemaDigest\":\""
                + com.chromascape.web.gameplay.contract.v2.GameplayV2EnvelopeRules
                    .canonicalSchemaDigest()
                + "\",\"minimumBuildId\":\"build-v1\","
                + "\"denialReasons\":[\"TASK_MAINTENANCE\"]}]");
    var gateway = gateway();

    CapabilitySnapshot snapshot = gateway.capabilities(request());

    assertThat(snapshot.task("FISHING_TRAINING")).isNotNull();
    assertThat(snapshot.task("FISHING_TRAINING").eligible()).isFalse();
    assertThat(snapshot.task("FISHING_TRAINING").denialReasons())
        .containsExactly("TASK_MAINTENANCE");
  }

  private String catalogWith(String tasksField) {
    return "{\"contractVersion\":\"sc-1429/v2\",\"enabled\":true,\"buildId\":\"build-v1\","
        + "\"catalogHash\":\"sha256:"
        + "c".repeat(64)
        + "\",\"capabilityDigest\":\"sha256:"
        + "a".repeat(64)
        + "\","
        + tasksField
        + ",\"observationRevision\":7,\"taskRevision\":9}";
  }

  @Test
  void attestedReceiptIdentityIsUsedVerbatim() {
    var gateway = gateway();
    GatewayReceipt receipt = gateway.reserve(request(), "sha256:" + "e".repeat(64));
    assertThat(receipt.receiptId()).isEqualTo("micro6:v2:reserve:req-1");
  }

  @Test
  void identicalResponsesReconcileToTheSameReceiptIdentity() {
    var gateway = gateway();
    String digest = "sha256:" + "e".repeat(64);
    GatewayReceipt firstReserve = gateway.reserve(request(), digest);
    GatewayReceipt secondReserve = gateway.reserve(request(), digest);
    assertThat(secondReserve.receiptId())
        .as("an identical response is the same evidence, never a new random receipt")
        .isEqualTo(firstReserve.receiptId());

    GatewayCorrelation correlation = correlation("req-active", digest);
    GatewayReceipt firstStatus = gateway.status(correlation, null);
    GatewayReceipt secondStatus = gateway.status(correlation, null);
    assertThat(secondStatus.receiptId()).isEqualTo(firstStatus.receiptId());
    assertThat(gateway().status(correlation, null).receiptId())
        .as("a fresh gateway instance derives the same receipt identity")
        .isEqualTo(firstStatus.receiptId());
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

  /**
   * A delivery request carrying the frozen ten-object bundle. The envelope digest is the one the
   * scripted Micro6 reserve response attests.
   */
  private OrchestrationRequest deliveryRequest(byte[] frozenBytes) {
    return new OrchestrationRequest(
        "orch-1",
        "req-1",
        "idem-1",
        "sha256:" + "f".repeat(64),
        AccountBuilderOrchestrationModels.Mode.BOOTSTRAP_RECORDING,
        "PROVIDER:CLAUDE",
        null,
        envelope(),
        null,
        null,
        null,
        NOW.plusSeconds(300),
        new AccountBuilderOrchestrationModels.FrozenV2Bundle(
            frozenBytes,
            "sha256:" + "9".repeat(64),
            "sha256:" + "e".repeat(64),
            "sha256:" + "8".repeat(64),
            "sha256:" + "c".repeat(64),
            "sha256:" + "7".repeat(64),
            "sha256:" + "6".repeat(64),
            "sha256:" + "5".repeat(64),
            "sha256:" + "1".repeat(64),
            "sha256:" + "4".repeat(64),
            "auth-1",
            "req-1",
            "idem-1",
            NOW.plusSeconds(300)));
  }

  /** Minimal ten-object sc-1429/v2 bundle; the shape is what the Micro6 gate asserts. */
  private byte[] frozenBundleBytes() throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode bundle = objectMapper.createObjectNode();
    com.fasterxml.jackson.databind.node.ObjectNode envelopeNode =
        bundle.putObject("executionEnvelope");
    envelopeNode.put("contractVersion", "sc-1429/v2");
    envelopeNode.put("mode", "LIVE_TASK");
    envelopeNode.put("requestId", "req-1");
    envelopeNode.put("idempotencyKey", "idem-1");
    envelopeNode.put("taskKey", "FISHING_TRAINING");
    for (String member :
        java.util.List.of(
            "catalogSnapshot",
            "semanticObservation",
            "planningInput",
            "eligibilityResult",
            "providerDecision",
            "goalPlan",
            "semanticAction",
            "approvedScope",
            "authority")) {
      bundle.putObject(member).put("contractVersion", "sc-1429/v2");
    }
    return objectMapper.writeValueAsBytes(bundle);
  }

  private OrchestrationRequest request() {
    return deliveryRequest(frozenBundleBytesUnchecked());
  }

  /** A delivery-shaped request with no frozen bundle, used only to prove the fail-closed denial. */
  private OrchestrationRequest requestWithoutFrozenBundle() {
    return new OrchestrationRequest(
        "orch-1",
        "req-1",
        "idem-1",
        "sha256:" + "f".repeat(64),
        AccountBuilderOrchestrationModels.Mode.BOOTSTRAP_RECORDING,
        "PROVIDER:CLAUDE",
        null,
        envelope(),
        null,
        null,
        null,
        NOW.plusSeconds(300));
  }

  private byte[] frozenBundleBytesUnchecked() {
    try {
      return frozenBundleBytes();
    } catch (Exception exception) {
      throw new IllegalStateException("bundle fixture could not be serialized", exception);
    }
  }

  private ExecutionEnvelope envelope() {
    try {
      String json =
          "{\"contractVersion\":\"sc-1429/v1\",\"provider\":\"CLAUDE\",\"modelVersion\":\"model-v1\","
              + "\"decisionId\":\"decision-1\",\"requestId\":\"req-1\",\"idempotencyKey\":\"idem-1\","
              + "\"goalPlanDigest\":\"sha256:"
              + "1".repeat(64)
              + "\",\"taskSkillDigests\":[\"sha256:"
              + "2".repeat(64)
              + "\"],"
              + "\"taskKey\":\"FISHING_TRAINING\",\"settings\":{},"
              + "\"identities\":{\"clientId\":\"client-1\",\"accountId\":\"account-1\","
              + "\"environmentId\":\"LIVE\"},"
              + "\"catalogDigest\":\"sha256:"
              + "c".repeat(64)
              + "\",\"schemaDigest\":\"sha256:"
              + "3".repeat(64)
              + "\","
              + "\"policyDigest\":\"sha256:"
              + "4".repeat(64)
              + "\",\"knowledgeDigest\":\"sha256:"
              + "5".repeat(64)
              + "\","
              + "\"semanticActionDigest\":\"sha256:"
              + "6".repeat(64)
              + "\",\"expectedTransition\":\"CONFIRMED\","
              + "\"verifierId\":\"verifier-1\",\"buildId\":\"build-v1\",\"expectedObservationRevision\":7,"
              + "\"expectedTaskRevision\":9,\"authorityId\":\"auth-1\",\"leaseGeneration\":1,\"fence\":1,"
              + "\"budgets\":{},\"deadline\":\"2026-08-30T12:05:00Z\",\"killSwitchScope\":\"RUN\"}";
      return objectMapper.readValue(json, ExecutionEnvelope.class);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
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

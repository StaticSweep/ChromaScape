package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels;
import com.chromascape.web.gameplay.orchestration.v2.Micro6GameplayV2Gateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Wire-mapping and failure classification for the SC-1449 SC-1431 adapter. */
class Micro6AccountBuilderOrchestrationGatewayTest {
  private static final Instant NOW = Instant.parse("2026-08-30T12:05:00Z");
  private static final String ENVELOPE_DIGEST = "sha256:" + "1".repeat(64);

  private final ObjectMapper mapper =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .findAndAddModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** Canned transport: deterministic responses without HTTP and with failure injection. */
  private static final class StubV2Gateway extends Micro6GameplayV2Gateway {
    JsonNode reserveResponse;
    JsonNode startResponse;
    JsonNode statusResponse;
    JsonNode stopResponse;
    JsonNode catalogResponse;
    RuntimeException reserveFailure;
    int reserveCalls;
    int startCalls;
    int statusCalls;
    String lastStopReason;
    boolean lastStopKillSwitch;

    StubV2Gateway() {
      super(new ObjectMapper(), "http://127.0.0.1:1");
    }

    @Override
    public JsonNode reserve(byte[] bundleBytes, String idempotencyKey) {
      reserveCalls++;
      if (reserveFailure != null) {
        throw reserveFailure;
      }
      return reserveResponse;
    }

    @Override
    public JsonNode start(JsonNode envelope, String idempotencyKey) {
      startCalls++;
      return startResponse;
    }

    @Override
    public JsonNode status(String requestId) {
      statusCalls++;
      return statusResponse;
    }

    @Override
    public JsonNode stop(String requestId, String reason, boolean killSwitch) {
      lastStopReason = reason;
      lastStopKillSwitch = killSwitch;
      return stopResponse;
    }

    @Override
    public JsonNode catalog() {
      return catalogResponse;
    }
  }

  private Micro6AccountBuilderOrchestrationGateway adapter(StubV2Gateway transport) {
    return new Micro6AccountBuilderOrchestrationGateway(
        transport, new CanonicalGameplayJson(mapper), mapper, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private ExecutionEnvelope envelope() {
    var budgets =
        mapper
            .createObjectNode()
            .put("maxActions", 5)
            .put("maxAttempts", 2)
            .put("maxElapsedMs", 30_000);
    var node = mapper.createObjectNode();
    return new ExecutionEnvelope(
        "sc-1429/v1",
        Provider.CODEX,
        "gpt-test",
        "decision-1",
        "request-1",
        "idempotency-1",
        "sha256:" + "a".repeat(64),
        List.of("sha256:" + "b".repeat(64)),
        "FISHING_TRAINING",
        node,
        node,
        "sha256:" + "c".repeat(64),
        "sha256:" + "d".repeat(64),
        "sha256:" + "e".repeat(64),
        "sha256:" + "f".repeat(64),
        "sha256:" + "0".repeat(64),
        "CONFIRMED",
        "verifier-1",
        "build-1",
        2,
        3,
        "authority-1",
        1,
        7,
        budgets,
        NOW.plusSeconds(600),
        "RUN");
  }

  private OrchestrationRequest request() {
    ExecutionEnvelope envelope = envelope();
    ProviderDecision decision =
        new ProviderDecision(
            "sc-1429/v1",
            Provider.CODEX,
            envelope.modelVersion(),
            envelope.decisionId(),
            0.9,
            mapper.createObjectNode().put("kind", "START_FISHING_TASK"),
            List.of());
    HumanAuthority authority =
        new HumanAuthority(
            "sc-1429/v1",
            "authority-1",
            "HUMAN",
            "operator",
            NOW.minusSeconds(60),
            NOW.plusSeconds(600),
            ENVELOPE_DIGEST,
            "sha256:" + "9".repeat(64),
            true,
            "hmac-sha256:" + "0".repeat(64));
    return new OrchestrationRequest(
        "orchestration-1",
        "request-1",
        "idempotency-1",
        "sha256:" + "2".repeat(64),
        Mode.LIVE_TASK,
        "PROVIDER:CODEX",
        decision,
        envelope,
        null,
        authority,
        mapper.createObjectNode().put("revision", 2),
        envelope.deadline());
  }

  private GatewayCorrelation correlation() {
    return new GatewayCorrelation(
        AccountBuilderOrchestrationModels.VERSION,
        "orchestration-1",
        "request-1",
        "idempotency-1",
        ENVELOPE_DIGEST,
        Mode.LIVE_TASK,
        "PROVIDER:CODEX",
        "FISHING_TRAINING",
        "authority-1",
        "sha256:" + "3".repeat(64),
        "sha256:" + "4".repeat(64),
        "sha256:" + "5".repeat(64),
        2,
        3,
        NOW.plusSeconds(600));
  }

  private com.fasterxml.jackson.databind.node.ObjectNode reservedAck() {
    return mapper
        .createObjectNode()
        .put("_httpSuccess", true)
        .put("_httpStatus", 200)
        .put("success", true)
        .put("status", "RESERVED")
        .put("requestId", "request-1")
        .put("envelopeDigest", ENVELOPE_DIGEST)
        .put("reservationId", "reservation-1")
        .put("deliveryAcknowledged", true)
        .set("evidenceRefs", mapper.createArrayNode().add("micro6:reserve:request-1"));
  }

  @Test
  void reserveMapsBoundReceiptWithExactLease() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.reserveResponse = reservedAck();
    GatewayReceipt receipt = adapter(transport).reserve(request(), ENVELOPE_DIGEST);

    assertThat(transport.reserveCalls).isEqualTo(1);
    assertThat(receipt.state()).isEqualTo(State.RESERVED);
    assertThat(receipt.requestId()).isEqualTo("request-1");
    assertThat(receipt.envelopeDigest()).isEqualTo(ENVELOPE_DIGEST);
    Lease lease = receipt.lease();
    assertThat(lease.reservationId()).isEqualTo("reservation-1");
    assertThat(lease.generation()).isEqualTo(1);
    assertThat(lease.fence()).isEqualTo(7);
    assertThat(lease.fenceTokenDigest()).matches("sha256:[0-9a-f]{64}");
  }

  @Test
  void wrongRequestIdentityIsStaleBinding() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.reserveResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode) reservedAck().put("requestId", "other");
    assertThatThrownBy(() -> adapter(transport).reserve(request(), ENVELOPE_DIGEST))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.STALE_BINDING);
  }

  @Test
  void wrongEnvelopeDigestIsFenceMismatch() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.statusResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            reservedAck().put("envelopeDigest", "sha256:" + "8".repeat(64));
    assertThatThrownBy(() -> adapter(transport).status(correlation(), null))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.FENCE_MISMATCH);
  }

  @Test
  void unrecognizedStatusIsInvalidGatewayReceipt() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.statusResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            reservedAck().put("status", "SOMETHING_ELSE");
    assertThatThrownBy(() -> adapter(transport).status(correlation(), null))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
  }

  @Test
  void http2xxWithoutClosedSuccessMemberIsInvalidReceipt() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.statusResponse =
        mapper
            .createObjectNode()
            .put("_httpSuccess", true)
            .put("_httpStatus", 200)
            .put("requestId", "request-1");
    assertThatThrownBy(() -> adapter(transport).status(correlation(), null))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
  }

  @Test
  void connectionFailureBeforeSubmissionIsGatewayUnavailable() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.reserveFailure =
        new GameplayV2DispatchModels.V2TransportException(
            "unreachable", new ConnectException("refused"));
    assertThatThrownBy(() -> adapter(transport).reserve(request(), ENVELOPE_DIGEST))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.GATEWAY_UNAVAILABLE);
  }

  @Test
  void timeoutAfterPossibleReserveIsDeliveryUncertain() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.reserveFailure =
        new GameplayV2DispatchModels.V2TransportException(
            "timeout", new HttpTimeoutException("timed out"));
    assertThatThrownBy(() -> adapter(transport).reserve(request(), ENVELOPE_DIGEST))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.DELIVERY_UNCERTAIN);
  }

  @Test
  void reconcileReservationIsLookupOnlyAndNeverReserves() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.statusResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode) reservedAck().put("status", "RESERVED");
    GatewayReceipt receipt = adapter(transport).reconcileReservation(correlation());

    assertThat(transport.reserveCalls).isZero();
    assertThat(transport.startCalls).isZero();
    assertThat(transport.statusCalls).isEqualTo(1);
    assertThat(receipt.phase()).isEqualTo("RESERVATION_RECONCILIATION");
    assertThat(receipt.state()).isEqualTo(State.RESERVED);
  }

  @Test
  void stopMapsMicro6StoppedToPersistedStop() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.stopResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            reservedAck().put("status", "STOPPED").put("deliveryAcknowledged", true);
    GatewayReceipt receipt =
        adapter(transport).stop(correlation(), null, "operator requested", true);

    assertThat(transport.lastStopReason).isEqualTo("operator requested");
    assertThat(transport.lastStopKillSwitch).isTrue();
    assertThat(receipt.state()).isEqualTo(State.STOPPED);
    assertThat(receipt.terminalReconciled()).isTrue();
  }

  @Test
  void completedMicro6StatusMapsToConfirmedTerminalEvidence() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.statusResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode) reservedAck().put("status", "COMPLETED");
    GatewayReceipt receipt = adapter(transport).status(correlation(), null);
    assertThat(receipt.state()).isEqualTo(State.CONFIRMED);
    assertThat(receipt.terminalReconciled()).isTrue();
  }

  @Test
  void readinessStaysFalseUntilTheCatalogRouteCheckPasses() {
    StubV2Gateway broken = new StubV2Gateway();
    broken.catalogResponse =
        mapper.createObjectNode().put("_httpSuccess", false).put("_httpStatus", 503);
    CapabilitySnapshot brokenSnapshot = adapter(broken).capabilities();
    assertThat(brokenSnapshot.liveDeliveryReady()).isFalse();

    StubV2Gateway healthy = new StubV2Gateway();
    var healthyTasks = mapper.createArrayNode();
    healthyTasks.addObject().put("taskKey", "FISHING_TRAINING").put("eligible", true);
    healthy.catalogResponse =
        mapper
            .createObjectNode()
            .put("_httpSuccess", true)
            .put("_httpStatus", 200)
            .put("catalogDigest", "sha256:" + "c".repeat(64))
            .put("buildId", "build-1")
            .set("tasks", healthyTasks);
    CapabilitySnapshot ready = adapter(healthy).capabilities();
    assertThat(ready.liveDeliveryReady()).isTrue();
    assertThat(ready.tasks().get(0).taskKey()).isEqualTo("FISHING_TRAINING");
  }
}

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
    JsonNode observeResponse;
    RuntimeException reserveFailure;
    int reserveCalls;
    int startCalls;
    int statusCalls;
    int observeCalls;
    String lastObservedClientId;
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

    @Override
    public JsonNode observe(String clientId) {
      observeCalls++;
      lastObservedClientId = clientId;
      return observeResponse;
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
    var node = mapper.createObjectNode().put("clientId", "client-1");
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
        .put("leaseGeneration", 1)
        .put("fence", 7)
        .put("fenceTokenDigest", "sha256:" + "c".repeat(64))
        .put("leaseExpiresAt", NOW.plusSeconds(120).toString())
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
  void readinessRequiresCatalogAndClientObservationEvidence() {
    StubV2Gateway broken = new StubV2Gateway();
    broken.catalogResponse =
        mapper.createObjectNode().put("_httpSuccess", false).put("_httpStatus", 503);
    CapabilitySnapshot brokenSnapshot = adapter(broken).capabilities(request());
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
    healthy.observeResponse = observationAck();
    CapabilitySnapshot ready = adapter(healthy).capabilities(request());
    assertThat(ready.liveDeliveryReady()).isTrue();
    assertThat(ready.tasks().get(0).taskKey()).isEqualTo("FISHING_TRAINING");
    assertThat(healthy.observeCalls).isEqualTo(1);
    assertThat(healthy.lastObservedClientId).isEqualTo("client-1");
    // The capability digest is the server-issued attestation, not a hash of transport JSON.
    assertThat(ready.capabilityDigest())
        .isEqualTo(observationAck().path("capabilityDigest").asText());

    // Catalog success plus observation failure leaves readiness false.
    StubV2Gateway observationFailure = new StubV2Gateway();
    observationFailure.catalogResponse = healthy.catalogResponse;
    observationFailure.observeResponse =
        mapper.createObjectNode().put("_httpSuccess", false).put("_httpStatus", 503);
    assertThat(adapter(observationFailure).capabilities(request()).liveDeliveryReady()).isFalse();

    // Transport metadata never alters the contract digest.
    StubV2Gateway withMetadata = new StubV2Gateway();
    withMetadata.catalogResponse = healthy.catalogResponse;
    withMetadata.observeResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode) observationAck().put("_httpStatus", 201);
    assertThat(adapter(withMetadata).capabilities(request()).capabilityDigest())
        .isEqualTo(ready.capabilityDigest());

    // A wrong client observation is rejected.
    StubV2Gateway wrongClient = new StubV2Gateway();
    wrongClient.catalogResponse = healthy.catalogResponse;
    wrongClient.observeResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode) observationAck().put("clientId", "other");
    assertThat(adapter(wrongClient).capabilities(request()).liveDeliveryReady()).isFalse();

    // A catalog/observation revision disagreement is rejected.
    StubV2Gateway stale = new StubV2Gateway();
    stale.catalogResponse = healthy.catalogResponse;
    stale.observeResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            observationAck().put("observationRevision", 9);
    assertThat(adapter(stale).capabilities(request()).liveDeliveryReady()).isFalse();

    // A missing server-issued capability digest is rejected.
    StubV2Gateway noDigest = new StubV2Gateway();
    noDigest.catalogResponse = healthy.catalogResponse;
    com.fasterxml.jackson.databind.node.ObjectNode withoutDigest = observationAck();
    withoutDigest.remove("capabilityDigest");
    noDigest.observeResponse = withoutDigest;
    assertThat(adapter(noDigest).capabilities(request()).liveDeliveryReady()).isFalse();
  }

  private com.fasterxml.jackson.databind.node.ObjectNode observationAck() {
    return mapper
        .createObjectNode()
        .put("_httpSuccess", true)
        .put("_httpStatus", 200)
        .put("clientId", "client-1")
        .put("catalogDigest", "sha256:" + "c".repeat(64))
        .put("buildId", "build-1")
        .put("capabilityDigest", "sha256:" + "9".repeat(64))
        .put("observationRevision", -1)
        .put("taskRevision", -1);
  }

  @Test
  void missingAuthoritativeLeaseFieldsAreRejectedWithoutSynthesis() {
    assertThat(reserveFailureCode(node -> node.remove("reservationId")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(reserveFailureCode(node -> node.remove("fenceTokenDigest")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(reserveFailureCode(node -> node.put("fenceTokenDigest", "not-a-digest")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(reserveFailureCode(node -> node.remove("leaseGeneration")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(reserveFailureCode(node -> node.remove("fence")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(reserveFailureCode(node -> node.remove("leaseExpiresAt")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(reserveFailureCode(node -> node.put("leaseExpiresAt", "not-a-timestamp")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
  }

  @Test
  void mismatchingLeaseIdentityFailsClosed() {
    assertThat(reserveFailureCode(node -> node.put("leaseGeneration", 2)))
        .isEqualTo(FailureCode.FENCE_MISMATCH);
    assertThat(reserveFailureCode(node -> node.put("fence", 8)))
        .isEqualTo(FailureCode.FENCE_MISMATCH);
  }

  @Test
  void alreadyExpiredLeaseIsRejectedBeforePersistence() {
    assertThat(
            reserveFailureCode(node -> node.put("leaseExpiresAt", NOW.minusSeconds(1).toString())))
        .isEqualTo(FailureCode.LEASE_EXPIRED);
  }

  /** Applies one sparse-field mutation to a complete reservation and maps the adapter outcome. */
  private FailureCode reserveFailureCode(
      java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutation) {
    StubV2Gateway transport = new StubV2Gateway();
    com.fasterxml.jackson.databind.node.ObjectNode response = reservedAck();
    mutation.accept(response);
    transport.reserveResponse = response;
    try {
      GatewayReceipt receipt = adapter(transport).reserve(request(), ENVELOPE_DIGEST);
      // A synthesized lease would be visible here; the adapter must never return one.
      return receipt.lease() == null ? FailureCode.NONE : FailureCode.INVALID_GATEWAY_RECEIPT;
    } catch (AccountBuilderOrchestrationException exception) {
      return exception.failureCode();
    }
  }

  /** Applies one sparse-field mutation and maps the adapter status outcome. */
  private FailureCode statusFailureCode(
      java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutation) {
    StubV2Gateway transport = new StubV2Gateway();
    com.fasterxml.jackson.databind.node.ObjectNode response = reservedAck();
    mutation.accept(response);
    transport.statusResponse = response;
    try {
      adapter(transport).status(correlation(), null);
      return FailureCode.NONE;
    } catch (AccountBuilderOrchestrationException exception) {
      return exception.failureCode();
    }
  }

  @Test
  void missingHttpStampsFailClosedOnEveryTrustedShape() {
    assertThat(reserveFailureCode(node -> node.remove("_httpSuccess")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(reserveFailureCode(node -> node.remove("_httpStatus")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(statusFailureCode(node -> node.remove("_httpSuccess")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(statusFailureCode(node -> node.remove("_httpStatus")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
  }

  @Test
  void contradictoryHttpStampsFailClosed() {
    assertThat(reserveFailureCode(node -> node.put("_httpSuccess", true).put("_httpStatus", 500)))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(reserveFailureCode(node -> node.put("_httpSuccess", false).put("_httpStatus", 200)))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(statusFailureCode(node -> node.put("_httpSuccess", true).put("_httpStatus", 503)))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
  }

  @Test
  void semanticSuccessNeverAccompaniesHttpFailure() {
    assertThat(
            reserveFailureCode(
                node ->
                    node.put("_httpSuccess", false)
                        .put("_httpStatus", 500)
                        .put("success", true)
                        .put("status", "RESERVED")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
  }

  @Test
  void unboundRejectionsAreNeverAssociatedWithTheExpectedRequest() {
    assertThat(
            reserveFailureCode(
                node ->
                    node.put("_httpSuccess", false)
                        .put("_httpStatus", 409)
                        .put("success", false)
                        .put("status", "DENIED")
                        .put("failureCode", "INVALID_ENVELOPE")
                        .remove("requestId")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
    assertThat(
            reserveFailureCode(
                node ->
                    node.put("_httpSuccess", false)
                        .put("_httpStatus", 409)
                        .put("success", false)
                        .put("status", "DENIED")
                        .put("failureCode", "INVALID_ENVELOPE")
                        .remove("envelopeDigest")))
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
  }

  @Test
  void mismatchingRejectionIdentityFailsClosed() {
    assertThat(
            reserveFailureCode(
                node ->
                    node.put("_httpSuccess", false)
                        .put("_httpStatus", 409)
                        .put("success", false)
                        .put("status", "DENIED")
                        .put("failureCode", "INVALID_ENVELOPE")
                        .put("requestId", "other")))
        .isEqualTo(FailureCode.STALE_BINDING);
    assertThat(
            reserveFailureCode(
                node ->
                    node.put("_httpSuccess", false)
                        .put("_httpStatus", 409)
                        .put("success", false)
                        .put("status", "DENIED")
                        .put("failureCode", "INVALID_ENVELOPE")
                        .put("envelopeDigest", "sha256:" + "8".repeat(64))))
        .isEqualTo(FailureCode.FENCE_MISMATCH);
  }

  @Test
  void aFullyBoundRejectionRemainsUsableTerminalEvidence() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.statusResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            reservedAck()
                .put("_httpSuccess", false)
                .put("_httpStatus", 409)
                .put("success", false)
                .put("status", "DENIED")
                .put("failureCode", "MODE_NOT_DELIVERABLE");
    GatewayReceipt receipt = adapter(transport).status(correlation(), null);

    assertThat(receipt.state()).isEqualTo(State.DENIED);
    assertThat(receipt.failureCode()).isEqualTo(FailureCode.MODE_NOT_DELIVERABLE);
    assertThat(receipt.terminalReconciled()).isTrue();
  }

  @Test
  void unknownRemoteFailureCodeFailsClosed() {
    StubV2Gateway transport = new StubV2Gateway();
    transport.statusResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            reservedAck()
                .put("_httpSuccess", false)
                .put("_httpStatus", 409)
                .put("success", false)
                .put("failureCode", "TOTALLY_NEW_CODE");
    // An unrecognized remote code is a malformed protocol receipt, never a proven terminal denial
    // that could authorize a retry.
    assertThatThrownBy(() -> adapter(transport).status(correlation(), null))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
  }

  @Test
  void capabilityEvidenceRequiresAffirmativeHttpStampsAndCompleteBodies() {
    // A fully valid exchange is ready, including the 299 upper boundary.
    assertThat(capabilityReady(catalogAck(), observationAck())).isTrue();
    assertThat(capabilityReady(catalogAck().put("_httpStatus", 299), observationAck())).isTrue();

    // Negative or contradictory stamps on otherwise complete valid-looking bodies never prove
    // readiness. false/503 is the exact regression: an equality comparison treated both false
    // sides as agreement and produced liveDeliveryReady=true.
    assertThat(
            capabilityReady(
                catalogAck().put("_httpSuccess", false).put("_httpStatus", 503), observationAck()))
        .isFalse();
    assertThat(
            capabilityReady(
                catalogAck().put("_httpSuccess", false).put("_httpStatus", 200), observationAck()))
        .isFalse();
    assertThat(
            capabilityReady(
                catalogAck().put("_httpSuccess", true).put("_httpStatus", 503), observationAck()))
        .isFalse();
    assertThat(
            capabilityReady(
                catalogAck(), observationAck().put("_httpSuccess", false).put("_httpStatus", 503)))
        .isFalse();

    // Boundaries 199 and 300 are not 2xx.
    assertThat(capabilityReady(catalogAck().put("_httpStatus", 199), observationAck())).isFalse();
    assertThat(capabilityReady(catalogAck().put("_httpStatus", 300), observationAck())).isFalse();

    // Missing or wrongly typed stamps never prove readiness.
    assertThat(capabilityReady(catalogAck().remove("_httpSuccess"), observationAck())).isFalse();
    assertThat(capabilityReady(catalogAck().remove("_httpStatus"), observationAck())).isFalse();
    assertThat(capabilityReady(catalogAck().put("_httpSuccess", "true"), observationAck()))
        .isFalse();
    assertThat(capabilityReady(catalogAck().put("_httpStatus", "200"), observationAck())).isFalse();

    // A mismatched catalog/observation identity is never ready.
    assertThat(
            capabilityReady(
                catalogAck(), observationAck().put("catalogDigest", "sha256:" + "8".repeat(64))))
        .isFalse();
  }

  private boolean capabilityReady(JsonNode catalog, JsonNode observation) {
    StubV2Gateway transport = new StubV2Gateway();
    transport.catalogResponse = catalog;
    transport.observeResponse = observation;
    return adapter(transport).capabilities(request()).liveDeliveryReady();
  }

  private com.fasterxml.jackson.databind.node.ObjectNode catalogAck() {
    var tasks = mapper.createArrayNode();
    tasks.addObject().put("taskKey", "FISHING_TRAINING").put("eligible", true);
    return mapper
        .createObjectNode()
        .put("_httpSuccess", true)
        .put("_httpStatus", 200)
        .put("catalogDigest", "sha256:" + "c".repeat(64))
        .put("buildId", "build-1")
        .set("tasks", tasks);
  }

  @Test
  void nonObjectAndEmptyResponsesFailClosed() {
    StubV2Gateway array = new StubV2Gateway();
    array.statusResponse = mapper.createArrayNode();
    assertThatThrownBy(() -> adapter(array).status(correlation(), null))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);

    StubV2Gateway empty = new StubV2Gateway();
    empty.statusResponse = mapper.createObjectNode();
    assertThatThrownBy(() -> adapter(empty).status(correlation(), null))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.INVALID_GATEWAY_RECEIPT);
  }
}

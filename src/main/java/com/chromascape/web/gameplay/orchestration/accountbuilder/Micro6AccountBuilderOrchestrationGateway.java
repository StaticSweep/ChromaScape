package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.TaskCapability;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2TransportException;
import com.chromascape.web.gameplay.orchestration.v2.Micro6GameplayV2Gateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * SC-1449 adapter for the SC-1431 orchestration gateway boundary. It speaks the closed {@code
 * /api/v2/gameplay/} vocabulary through the reviewed {@link Micro6GameplayV2Gateway} transport and
 * maps every remote response into a bound, verifiable {@link GatewayReceipt}.
 *
 * <p>The adapter never reserves again during reconciliation: {@link #reconcileReservation} is a
 * lookup only. Transport failures are classified fail-safe — a connection failure before a body
 * could have been submitted is {@code GATEWAY_UNAVAILABLE}, while any timeout or ambiguous
 * transport outcome is {@code DELIVERY_UNCERTAIN} and therefore a reconciliation case, never a
 * blind retry.
 */
public final class Micro6AccountBuilderOrchestrationGateway
    implements AccountBuilderOrchestrationGateway {

  private final Micro6GameplayV2Gateway transport;
  private final CanonicalGameplayJson canonicalJson;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public Micro6AccountBuilderOrchestrationGateway(
      Micro6GameplayV2Gateway transport,
      CanonicalGameplayJson canonicalJson,
      ObjectMapper objectMapper,
      Clock clock) {
    this.transport = transport;
    this.canonicalJson = canonicalJson;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public CapabilitySnapshot capabilities() {
    Instant now = clock.instant();
    try {
      JsonNode catalog = transport.catalog();
      return capabilitySnapshot(catalog, now);
    } catch (RuntimeException exception) {
      // Readiness stays false unless the catalog route check passes; a probe failure must never
      // poison a delivery attempt because capabilities are read-only evidence.
      return new CapabilitySnapshot(null, null, null, -1, -1, now, now, false, List.of());
    }
  }

  @Override
  public GatewayReceipt reserve(OrchestrationRequest request, String envelopeDigest) {
    ObjectNode bundle = objectMapper.createObjectNode();
    bundle.set("semanticObservation", request.observation());
    bundle.set(GameplayV2DispatchModels.PROVIDER_DECISION_FIELD, canonicalNode(request.decision()));
    bundle.set("semanticAction", canonicalNode(request.decision().semanticAction()));
    bundle.set("executionEnvelope", canonicalNode(request.envelope()));
    bundle.set("authority", canonicalNode(request.authority()));
    bundle.put("provider", request.envelope().provider().name());
    bundle.put("modelVersion", request.envelope().modelVersion());
    bundle.put("orchestrationId", request.orchestrationId());
    bundle.put("sourceType", request.sourceType());
    bundle.put("now", clock.instant().toString());
    byte[] bytes;
    try {
      bytes = objectMapper.writeValueAsBytes(bundle);
    } catch (IOException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INTERNAL_FAILURE, "Reserve bundle could not be serialized");
    }
    JsonNode response =
        transmit(() -> transport.reserve(bytes, request.envelope().idempotencyKey()));
    return receipt(
        response, request.requestId(), envelopeDigest, "RESERVATION", leaseFrom(response, request));
  }

  @Override
  public GatewayReceipt dispatch(OrchestrationRequest request, String envelopeDigest, Lease lease) {
    JsonNode response =
        transmit(
            () ->
                transport.start(
                    canonicalNode(request.envelope()), request.envelope().idempotencyKey()));
    return receipt(response, request.requestId(), envelopeDigest, "DISPATCH", lease);
  }

  @Override
  public GatewayReceipt status(GatewayCorrelation correlation, Lease lease) {
    JsonNode response = transmit(() -> transport.status(correlation.requestId()));
    return receipt(
        response, correlation.requestId(), correlation.envelopeDigest(), "STATUS", lease);
  }

  @Override
  public GatewayReceipt stop(GatewayCorrelation correlation, Lease lease, String reason) {
    JsonNode response =
        transmit(
            () -> transport.stop(correlation.requestId(), reason == null ? "" : reason, false));
    return receipt(response, correlation.requestId(), correlation.envelopeDigest(), "STOP", lease);
  }

  @Override
  public GatewayReceipt reconcile(GatewayCorrelation correlation, Lease lease) {
    // Reconciliation is a lookup; it never reserves or dispatches.
    JsonNode response = transmit(() -> transport.status(correlation.requestId()));
    return receipt(
        response, correlation.requestId(), correlation.envelopeDigest(), "RECONCILIATION", lease);
  }

  @Override
  public GatewayReceipt reconcileReservation(GatewayCorrelation correlation) {
    // Lookup only: an authority-consumed orchestration without a lease must never reserve again.
    JsonNode response = transmit(() -> transport.status(correlation.requestId()));
    return receipt(
        response,
        correlation.requestId(),
        correlation.envelopeDigest(),
        "RESERVATION_RECONCILIATION",
        null);
  }

  private JsonNode canonicalNode(Object value) {
    return value == null ? null : objectMapper.valueToTree(value);
  }

  private JsonNode transmit(java.util.function.Supplier<JsonNode> call) {
    try {
      return call.get();
    } catch (V2TransportException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof ConnectException) {
        // The connection never opened, so no request body could have been submitted.
        throw new AccountBuilderOrchestrationException(
            FailureCode.GATEWAY_UNAVAILABLE, "Micro6 v2 route is unreachable", exception);
      }
      if (cause instanceof HttpTimeoutException || cause instanceof IOException) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.DELIVERY_UNCERTAIN,
            "Micro6 v2 delivery outcome is unknown after submission was possible",
            exception);
      }
      throw new AccountBuilderOrchestrationException(
          FailureCode.DELIVERY_UNCERTAIN, "Micro6 v2 transport outcome is unknown", exception);
    }
  }

  private GatewayReceipt receipt(
      JsonNode response,
      String expectedRequestId,
      String expectedEnvelopeDigest,
      String phase,
      Lease lease) {
    if (response == null || !response.isObject()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT, "Micro6 v2 response is not a JSON object");
    }
    if (response.has("_httpSuccess") && !response.path("_httpSuccess").asBoolean()) {
      // A negative HTTP status with a structured body is a stable rejection; without one it is an
      // invalid receipt that can never be trusted as terminal evidence.
      if (response.path("success").isBoolean()
          && !response.path("success").asBoolean()
          && response.path("failureCode").asText(null) != null) {
        return boundRejection(response, expectedRequestId, expectedEnvelopeDigest, phase);
      }
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT, "Micro6 v2 response reported HTTP failure");
    }
    boolean httpStamped = response.has("_httpSuccess");
    if (httpStamped && !response.path("success").isBoolean()) {
      // HTTP 2xx without a closed success member can never be treated as semantic success.
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT,
          "Micro6 v2 receipt is missing the closed success member");
    }
    String requestId = response.path("requestId").asText(null);
    String envelopeDigest = response.path("envelopeDigest").asText(null);
    if (!expectedRequestId.equals(requestId)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Micro6 v2 receipt request id does not match");
    }
    if (!expectedEnvelopeDigest.equals(envelopeDigest)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.FENCE_MISMATCH, "Micro6 v2 receipt envelope digest does not match");
    }
    String status = response.path("status").asText(null);
    State state = mapState(status);
    if (state == null) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT, "Micro6 v2 receipt status is not recognized");
    }
    FailureCode failureCode = mapFailureCode(response.path("failureCode").asText(null), state);
    boolean deliveryAcknowledged = response.path("deliveryAcknowledged").asBoolean(false);
    boolean terminalReconciled = state.provenTerminal();
    return new GatewayReceipt(
        requestId,
        envelopeDigest,
        phase,
        state,
        failureCode,
        deliveryAcknowledged || terminalReconciled,
        terminalReconciled,
        lease,
        evidenceRefs(response),
        clock.instant(),
        receiptId(response));
  }

  private GatewayReceipt boundRejection(
      JsonNode response, String expectedRequestId, String expectedEnvelopeDigest, String phase) {
    String requestId = response.path("requestId").asText(null);
    if (requestId != null && !expectedRequestId.equals(requestId)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Micro6 v2 rejection request id does not match");
    }
    String envelopeDigest = response.path("envelopeDigest").asText(null);
    if (envelopeDigest != null && !expectedEnvelopeDigest.equals(envelopeDigest)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.FENCE_MISMATCH, "Micro6 v2 rejection envelope digest does not match");
    }
    String status = response.path("status").asText(null);
    State state = mapState(status);
    if (state == null || !state.provenTerminal()) {
      state = State.DENIED;
    }
    FailureCode failureCode = mapFailureCode(response.path("failureCode").asText(null), state);
    return new GatewayReceipt(
        expectedRequestId,
        expectedEnvelopeDigest,
        phase,
        state,
        failureCode,
        false,
        true,
        null,
        evidenceRefs(response),
        clock.instant(),
        receiptId(response));
  }

  private CapabilitySnapshot capabilitySnapshot(JsonNode catalog, Instant now) {
    boolean httpOk = !catalog.has("_httpSuccess") || catalog.path("_httpSuccess").asBoolean(false);
    String catalogDigest = catalog.path("catalogDigest").asText(null);
    String buildId = catalog.path("buildId").asText(null);
    List<TaskCapability> tasks = new ArrayList<>();
    JsonNode taskNodes = catalog.path("tasks");
    if (taskNodes.isArray()) {
      taskNodes.forEach(
          task ->
              tasks.add(
                  new TaskCapability(
                      task.path("taskKey").asText(null),
                      task.path("eligible").asBoolean(false),
                      task.path("schemaDigest").asText(null),
                      task.path("minimumBuildId").asText(null),
                      List.of())));
    }
    // Readiness requires a truthful HTTP stamp, a digest-bearing catalog and a build identity.
    boolean ready = httpOk && catalogDigest != null && buildId != null && !tasks.isEmpty();
    return new CapabilitySnapshot(
        catalogDigest,
        ready ? canonicalJson.digest(catalog) : null,
        buildId,
        catalog.path("observationRevision").asLong(-1),
        catalog.path("taskRevision").asLong(-1),
        now,
        now.plus(Duration.ofSeconds(30)),
        ready,
        tasks);
  }

  private Lease leaseFrom(JsonNode response, OrchestrationRequest request) {
    if (!response.path("success").asBoolean(false)) {
      return null;
    }
    String reservationId = response.path("reservationId").asText(null);
    if (reservationId == null || reservationId.isBlank()) {
      reservationId = request.requestId();
    }
    String fenceTokenDigest = response.path("fenceTokenDigest").asText(null);
    if (fenceTokenDigest == null || !fenceTokenDigest.matches("sha256:[0-9a-f]{64}")) {
      fenceTokenDigest = digestOf(response);
    }
    return new Lease(
        reservationId,
        request.envelope().leaseGeneration(),
        request.envelope().fence(),
        fenceTokenDigest,
        clock.instant().plus(Duration.ofSeconds(120)));
  }

  private State mapState(String status) {
    if (status == null) {
      return null;
    }
    return switch (status) {
      case "RESERVED" -> State.RESERVED;
      case "STARTING", "DELIVERY_PENDING", "DELIVERED" -> State.ACTIVE;
      case "ACTIVE" -> State.ACTIVE;
      case "STOPPING" -> State.ACTIVE;
      case "STOPPED", "CANCELLED" -> State.STOPPED;
      case "COMPLETED", "CONFIRMED" -> State.CONFIRMED;
      case "REFUTED" -> State.REFUTED;
      case "UNVERIFIABLE" -> State.UNVERIFIABLE;
      case "FAILED" -> State.FAILED;
      case "RECEIVED", "VALIDATED", "AUTHORIZED", "NEEDS_RECONCILIATION" -> null;
      default -> null;
    };
  }

  private FailureCode mapFailureCode(String remoteCode, State state) {
    if (remoteCode == null || remoteCode.isBlank() || "NONE".equals(remoteCode)) {
      return state == State.DENIED ? FailureCode.DISPATCH_REJECTED : FailureCode.NONE;
    }
    return switch (remoteCode) {
      case "FENCE_MISMATCH" -> FailureCode.FENCE_MISMATCH;
      case "LEASE_EXPIRED" -> FailureCode.LEASE_EXPIRED;
      case "DEADLINE_EXPIRED" -> FailureCode.DEADLINE_EXPIRED;
      case "AUTHORITY_EXPIRED" -> FailureCode.AUTHORITY_EXPIRED;
      case "AUTHORITY_CONSUMED",
          "AUTHORITY_MISSING",
          "AUTHORITY_FORBIDDEN",
          "AUTHORITY_INVALID" -> FailureCode.AUTHORITY_ALREADY_USED;
      case "MODE_NOT_DELIVERABLE" -> FailureCode.MODE_NOT_DELIVERABLE;
      case "LIVE_DISABLED", "RUNTIME_DISABLED" -> FailureCode.RUNTIME_DISABLED;
      case "VERSION_MISMATCH",
          "INVALID_BUNDLE",
          "INVALID_ENVELOPE",
          "INVALID_RECEIPT",
          "UNKNOWN_REQUEST" -> FailureCode.INVALID_GATEWAY_RECEIPT;
      default -> State.DENIED == state ? FailureCode.DISPATCH_REJECTED : FailureCode.NONE;
    };
  }

  private List<String> evidenceRefs(JsonNode response) {
    JsonNode refs = response.path("evidenceRefs");
    if (!refs.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    refs.forEach(ref -> values.add(ref.asText()));
    return List.copyOf(values);
  }

  private String receiptId(JsonNode response) {
    String receiptId = response.path("receiptId").asText(null);
    return receiptId == null || receiptId.isBlank() ? digestOf(response) : receiptId;
  }

  private String digestOf(JsonNode node) {
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(node);
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INTERNAL_FAILURE, "Receipt digest could not be computed", exception);
    }
  }
}

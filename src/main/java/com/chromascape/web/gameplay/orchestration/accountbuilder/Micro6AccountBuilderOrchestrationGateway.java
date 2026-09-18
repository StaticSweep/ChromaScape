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

  private static final String DIGEST_PATTERN = "sha256:[0-9a-f]{64}";

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
  public CapabilitySnapshot capabilities(OrchestrationRequest request) {
    Instant now = clock.instant();
    try {
      JsonNode catalog = transport.catalog();
      String clientId = clientId(request);
      JsonNode observation = clientId == null ? null : transport.observe(clientId);
      return capabilitySnapshot(catalog, observation, request, now);
    } catch (RuntimeException exception) {
      // Readiness stays false unless both the catalog and the client observation routes pass; a
      // probe failure must never poison a delivery attempt because capabilities are read-only
      // evidence and never reserve, dispatch or mutate state.
      return new CapabilitySnapshot(null, null, null, -1, -1, now, now, false, List.of());
    }
  }

  /** The validated request identities carry the Micro6 client identity for observation. */
  private String clientId(OrchestrationRequest request) {
    if (request == null || request.envelope() == null || request.envelope().identities() == null) {
      return null;
    }
    String clientId = request.envelope().identities().path("clientId").asText(null);
    return clientId == null || clientId.isBlank() ? null : clientId;
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
        response,
        request.requestId(),
        envelopeDigest,
        "RESERVATION",
        leaseFrom(response, request),
        false);
  }

  @Override
  public GatewayReceipt dispatch(OrchestrationRequest request, String envelopeDigest, Lease lease) {
    JsonNode response =
        transmit(
            () ->
                transport.start(
                    canonicalNode(request.envelope()), request.envelope().idempotencyKey()));
    return receipt(response, request.requestId(), envelopeDigest, "DISPATCH", lease, false);
  }

  @Override
  public GatewayReceipt status(GatewayCorrelation correlation, Lease lease) {
    JsonNode response = transmit(() -> transport.status(correlation.requestId()));
    return receipt(
        response, correlation.requestId(), correlation.envelopeDigest(), "STATUS", lease, false);
  }

  @Override
  public GatewayReceipt stop(
      GatewayCorrelation correlation, Lease lease, String reason, boolean killSwitch) {
    JsonNode response =
        transmit(
            () ->
                transport.stop(correlation.requestId(), reason == null ? "" : reason, killSwitch));
    return receipt(
        response, correlation.requestId(), correlation.envelopeDigest(), "STOP", lease, killSwitch);
  }

  @Override
  public GatewayReceipt reconcile(GatewayCorrelation correlation, Lease lease) {
    // Reconciliation is a lookup; it never reserves or dispatches.
    JsonNode response = transmit(() -> transport.status(correlation.requestId()));
    return receipt(
        response,
        correlation.requestId(),
        correlation.envelopeDigest(),
        "RECONCILIATION",
        lease,
        false);
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
        null,
        false);
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
      Lease lease,
      boolean killSwitch) {
    if (response == null || !response.isObject()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT, "Micro6 v2 response is not a JSON object");
    }
    // Every trusted response carries both HTTP stamps. A response without them is an invalid
    // receipt that can never be trusted as success, denial or terminal evidence.
    if (!response.path("_httpSuccess").isBoolean()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT, "Micro6 v2 response is missing _httpSuccess");
    }
    boolean httpSuccess = response.path("_httpSuccess").asBoolean();
    if (!response.path("_httpStatus").isIntegralNumber()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT, "Micro6 v2 response is missing _httpStatus");
    }
    int httpStatus = response.path("_httpStatus").asInt();
    if (httpSuccess != (httpStatus >= 200 && httpStatus < 300)) {
      // HTTP stamps must agree: a success stamp with a non-2xx status (or the inverse) is an
      // invalid receipt and never a usable evidence boundary.
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT, "Micro6 v2 HTTP stamps contradict each other");
    }
    if (!httpSuccess) {
      // A negative HTTP status with a structured body is a stable rejection; without one it is an
      // invalid receipt that can never be trusted as terminal evidence.
      if (!response.path("success").isBoolean()
          || response.path("success").asBoolean()
          || response.path("failureCode").asText(null) == null
          || response.path("failureCode").asText().isBlank()) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.INVALID_GATEWAY_RECEIPT,
            "Micro6 v2 response reported HTTP failure without a stable rejection body");
      }
      return boundRejection(response, expectedRequestId, expectedEnvelopeDigest, phase);
    }
    // HTTP 2xx without a closed success member can never be treated as semantic success.
    if (!response.path("success").isBoolean()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT,
          "Micro6 v2 receipt is missing the closed success member");
    }
    String requestId = response.path("requestId").asText(null);
    String envelopeDigest = response.path("envelopeDigest").asText(null);
    if (requestId == null
        || requestId.isBlank()
        || envelopeDigest == null
        || envelopeDigest.isBlank()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT,
          "Micro6 v2 receipt is missing its request or envelope identity");
    }
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
        receiptId(response),
        killSwitch);
  }

  private GatewayReceipt boundRejection(
      JsonNode response, String expectedRequestId, String expectedEnvelopeDigest, String phase) {
    // A denial is only associated with the expected orchestration when it carries the exact
    // request id and envelope digest. A denial missing either value is an unbound receipt and can
    // never be trusted as terminal evidence for this request.
    String requestId = response.path("requestId").asText(null);
    if (requestId == null || requestId.isBlank()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT,
          "Micro6 v2 rejection is missing its request identity");
    }
    if (!expectedRequestId.equals(requestId)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING, "Micro6 v2 rejection request id does not match");
    }
    String envelopeDigest = response.path("envelopeDigest").asText(null);
    if (envelopeDigest == null || envelopeDigest.isBlank()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT,
          "Micro6 v2 rejection is missing its envelope digest");
    }
    if (!expectedEnvelopeDigest.equals(envelopeDigest)) {
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
        receiptId(response),
        false);
  }

  private CapabilitySnapshot capabilitySnapshot(
      JsonNode catalog, JsonNode observation, OrchestrationRequest request, Instant now) {
    boolean catalogOk = httpStampOk(catalog);
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

    boolean observationOk = observation != null && httpStampOk(observation);
    // Server-issued attestations only: the capability digest is never re-hashed from the whole
    // transport response, so transport metadata such as _httpStatus cannot enter a contract
    // digest.
    String serverCapabilityDigest =
        observationOk ? observation.path("capabilityDigest").asText(null) : null;
    String observationCatalogDigest =
        observationOk ? observation.path("catalogDigest").asText(null) : null;
    String observationBuildId = observationOk ? observation.path("buildId").asText(null) : null;
    String observationClientId =
        observationOk ? observation.path("clientId").asText(null) : clientId(request);
    long observationRevision =
        observationOk ? observation.path("observationRevision").asLong(-1) : -1;
    long taskRevision = observationOk ? observation.path("taskRevision").asLong(-1) : -1;

    boolean serverDigestValid =
        serverCapabilityDigest != null && serverCapabilityDigest.matches(DIGEST_PATTERN);
    boolean agree =
        observationOk
            && serverDigestValid
            && catalogDigest != null
            && catalogDigest.equals(observationCatalogDigest)
            && buildId != null
            && buildId.equals(observationBuildId)
            && observationClientId != null
            && observationClientId.equals(clientId(request))
            && observationRevision == catalog.path("observationRevision").asLong(-1)
            && taskRevision == catalog.path("taskRevision").asLong(-1);

    // Readiness requires truthful HTTP stamps, a digest-bearing catalog, a build identity, task
    // capabilities and a server-issued capability attestation that agrees with the catalog.
    boolean ready = catalogOk && agree && !tasks.isEmpty();
    return new CapabilitySnapshot(
        catalogDigest,
        ready ? serverCapabilityDigest : null,
        buildId,
        observationRevision,
        taskRevision,
        now,
        now.plus(Duration.ofSeconds(30)),
        ready,
        tasks);
  }

  /**
   * Both HTTP stamps must be present and consistent on a trusted preflight response. A response
   * without stamps, with contradictory stamps, or with a failure stamp can never carry capability
   * evidence.
   */
  private boolean httpStampOk(JsonNode response) {
    if (!response.path("_httpSuccess").isBoolean()
        || !response.path("_httpStatus").isIntegralNumber()) {
      return false;
    }
    boolean httpSuccess = response.path("_httpSuccess").asBoolean();
    int httpStatus = response.path("_httpStatus").asInt();
    // Conjunctive: a stamp alone never proves success. Both the boolean success stamp AND a 2xx
    // status are required, so a negative stamp (for example false/503, where both sides of an
    // equality check would be false) can never be accepted as capability evidence.
    return httpSuccess && httpStatus >= 200 && httpStatus < 300;
  }

  /**
   * A successful reservation must carry every authoritative lease field. Nothing is derived from
   * the request or synthesized locally: a sparse or malformed response can never become a trusted
   * lease.
   */
  private Lease leaseFrom(JsonNode response, OrchestrationRequest request) {
    if (!response.path("success").asBoolean(false)) {
      return null;
    }
    String reservationId = response.path("reservationId").asText(null);
    String fenceTokenDigest = response.path("fenceTokenDigest").asText(null);
    JsonNode generationNode = response.path("leaseGeneration");
    JsonNode fenceNode = response.path("fence");
    String leaseExpiresAt = response.path("leaseExpiresAt").asText(null);
    if (reservationId == null
        || reservationId.isBlank()
        || fenceTokenDigest == null
        || !fenceTokenDigest.matches(DIGEST_PATTERN)
        || !generationNode.isIntegralNumber()
        || !fenceNode.isIntegralNumber()
        || leaseExpiresAt == null) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT,
          "Micro6 reservation receipt is missing authoritative lease evidence");
    }
    long generation = generationNode.asLong();
    long fence = fenceNode.asLong();
    Instant expiresAt;
    try {
      expiresAt = Instant.parse(leaseExpiresAt);
    } catch (RuntimeException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT,
          "Micro6 lease expiry is not a valid timestamp",
          exception);
    }
    if (generation != request.envelope().leaseGeneration() || fence != request.envelope().fence()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.FENCE_MISMATCH,
          "Micro6 lease generation or fence does not match the approved request");
    }
    if (!expiresAt.isAfter(clock.instant())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.LEASE_EXPIRED, "Micro6 lease is already expired");
    }
    return new Lease(reservationId, generation, fence, fenceTokenDigest, expiresAt);
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

  /**
   * Stable, one-to-one failure semantics. Missing, malformed, forbidden and consumed authority keep
   * distinct meanings. An unrecognized remote code is a malformed protocol receipt: it is never
   * translated into {@code NONE}, {@code DISPATCH_REJECTED} or any other proven terminal denial
   * that could authorize a retry, and it fails closed with {@code INVALID_GATEWAY_RECEIPT}.
   */
  private FailureCode mapFailureCode(String remoteCode, State state) {
    if (remoteCode == null || remoteCode.isBlank() || "NONE".equals(remoteCode)) {
      return state == State.DENIED ? FailureCode.DISPATCH_REJECTED : FailureCode.NONE;
    }
    return switch (remoteCode) {
      case "FENCE_MISMATCH" -> FailureCode.FENCE_MISMATCH;
      case "LEASE_EXPIRED" -> FailureCode.LEASE_EXPIRED;
      case "DEADLINE_EXPIRED" -> FailureCode.DEADLINE_EXPIRED;
      case "AUTHORITY_CONSUMED" -> FailureCode.AUTHORITY_ALREADY_USED;
      case "AUTHORITY_EXPIRED" -> FailureCode.AUTHORITY_EXPIRED;
      case "AUTHORITY_MISSING", "AUTHORITY_INVALID" -> FailureCode.AUTHORITY_INVALID;
      case "AUTHORITY_FORBIDDEN" -> FailureCode.BOUNDARY_VIOLATION;
      case "MODE_NOT_DELIVERABLE" -> FailureCode.MODE_NOT_DELIVERABLE;
      case "LIVE_DISABLED", "RUNTIME_DISABLED" -> FailureCode.RUNTIME_DISABLED;
      case "VERSION_MISMATCH",
          "INVALID_BUNDLE",
          "INVALID_ENVELOPE",
          "INVALID_RECEIPT",
          "UNKNOWN_REQUEST" -> FailureCode.INVALID_GATEWAY_RECEIPT;
      default -> throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_GATEWAY_RECEIPT,
          "Micro6 v2 receipt carries an unrecognized failure code");
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

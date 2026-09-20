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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * ChromaScape Micro6 v2 adapter for SC-1498.
 *
 * <p>This is the sole production transport for {@code BOOTSTRAP_RECORDING} delivery. It speaks only
 * the additive Micro6 v2 surface ({@code /api/v2/gameplay/catalog}, {@code /observe}, {@code
 * /reserve}, {@code /start}, {@code /status/{requestId}}, {@code /stop}) and maps every Micro6
 * response to a typed SC-1431 receipt without lossy string fallbacks. Unknown wire states fail
 * closed to {@code NEEDS_RECONCILIATION} with {@code retryAllowed=false}; recovery performs
 * lookup/reconciliation only and never redispatches.
 *
 * <p>Revision sourcing: Micro6 v2 {@code /observe} currently attests {@code clientId}, {@code
 * buildId} and {@code catalogHash} but no observation/task revision counter. Revision freshness for
 * SC-1498 is therefore enforced end to end via the persisted SC-1430 decision, envelope,
 * observation and snapshot bindings (see {@code BootstrapProposalAssembler}); the gateway snapshot
 * carries the Micro6-observed catalog/build identity and a live-delivery flag. Native-Windows
 * verification must confirm the catalog/capability digest mapping against real Micro6 observe
 * responses before any live run.
 */
@Component("micro6V2AccountBuilderOrchestrationGateway")
@Primary
public final class Micro6V2AccountBuilderOrchestrationGateway
    implements AccountBuilderOrchestrationGateway {
  private final ObjectMapper objectMapper;
  private final CanonicalGameplayJson canonicalJson;
  private final HttpClient httpClient;
  private final URI baseUri;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final Duration terminalTimeout;
  private final Duration statusPollInterval;
  private final boolean adapterEnabled;
  private final Clock clock;

  @Autowired
  public Micro6V2AccountBuilderOrchestrationGateway(
      ObjectMapper objectMapper,
      CanonicalGameplayJson canonicalJson,
      @Value("${gameplay.bootstrap.micro6-v2-url:http://127.0.0.1:8098}") String baseUrl,
      @Value("${gameplay.bootstrap.micro6-v2-connect-timeout-ms:3000}") long connectTimeoutMs,
      @Value("${gameplay.bootstrap.micro6-v2-request-timeout-ms:10000}") long requestTimeoutMs,
      @Value("${gameplay.bootstrap.micro6-v2-terminal-timeout-ms:300000}") long terminalTimeoutMs,
      @Value("${gameplay.bootstrap.micro6-v2-status-poll-interval-ms:1000}")
          long statusPollIntervalMs,
      @Value("${gameplay.bootstrap.micro6-v2-enabled:false}") boolean adapterEnabled) {
    this(
        objectMapper,
        canonicalJson,
        baseUrl,
        connectTimeoutMs,
        requestTimeoutMs,
        terminalTimeoutMs,
        statusPollIntervalMs,
        adapterEnabled,
        Clock.systemUTC());
  }

  Micro6V2AccountBuilderOrchestrationGateway(
      ObjectMapper objectMapper,
      CanonicalGameplayJson canonicalJson,
      String baseUrl,
      long connectTimeoutMs,
      long requestTimeoutMs,
      long terminalTimeoutMs,
      long statusPollIntervalMs,
      boolean adapterEnabled,
      Clock clock) {
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
    this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
    this.connectTimeout = Duration.ofMillis(Math.max(500, connectTimeoutMs));
    this.requestTimeout = Duration.ofMillis(Math.max(1000, requestTimeoutMs));
    this.terminalTimeout = Duration.ofMillis(Math.max(5000, terminalTimeoutMs));
    this.statusPollInterval =
        Duration.ofMillis(Math.min(30000, Math.max(100, statusPollIntervalMs)));
    this.adapterEnabled = adapterEnabled;
    this.clock = clock;
    this.httpClient = HttpClient.newBuilder().connectTimeout(this.connectTimeout).build();
  }

  @Override
  public CapabilitySnapshot capabilities(OrchestrationRequest request) {
    Instant now = clock.instant();
    if (!adapterEnabled) {
      return unavailable(now);
    }
    try {
      JsonNode catalog = get("api/v2/gameplay/catalog");
      JsonNode observe = get("api/v2/gameplay/observe");
      boolean enabled = catalog.path("enabled").asBoolean(false);
      String buildId = firstText(catalog, "buildId", "build_id");
      if (buildId == null || buildId.isBlank()) {
        buildId = observe.path("buildId").asText(null);
      }
      String catalogHash = observe.path("catalogHash").asText(null);
      if (catalogHash == null || catalogHash.isBlank()) {
        catalogHash = catalog.path("catalogHash").asText(null);
      }
      // Micro6 must attest the live revisions and capability digest from the same snapshot
      // validated by its v2 gate. A missing field fails closed: the gateway never substitutes
      // zero and never copies an unverified value out of the submitted request.
      long observationRevision = revision(observe, "observationRevision");
      if (observationRevision < 0) {
        observationRevision = revision(catalog, "observationRevision");
      }
      long taskRevision = revision(observe, "taskRevision");
      if (taskRevision < 0) {
        taskRevision = revision(catalog, "taskRevision");
      }
      String capabilityDigest = firstPresent(observe, "capabilityDigest");
      if (capabilityDigest == null) {
        capabilityDigest = firstPresent(catalog, "capabilityDigest");
      }
      if (!enabled
          || buildId == null
          || buildId.isBlank()
          || catalogHash == null
          || catalogHash.isBlank()
          || capabilityDigest == null
          || capabilityDigest.isBlank()
          || observationRevision < 0
          || taskRevision < 0) {
        return unavailable(now);
      }
      Instant expiresAt = now.plusSeconds(60);
      return new CapabilitySnapshot(
          catalogHash,
          capabilityDigest,
          buildId,
          observationRevision,
          taskRevision,
          now,
          expiresAt,
          true,
          List.of(new TaskCapability("FISHING_TRAINING", true, catalogHash, buildId, List.of())));
    } catch (RuntimeException exception) {
      return unavailable(now);
    }
  }

  @Override
  public GatewayReceipt reserve(OrchestrationRequest request, String envelopeDigest) {
    Instant at = clock.instant();
    try {
      Map<String, Object> bundle = v2Bundle(request);
      JsonNode response = post("api/v2/gameplay/reserve", bundle, request.idempotencyKey());
      return toReceipt(request.requestId(), envelopeDigest, "RESERVATION", response, at);
    } catch (AccountBuilderOrchestrationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.DELIVERY_UNCERTAIN, "Micro6 v2 reserve could not be reconciled");
    }
  }

  @Override
  public GatewayReceipt dispatch(OrchestrationRequest request, String envelopeDigest, Lease lease) {
    Instant at = clock.instant();
    try {
      Map<String, Object> body =
          Map.of(
              "envelope",
              request.envelope() == null
                  ? objectMapper.createObjectNode()
                  : objectMapper.valueToTree(request.envelope()));
      JsonNode response = post("api/v2/gameplay/start", body, request.idempotencyKey() + ":start");
      GatewayReceipt receipt =
          toReceipt(request.requestId(), envelopeDigest, "DISPATCH", response, at);
      if (lease != null && receipt.lease() != null && !lease.equals(receipt.lease())) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.FENCE_MISMATCH, "Gateway receipt lease does not match the stored lease");
      }
      return receipt;
    } catch (AccountBuilderOrchestrationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.DELIVERY_UNCERTAIN, "Micro6 v2 start could not be reconciled");
    }
  }

  @Override
  public GatewayReceipt status(GatewayCorrelation correlation, Lease lease) {
    Instant at = clock.instant();
    try {
      JsonNode response = get("api/v2/gameplay/status/" + correlation.requestId());
      return toReceipt(
          correlation.requestId(), correlation.envelopeDigest(), "STATUS", response, at);
    } catch (AccountBuilderOrchestrationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.DELIVERY_UNCERTAIN, "Micro6 v2 status could not be reconciled");
    }
  }

  /**
   * Bounded terminal wait for SC-1498 bootstrap delivery. This is a gateway-local helper, not part
   * of the SC-1431 transport interface: it polls {@code status} until the earliest envelope
   * deadline, approved elapsed budget or configured terminal timeout, performs one final lookup at
   * the bound, and returns reconciliation-only uncertainty when still nonterminal. Start is never
   * repeated here.
   */
  public GatewayReceipt awaitTerminal(
      GatewayCorrelation correlation, Lease lease, Instant deadline) {
    Instant now = clock.instant();
    Instant bound = deadline == null ? now.plus(terminalTimeout) : deadline;
    Instant configBound = now.plus(terminalTimeout);
    if (configBound.isBefore(bound)) {
      bound = configBound;
    }
    if (lease != null && lease.expiresAt() != null && lease.expiresAt().isBefore(bound)) {
      bound = lease.expiresAt();
    }
    // Elapsed time is measured with a monotonic clock so a fixed test clock cannot spin this loop
    // while the real wall clock (and the injected clock in production) advances the deadline.
    long startNanos = System.nanoTime();
    long budgetNanos = Math.max(0L, Duration.between(now, bound).toNanos());
    while (System.nanoTime() - startNanos < budgetNanos) {
      GatewayReceipt receipt = status(correlation, lease);
      if (receipt.terminalReconciled() && receipt.state().provenTerminal()) {
        return receipt;
      }
      if (!sleep(statusPollInterval)) {
        // Interrupted: return the last nonterminal receipt as reconciliation-only.
        return nonterminalReceipt(correlation, receipt);
      }
    }
    // One final lookup at the deadline before declaring the run reconciliation-only.
    GatewayReceipt finalReceipt = status(correlation, lease);
    if (finalReceipt.terminalReconciled() && finalReceipt.state().provenTerminal()) {
      return finalReceipt;
    }
    return nonterminalReceipt(correlation, finalReceipt);
  }

  private GatewayReceipt nonterminalReceipt(
      GatewayCorrelation correlation, GatewayReceipt observed) {
    return new GatewayReceipt(
        correlation.requestId(),
        correlation.envelopeDigest(),
        observed.phase(),
        State.NEEDS_RECONCILIATION,
        FailureCode.DELIVERY_UNCERTAIN,
        false,
        false,
        observed.lease(),
        observed.evidenceRefs(),
        clock.instant(),
        observed.receiptId(),
        false);
  }

  private boolean sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public GatewayReceipt stop(
      GatewayCorrelation correlation, Lease lease, String reason, boolean killSwitch) {
    Instant at = clock.instant();
    try {
      // SC-1498 kill-switch propagation: the SC-1431 stop reason carries a "kill-switch:"
      // prefix from the bootstrap operator kill endpoint. The v2 stop contract requires an
      // explicit boolean, so the caller's flag and the prefix are honored together; the durable
      // SC-1431 state machine remains the sole delivery authority.
      boolean kill = killSwitch || (reason != null && reason.startsWith("kill-switch:"));
      Map<String, Object> body =
          Map.of(
              "requestId", correlation.requestId(),
              "reason", reason,
              "killSwitch", kill);
      JsonNode response =
          postWithIdempotency("api/v2/gameplay/stop", body, correlation.requestId() + ":stop");
      return toReceipt(correlation.requestId(), correlation.envelopeDigest(), "STOP", response, at);
    } catch (AccountBuilderOrchestrationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.DELIVERY_UNCERTAIN, "Micro6 v2 stop could not be reconciled");
    }
  }

  @Override
  public GatewayReceipt reconcile(GatewayCorrelation correlation, Lease lease) {
    return status(correlation, lease);
  }

  @Override
  public GatewayReceipt reconcileReservation(GatewayCorrelation correlation) {
    Instant at = clock.instant();
    try {
      JsonNode response = get("api/v2/gameplay/status/" + correlation.requestId());
      if (response.path("success").asBoolean(false)) {
        return toReceipt(
            correlation.requestId(),
            correlation.envelopeDigest(),
            "RESERVATION_RECONCILIATION",
            response,
            at);
      }
      return new GatewayReceipt(
          correlation.requestId(),
          correlation.envelopeDigest(),
          "RESERVATION_RECONCILIATION",
          State.NEEDS_RECONCILIATION,
          FailureCode.DELIVERY_UNCERTAIN,
          false,
          false,
          null,
          List.of(),
          at,
          receiptId(correlation.requestId(), "reconcile-reservation"),
          false);
    } catch (AccountBuilderOrchestrationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.DELIVERY_UNCERTAIN, "Micro6 v2 reservation lookup could not be reconciled");
    }
  }

  private Map<String, Object> v2Bundle(OrchestrationRequest request) {
    return Map.of(
        "executionEnvelope",
            request.envelope() == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(request.envelope()),
        "semanticObservation",
            request.observation() == null ? objectMapper.createObjectNode() : request.observation(),
        "providerDecision",
            request.decision() == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(request.decision()),
        "authority",
            request.authority() == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(request.authority()),
        "snapshotBindings",
            request.bindings() == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(request.bindings()));
  }

  private GatewayReceipt toReceipt(
      String requestId, String envelopeDigest, String phase, JsonNode response, Instant at) {
    boolean success = response.path("success").asBoolean(false);
    if (success) {
      // A successful response must attest its own binding identity. Missing, blank or
      // mismatched values fail closed as a boundary violation: caller-side values are never
      // substituted for Micro6 attestation, and worker evidence is never fabricated.
      String responseRequestId = response.path("requestId").asText(null);
      if (responseRequestId == null || responseRequestId.isBlank()) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.BOUNDARY_VIOLATION,
            "Micro6 v2 successful response is missing its requestId");
      }
      if (!responseRequestId.equals(requestId)) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.BOUNDARY_VIOLATION,
            "Micro6 v2 response requestId does not match the request");
      }
      String responseEnvelopeDigest = response.path("envelopeDigest").asText(null);
      if (responseEnvelopeDigest == null || responseEnvelopeDigest.isBlank()) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.BOUNDARY_VIOLATION,
            "Micro6 v2 successful response is missing its envelopeDigest");
      }
      if (!responseEnvelopeDigest.equals(envelopeDigest)) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.BOUNDARY_VIOLATION,
            "Micro6 v2 response envelopeDigest does not match the request");
      }
      String wireStatus = firstText(response, "status", "outcome");
      State state = mapWireState(wireStatus, true);
      FailureCode failureCode = mapFailureCode(response, state, true);
      boolean acknowledged = response.path("deliveryAcknowledged").asBoolean(true);
      boolean reconciled = response.path("terminalReconciled").asBoolean(state.provenTerminal());
      Lease lease = parseLease(response);
      List<String> evidenceRefs = new ArrayList<>();
      response.path("evidenceRefs").forEach(item -> evidenceRefs.add(item.asText()));
      if (acknowledged && evidenceRefs.isEmpty()) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.BOUNDARY_VIOLATION,
            "Micro6 v2 successful response is missing worker evidence");
      }
      return new GatewayReceipt(
          responseRequestId,
          responseEnvelopeDigest,
          phase,
          state,
          failureCode,
          acknowledged,
          reconciled,
          lease,
          List.copyOf(evidenceRefs),
          at,
          receiptId(responseRequestId, phase),
          false);
    }
    String wireStatus = firstText(response, "status", "outcome");
    State state = mapWireState(wireStatus, success);
    FailureCode failureCode = mapFailureCode(response, state, success);
    boolean acknowledged = response.path("deliveryAcknowledged").asBoolean(success);
    boolean reconciled = response.path("terminalReconciled").asBoolean(state.provenTerminal());
    if (!success && !state.provenTerminal() && state != State.NEEDS_RECONCILIATION) {
      state = State.NEEDS_RECONCILIATION;
      failureCode = FailureCode.DELIVERY_UNCERTAIN;
      acknowledged = false;
      reconciled = false;
    }
    // Failure responses carry no attested identity: the receipt records the attempted
    // correlation so recovery can reconcile it. No worker evidence is fabricated here.
    Lease lease = parseLease(response);
    List<String> evidenceRefs = new ArrayList<>();
    response.path("evidenceRefs").forEach(item -> evidenceRefs.add(item.asText()));
    return new GatewayReceipt(
        requestId,
        envelopeDigest,
        phase,
        state,
        failureCode,
        acknowledged,
        reconciled,
        lease,
        List.copyOf(evidenceRefs),
        at,
        receiptId(requestId, phase),
        false);
  }

  private State mapWireState(String wireStatus, boolean success) {
    if (wireStatus == null) {
      return success ? State.RESERVED : State.NEEDS_RECONCILIATION;
    }
    return switch (wireStatus) {
      case "RESERVED" -> State.RESERVED;
      case "ACTIVE" -> State.ACTIVE;
      case "COMPLETED", "CONFIRMED" -> State.CONFIRMED;
      case "FAILED" -> State.FAILED;
      case "CANCELLED" -> State.DENIED;
      case "STOPPED" -> State.STOPPED;
      case "DENIED",
          "RESERVATION_REJECTED",
          "BUDGET_EXHAUSTED",
          "FENCE_MISMATCH",
          "IDEMPOTENCY_CONFLICT",
          "V2_DISABLED",
          "GATEWAY_UNAVAILABLE" -> State.DENIED;
      case "NEEDS_RECONCILIATION", "DELIVERY_UNCERTAIN", "TRANSPORT_UNCERTAIN" -> State
          .NEEDS_RECONCILIATION;
      case "REFUTED" -> State.REFUTED;
      case "UNVERIFIABLE" -> State.UNVERIFIABLE;
      default -> State.NEEDS_RECONCILIATION;
    };
  }

  private FailureCode mapFailureCode(JsonNode response, State state, boolean success) {
    String code = firstText(response, "failureCode", "failure_code");
    if (code == null) {
      code = firstText(response, "message", "error");
    }
    if (code == null || code.isBlank()) {
      return state == State.NEEDS_RECONCILIATION
          ? FailureCode.DELIVERY_UNCERTAIN
          : FailureCode.NONE;
    }
    try {
      return FailureCode.valueOf(code);
    } catch (IllegalArgumentException ignored) {
      return switch (code) {
        case "V2_DISABLED", "v2 gameplay is disabled" -> FailureCode.GATEWAY_UNAVAILABLE;
        case "method_not_allowed", "invalid_json_body", "BAD_REQUEST" -> FailureCode
            .INVALID_CONTRACT;
        case "UNKNOWN_REQUEST" -> FailureCode.INVALID_CONTRACT;
        case "START_IN_PROGRESS", "STOP_IN_PROGRESS", "TRANSPORT_UNCERTAIN" -> FailureCode
            .DELIVERY_UNCERTAIN;
        case "RESERVATION_NOT_STARTABLE", "RESERVATION_NOT_STOPPABLE" -> FailureCode
            .RESERVATION_REJECTED;
        default -> success ? FailureCode.NONE : FailureCode.DELIVERY_UNCERTAIN;
      };
    }
  }

  private Lease parseLease(JsonNode response) {
    JsonNode lease = response.path("lease");
    if (lease.isMissingNode() || lease.isNull()) {
      JsonNode reservation = response.path("reservation");
      if (!reservation.isMissingNode() && !reservation.isNull()) {
        lease = reservation;
      } else {
        return null;
      }
    }
    try {
      String reservationId = lease.path("reservationId").asText(null);
      String fenceDigest = lease.path("fenceTokenDigest").asText(null);
      if (reservationId == null || fenceDigest == null) {
        return null;
      }
      return new Lease(
          reservationId,
          lease.path("generation").asLong(0),
          lease.path("fence").asLong(0),
          fenceDigest,
          Instant.parse(lease.path("expiresAt").asText()));
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private CapabilitySnapshot unavailable(Instant now) {
    return new CapabilitySnapshot(
        "unavailable", "unavailable", "unavailable", -1, -1, now, now, false, List.of());
  }

  private JsonNode get(String path) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(baseUri.resolve(path)).timeout(requestTimeout).GET().build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Micro6 v2 GET " + path + " returned HTTP " + response.statusCode());
      }
      return objectMapper.readTree(response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Micro6 v2 request interrupted", exception);
    } catch (AccountBuilderOrchestrationException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Micro6 v2 request could not be reconciled", exception);
    }
  }

  private JsonNode post(String path, Object body, String idempotencyKey) {
    return postWithIdempotency(path, body, idempotencyKey);
  }

  private JsonNode postWithIdempotency(String path, Object body, String idempotencyKey) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(baseUri.resolve(path))
              .timeout(requestTimeout)
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", idempotencyKey)
              .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode json = objectMapper.readTree(response.body());
      if (json.isObject()) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) json)
            .put("_httpStatus", response.statusCode());
      }
      return json;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Micro6 v2 request interrupted", exception);
    } catch (Exception exception) {
      throw new IllegalStateException("Micro6 v2 request could not be reconciled", exception);
    }
  }

  private String firstText(JsonNode node, String first, String second) {
    String value = node.path(first).asText(null);
    return value != null ? value : node.path(second).asText(null);
  }

  private String firstPresent(JsonNode node, String field) {
    String value = node.path(field).asText(null);
    return value == null || value.isBlank() ? null : value;
  }

  private long revision(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNumber() ? value.asLong() : -1L;
  }

  private String receiptId(String requestId, String phase) {
    return "v2:"
        + phase
        + ":"
        + requestId
        + ":"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}

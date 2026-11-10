package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FrozenV2Bundle;
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
 * <p>Capability attestation: Micro6 v2 {@code /catalog} and {@code /observe} must agree on {@code
 * buildId}, {@code catalogHash} and {@code capabilityDigest}, and {@code /observe} must attest the
 * observation/task revisions and the client identity. Any missing, disagreeing or wrong-client
 * value fails closed to unavailable rather than being filled from the request. The capability entry
 * for the requested task key carries the canonical v2 tool-schema digest (never the catalog hash)
 * so it matches the envelope's pinned {@code schemaDigest}. Native-Windows verification must
 * confirm the catalog/capability digest mapping against real Micro6 observe responses before any
 * live run.
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
    if (!adapterEnabled || request == null || request.envelope() == null) {
      return unavailable(now);
    }
    try {
      JsonNode catalog = get("api/v2/gameplay/catalog");
      JsonNode observe = get("api/v2/gameplay/observe");
      boolean enabled = catalog.path("enabled").asBoolean(false);
      // Both routes must attest the same coherent Micro6 snapshot. Any missing or disagreeing
      // identity fails closed rather than being filled from the other route or the request.
      String catalogBuildId = firstPresent(catalog, "buildId");
      String observeBuildId = firstPresent(observe, "buildId");
      String catalogHash = firstPresent(catalog, "catalogHash");
      String observeCatalogHash = firstPresent(observe, "catalogHash");
      String capabilityDigest = firstPresent(catalog, "capabilityDigest");
      String observeCapabilityDigest = firstPresent(observe, "capabilityDigest");
      String observedClientId = firstPresent(observe, "clientId");
      String expectedClientId =
          request.envelope().identities() == null
              ? null
              : request.envelope().identities().path("clientId").asText(null);
      // Micro6 must attest the live revisions from the same validated observation snapshot. A
      // missing field fails closed: the gateway never substitutes zero and never copies an
      // unverified value out of the submitted request.
      long observationRevision = revision(observe, "observationRevision");
      long taskRevision = revision(observe, "taskRevision");
      if (!enabled
          || !nonBlank(catalogBuildId)
          || !catalogBuildId.equals(observeBuildId)
          || !nonBlank(catalogHash)
          || !catalogHash.equals(observeCatalogHash)
          || !nonBlank(capabilityDigest)
          || !capabilityDigest.equals(observeCapabilityDigest)
          || !nonBlank(observedClientId)
          || !observedClientId.equals(expectedClientId)
          || observationRevision < 0
          || taskRevision < 0) {
        return unavailable(now);
      }
      String taskKey = request.envelope().taskKey();
      if (taskKey == null || taskKey.isBlank()) {
        return unavailable(now);
      }
      Instant expiresAt = now.plusSeconds(60);
      // Only the catalog's own per-task attestation is eligible. The requested key is never turned
      // into an eligible capability: an absent task yields no entry, and an ineligible entry keeps
      // its attested denial reasons, so the validator refuses both with TASK_INELIGIBLE.
      List<TaskCapability> tasks = catalogTasks(catalog);
      return new CapabilitySnapshot(
          catalogHash,
          capabilityDigest,
          catalogBuildId,
          observationRevision,
          taskRevision,
          now,
          expiresAt,
          true,
          tasks);
    } catch (RuntimeException exception) {
      return unavailable(now);
    }
  }

  @Override
  public GatewayReceipt reserve(OrchestrationRequest request, String envelopeDigest) {
    Instant at = clock.instant();
    // The complete SC-1429/v2 bundle is the only payload Micro6's gate accepts. A delivery request
    // without one is a proven pre-submission denial: the adapter never falls back to a partial
    // five-key body, because that body can only ever be refused after consuming authority.
    FrozenV2Bundle frozen = request == null ? null : request.frozenBundle();
    if (frozen == null || !frozen.usable()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "A complete frozen SC-1429/v2 bundle is required to reserve");
    }
    if (!frozen.envelopeDigest().equals(envelopeDigest)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.STALE_BINDING,
          "Frozen bundle envelope digest does not match the request envelope");
    }
    try {
      JsonNode response =
          postBytes(
              "api/v2/gameplay/reserve", frozen.bytes(), request.idempotencyKey());
      return toReceipt(request.requestId(), envelopeDigest, "RESERVATION", response, at, false);
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
      // Start is the envelope-only contract Micro6 implements: it recomputes the envelope digest
      // from the transmitted bytes and compares it to the reservation. A v2 delivery must therefore
      // send the exact approved envelope, not a re-serialisation of the carrier record.
      JsonNode envelopeNode;
      if (request.frozenBundle() != null && request.frozenBundle().usable()) {
        try {
          envelopeNode =
              objectMapper.readTree(request.frozenBundle().bytes()).path("executionEnvelope");
        } catch (java.io.IOException exception) {
          throw new AccountBuilderOrchestrationException(
              FailureCode.INVALID_CONTRACT, "Frozen bundle bytes are not valid JSON");
        }
      } else {
        envelopeNode =
            request.envelope() == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(request.envelope());
      }
      Map<String, Object> body = Map.of("envelope", envelopeNode);
      JsonNode response = post("api/v2/gameplay/start", body, request.idempotencyKey() + ":start");
      GatewayReceipt receipt =
          toReceipt(request.requestId(), envelopeDigest, "DISPATCH", response, at, false);
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
          correlation.requestId(), correlation.envelopeDigest(), "STATUS", response, at, false);
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
   * deadline, approved elapsed budget or configured terminal timeout. Every status request and poll
   * sleep is capped to the remaining budget, and the wait returns reconciliation-only uncertainty
   * without an extra lookup once the bound is exhausted. Start is never repeated here.
   */
  @Override
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
    GatewayReceipt last = null;
    while (true) {
      long remaining = budgetNanos - (System.nanoTime() - startNanos);
      if (remaining <= 0L) {
        break;
      }
      // Every request is capped to what is left, so one slow status call cannot overrun the bound.
      GatewayReceipt receipt;
      try {
        receipt = statusWithin(correlation, lease, Duration.ofNanos(remaining));
      } catch (AccountBuilderOrchestrationException exception) {
        if (exception.failureCode() == FailureCode.BOUNDARY_VIOLATION) {
          throw exception;
        }
        // An expired or failed lookup at the bound is reconciliation-only, never an overrun.
        break;
      }
      last = receipt;
      if (receipt.terminalReconciled() && receipt.state().provenTerminal()) {
        return receipt;
      }
      remaining = budgetNanos - (System.nanoTime() - startNanos);
      if (remaining <= 0L) {
        break;
      }
      long sleepMillis =
          Math.min(statusPollInterval.toMillis(), Duration.ofNanos(remaining).toMillis());
      if (sleepMillis <= 0L) {
        break;
      }
      if (!sleep(Duration.ofMillis(sleepMillis))) {
        // Interrupted: return the last nonterminal receipt as reconciliation-only.
        return nonterminalReceipt(correlation, receipt);
      }
    }
    // No budget remains: return reconciliation-only without an unconditional extra lookup.
    return nonterminalReceipt(correlation, last);
  }

  /** Status lookup whose HTTP timeout is capped by the caller's remaining terminal budget. */
  private GatewayReceipt statusWithin(
      GatewayCorrelation correlation, Lease lease, Duration timeout) {
    Instant at = clock.instant();
    try {
      JsonNode response =
          getWithTimeout("api/v2/gameplay/status/" + correlation.requestId(), timeout);
      return toReceipt(
          correlation.requestId(), correlation.envelopeDigest(), "STATUS", response, at, false);
    } catch (AccountBuilderOrchestrationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.DELIVERY_UNCERTAIN, "Micro6 v2 status could not be reconciled");
    }
  }

  private GatewayReceipt nonterminalReceipt(
      GatewayCorrelation correlation, GatewayReceipt observed) {
    if (observed == null) {
      return new GatewayReceipt(
          correlation.requestId(),
          correlation.envelopeDigest(),
          "TERMINAL_WAIT",
          State.NEEDS_RECONCILIATION,
          FailureCode.DELIVERY_UNCERTAIN,
          false,
          false,
          null,
          List.of(),
          clock.instant(),
          receiptId(correlation.requestId(), "TERMINAL_WAIT", null),
          false);
    }
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
        observed.killSwitch());
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
      // The durable receipt records the requested kill-switch mode, matching the SC-1431 contract.
      return toReceipt(
          correlation.requestId(), correlation.envelopeDigest(), "STOP", response, at, kill);
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
            at,
            false);
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
          receiptId(correlation.requestId(), "reconcile-reservation", null),
          false);
    } catch (AccountBuilderOrchestrationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.DELIVERY_UNCERTAIN, "Micro6 v2 reservation lookup could not be reconciled");
    }
  }

  /**
   * Transmits the exact validated bundle bytes. The gateway never re-serializes a bundle: the
   * bytes Micro6 validates are the bytes ChromaScape validated and the operator approved.
   */
  private JsonNode postBytes(String path, byte[] body, String idempotencyKey) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(baseUri.resolve(path))
              .timeout(requestTimeout)
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", idempotencyKey)
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
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

  private GatewayReceipt toReceipt(
      String requestId,
      String envelopeDigest,
      String phase,
      JsonNode response,
      Instant at,
      boolean killSwitch) {
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
      List<String> evidenceRefs = parseEvidenceRefs(response, acknowledged);
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
          receiptId(responseRequestId, phase, response),
          killSwitch);
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
    List<String> evidenceRefs = parseEvidenceRefs(response, acknowledged);
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
        receiptId(requestId, phase, response),
        killSwitch);
  }

  /**
   * Reads the catalog's per-task capability entries. Attested eligibility, schema digest, minimum
   * build and denial reasons are carried verbatim; nothing is derived from the request.
   */
  private List<TaskCapability> catalogTasks(JsonNode catalog) {
    List<TaskCapability> tasks = new ArrayList<>();
    JsonNode taskNodes = catalog.path("tasks");
    if (!taskNodes.isArray()) {
      return tasks;
    }
    for (JsonNode task : taskNodes) {
      String key = firstText(task, "taskKey", "task_key");
      if (key == null || key.isBlank()) {
        continue;
      }
      boolean eligible =
          task.has("eligible")
              ? task.path("eligible").asBoolean(false)
              : task.path("runnable").asBoolean(false);
      String schemaDigest = firstText(task, "schemaDigest", "settings_schema_hash");
      String minimumBuildId = firstText(task, "minimumBuildId", "minimum_build_id");
      List<String> denialReasons = new ArrayList<>();
      JsonNode reasons = task.path("denialReasons");
      if (reasons.isArray()) {
        reasons.forEach(reason -> denialReasons.add(reason.asText()));
      } else {
        String reason = firstText(task, "schema_error_reason", "denialReason");
        if (reason != null && !reason.isBlank()) {
          denialReasons.add(reason);
        }
      }
      if (!eligible && denialReasons.isEmpty()) {
        denialReasons.add("TASK_NOT_ELIGIBLE");
      }
      tasks.add(
          new TaskCapability(key, eligible, schemaDigest, minimumBuildId, denialReasons));
    }
    return tasks;
  }

  /**
   * Worker evidence as durable provenance. Every entry must be a nonblank textual reference and no
   * reference may repeat; an acknowledged delivery must attest at least one. Anything else is a
   * boundary violation, so malformed values can never be persisted as trusted evidence.
   */
  private List<String> parseEvidenceRefs(JsonNode response, boolean required) {
    JsonNode refs = response.path("evidenceRefs");
    if (refs.isMissingNode() || refs.isNull()) {
      if (required) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.BOUNDARY_VIOLATION,
            "Micro6 v2 response is missing required worker evidence");
      }
      return List.of();
    }
    if (!refs.isArray()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.BOUNDARY_VIOLATION,
          "Micro6 v2 evidenceRefs must be an array of references");
    }
    List<String> refsList = new ArrayList<>();
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    for (JsonNode item : refs) {
      if (!item.isTextual() || item.asText().isBlank()) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.BOUNDARY_VIOLATION,
            "Micro6 v2 evidenceRefs contains a blank or non-textual reference");
      }
      String reference = item.asText().trim();
      if (!seen.add(reference)) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.BOUNDARY_VIOLATION,
            "Micro6 v2 evidenceRefs contains a duplicate reference");
      }
      refsList.add(reference);
    }
    if (required && refsList.isEmpty()) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.BOUNDARY_VIOLATION,
          "Micro6 v2 acknowledged delivery is missing worker evidence");
    }
    return List.copyOf(refsList);
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
    return getWithTimeout(path, requestTimeout);
  }

  private JsonNode getWithTimeout(String path, Duration timeout) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout).GET().build();
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

  private static boolean nonBlank(String value) {
    return value != null && !value.isBlank();
  }

  private long revision(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNumber() ? value.asLong() : -1L;
  }

  /**
   * Durable receipt identity. An attested remote receipt id wins; otherwise the identity is derived
   * from the canonical response and phase, so reconciling the same response twice yields one
   * receipt and the same evidence manifest instead of a fresh random id each time.
   */
  private String receiptId(String requestId, String phase, JsonNode response) {
    String attested = response == null ? null : response.path("receiptId").asText(null);
    if (attested != null && !attested.isBlank()) {
      return attested;
    }
    java.util.Map<String, Object> canonical = new java.util.LinkedHashMap<>();
    canonical.put("requestId", requestId);
    canonical.put("phase", phase);
    canonical.put("response", response == null ? objectMapper.createObjectNode() : response);
    String digest = canonicalJson.digest(canonical);
    String derived = digest.substring("sha256:".length(), "sha256:".length() + 16);
    return "v2:" + phase + ":" + requestId + ":" + derived;
  }
}

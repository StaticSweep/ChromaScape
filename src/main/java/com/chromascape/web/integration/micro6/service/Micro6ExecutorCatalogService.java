package com.chromascape.web.integration.micro6.service;

import com.chromascape.web.integration.micro6.config.Micro6Config;
import com.chromascape.web.integration.micro6.service.Micro6CatalogV2Contract.CatalogState;
import com.chromascape.web.integration.micro6.service.Micro6CatalogV2Contract.ValidationResult;
import com.chromascape.web.integration.micro6.service.Micro6RuntimeRegistryClient.CatalogFetch;
import com.chromascape.web.integration.micro6.service.Micro6RuntimeRegistryClient.Micro6Runtime;
import com.chromascape.web.integration.micro6.service.Micro6RuntimeRegistryClient.ResponseIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches and caches the Micro6 executor task catalog through the authenticated leeuwbotapi runtime
 * registry.
 *
 * <p>Executors are selected from authenticated runtime candidates; catalog requests carry opaque
 * endpoint references resolved server-side by the registry. Catalog payloads are contract-validated
 * and their canonical hashes are recomputed before caching. A {@code 202 GENERATING}, {@code 503},
 * timeout, disconnect, or malformed response never replaces the last-known-good catalog for an
 * executor.
 *
 * <p>The legacy fixed local URL path remains as an explicit development fallback only, visibly
 * marked {@code DEV_STATIC_ROUTE}.
 */
@Service
public class Micro6ExecutorCatalogService {
  private static final Logger log = LoggerFactory.getLogger(Micro6ExecutorCatalogService.class);
  private static final String DEFAULT_CAPABILITY_VERSION = "micro6-executor/v1";
  private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
  // Mirrors the backend launcher session TTL; a backend-attested observed_at older than this is
  // not a fresh attestation for a selectable runtime.
  private static final Duration DEFAULT_RUNTIME_TTL = Duration.ofMinutes(2);
  // Bounded retry: the initial fetch plus one re-fetch when the catalog identity changes mid-fetch.
  // Acquisition never recurses beyond this fixed limit.
  private static final int MAX_FETCH_ATTEMPTS = 2;

  private final Micro6Config config;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  @org.springframework.beans.factory.annotation.Autowired
  private com.chromascape.web.debug.buildergoal.service.BuilderGoalTopologyGuard topologyGuard;

  private final Micro6RuntimeRegistryClient registryClient;

  private final AtomicReference<JsonNode> cache = new AtomicReference<>();
  private final ConcurrentHashMap<String, BaseUrlCachedCatalog> cacheByBaseUrl =
      new ConcurrentHashMap<>();
  private volatile Instant lastRefresh = Instant.EPOCH;

  private final ConcurrentHashMap<String, CachedCatalog> executorCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> inflightFetches =
      new ConcurrentHashMap<>();

  public Micro6ExecutorCatalogService(Micro6Config config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofMillis(Math.max(1000L, config.getExecutorCatalog().getTimeoutMs())))
            .build();
    Micro6Config.RuntimeSelectionConfig selection = config.getRuntimeSelection();
    this.registryClient =
        new Micro6RuntimeRegistryClient(
            objectMapper,
            selection.getRegistryBaseUrl(),
            selection.getRegistryAuthToken(),
            selection.getRequestTimeoutMs());
  }

  // ------------------------------------------------------------------
  // Authenticated executor selection
  // ------------------------------------------------------------------

  /** Returns authenticated Micro6 executor candidates with safe identity and states. */
  public JsonNode getExecutors() {
    List<Micro6Runtime> runtimes;
    try {
      runtimes = registryClient.listRuntimes();
    } catch (Micro6RuntimeRegistryClient.Micro6RegistryException e) {
      return registryErrorPayload(e, "executors");
    }
    ArrayNode array = objectMapper.createArrayNode();
    for (Micro6Runtime runtime : runtimes) {
      array.add(executorSummary(runtime));
    }
    ObjectNode root = objectMapper.createObjectNode();
    root.put("executors", array);
    root.put("source", "leeuwbotapi_authenticated_runtime_registry");
    root.put("count", array.size());
    return root;
  }

  private ObjectNode executorSummary(Micro6Runtime runtime) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("machine_uuid", runtime.machineUuid());
    node.put("endpoint_ref", runtime.endpointRef());
    node.put("executor_instance_id", runtime.executorInstanceId());
    node.put("client_type", runtime.clientType());
    node.put("catalog_version", runtime.catalogVersion());
    node.put("catalog_hash", runtime.catalogHash());
    node.put("schema_bundle_hash", runtime.schemaBundleHash());
    node.put("runtime_state", runtime.runtimeState());
    node.put("catalog_state", executorCatalogState(runtime));
    node.put(
        "last_heartbeat_at",
        runtime.lastHeartbeatAt() != null ? runtime.lastHeartbeatAt().toString() : null);
    node.put("expires_at", runtime.expiresAt() != null ? runtime.expiresAt().toString() : null);
    node.putNull("advertised_base_url");
    node.put("selectable", "ONLINE".equalsIgnoreCase(runtime.runtimeState()));
    ArrayNode reasons = objectMapper.createArrayNode();
    if (runtime.reasons() != null) {
      runtime.reasons().forEach(reasons::add);
    }
    node.set("reasons", reasons);
    return node;
  }

  private String executorCatalogState(Micro6Runtime runtime) {
    if (!"ONLINE".equalsIgnoreCase(runtime.runtimeState())) {
      return "UNAVAILABLE";
    }
    String endpointRef = runtime.endpointRef();
    if (endpointRef == null) {
      return "UNAVAILABLE";
    }
    CachedCatalog cached = executorCache.get(cacheKey(runtime, "lightweight"));
    if (cached != null && cached.payload() != null) {
      return cached.state();
    }
    return "UNAVAILABLE";
  }

  /**
   * Identity-scoped cache key. Catalog data is isolated by the runtime identity resolved at fetch
   * time, never by {@code endpoint_ref} alone.
   */
  private static String cacheKey(Micro6Runtime runtime, String mode) {
    return runtime.machineUuid()
        + "|"
        + runtime.executorInstanceId()
        + "|"
        + runtime.sessionId()
        + "|"
        + mode;
  }

  // ------------------------------------------------------------------
  // Authenticated catalog fetch
  // ------------------------------------------------------------------

  /**
   * Fetches (or returns a cached) catalog for an executor identified by its opaque endpoint
   * reference. The browser never supplies an executor URL; the reference is resolved server-side by
   * the registry.
   */
  public JsonNode getCatalogForExecutor(String endpointRef, String mode, boolean forceRefresh) {
    String normalizedMode = "full".equalsIgnoreCase(mode) ? "full" : "lightweight";
    String normalizedRef = normalizeOptional(endpointRef);

    if (normalizedRef == null) {
      if (config.getRuntimeSelection().isDevFallbackEnabled()) {
        return devFallbackCatalog(forceRefresh);
      }
      return errorPayload(
          "MISSING_ENDPOINT_REF",
          "An executor endpoint reference is required for authenticated catalog selection");
    }

    // Resolve and verify the authenticated runtime before any catalog work.
    Micro6Runtime runtime;
    try {
      runtime = registryClient.resolveRuntime(normalizedRef);
    } catch (Micro6RuntimeRegistryClient.Micro6RegistryException e) {
      String code = e.reasonCode();
      String reason = "REGISTRY_FAILURE";
      if (code != null && code.contains("404")) {
        reason = "UNKNOWN_ENDPOINT_REF";
      } else if (code != null && (code.contains("403") || code.contains("401"))) {
        reason = "RUNTIME_NOT_AUTHORIZED";
      }
      return unavailablePayload(normalizedRef, normalizedMode, reason, List.of(e.getMessage()));
    }
    if (runtime == null) {
      return unavailablePayload(
          normalizedRef,
          normalizedMode,
          "UNKNOWN_ENDPOINT_REF",
          List.of("unknown or unauthorized endpoint reference"));
    }
    if (!"ONLINE".equalsIgnoreCase(runtime.runtimeState())) {
      return staleOrUnavailable(
          runtime,
          normalizedMode,
          "RUNTIME_NOT_SELECTABLE",
          "RUNTIME_NOT_SELECTABLE",
          runtime.reasons() == null ? List.of("runtime is not selectable") : runtime.reasons());
    }

    // An ONLINE runtime must itself be contract-valid before any catalog work: it must attest a
    // valid catalog hash, and a full-mode runtime claiming AVAILABLE must attest a valid schema
    // bundle hash. A missing hash while ONLINE is a permanent contract error and never satisfies
    // the cache.
    String preContractError = runtimeContractError(runtime, normalizedMode);
    if (preContractError != null) {
      return unavailablePayload(
          normalizedRef, normalizedMode, "CONTRACT_INVALID", List.of(preContractError));
    }

    // An ONLINE runtime must itself attest a valid lifecycle before any cached catalog may be
    // served: a missing catalog_state or schema_bundle_state means ChromaScape cannot bind the
    // catalog to a verifiable lifecycle, and an impossible state pair or a schema-bundle hash
    // violating the lifecycle matrix is a permanent contract violation. Both fail closed rather
    // than satisfying a cache hit.
    RuntimeLifecycleAttestation preLifecycleError = runtimeLifecycleAttestation(runtime);
    if (preLifecycleError != null) {
      return unavailablePayload(
          normalizedRef,
          normalizedMode,
          preLifecycleError.reasonCode(),
          List.of(preLifecycleError.message()));
    }

    // The cache is identity-scoped: only a fresh entry whose identity matches the currently
    // resolved runtime is usable. Full-mode cache entries additionally require the runtime's
    // schema bundle hash to match exactly; a changed bundle invalidates the old full cache while
    // the lightweight cache is unaffected. A runtime that is not catalog-ready may expose a
    // same-identity cached catalog only as STALE, regardless of mode (see cacheMatchesRuntime).
    String key = cacheKey(runtime, normalizedMode);
    CachedCatalog cached = executorCache.get(key);
    if (!forceRefresh
        && cached != null
        && cached.isFresh(config.getExecutorCatalog().getRefreshSeconds())
        && cacheMatchesRuntime(cached, runtime, normalizedMode)) {
      String state = cached.state();
      if (!"READY".equals(runtime.catalogState())
          || ("full".equals(normalizedMode) && !"AVAILABLE".equals(runtime.schemaBundleState()))) {
        // A catalog lifecycle other than READY means the cache is last-known-good, not current.
        // Full catalogs are also stale while their schema bundle is not AVAILABLE. Invalid states
        // were already rejected by runtimeLifecycleAttestation.
        state = "STALE";
      }
      ObjectNode response =
          (ObjectNode)
              catalogResponse(
                  normalizedRef, normalizedMode, state, cached.payload().deepCopy(), List.of());
      response.put("captured_at", cached.fetchedAt().toString());
      if ("STALE".equals(state)) {
        response.put("reason_code", "CATALOG_NOT_READY");
      }
      return response;
    }

    return awaitOrFetch(runtime, normalizedRef, normalizedMode);
  }

  /**
   * Coalesces concurrent requests for the same executor identity+mode onto a single in-flight
   * fetch. A failed winner's entry is removed and acquisition is retried in a loop, so no waiter
   * ever calls {@code doFetch} without owning the map entry.
   */
  private JsonNode awaitOrFetch(
      Micro6Runtime runtime, String normalizedRef, String normalizedMode) {
    String key = cacheKey(runtime, normalizedMode);
    while (true) {
      CompletableFuture<JsonNode> existing = inflightFetches.get(key);
      if (existing != null) {
        try {
          return existing.join();
        } catch (CompletionException failure) {
          // The winner failed and its entry is removed; retry acquisition instead of fetching
          // directly while another caller may still be owning the entry.
          inflightFetches.remove(key, existing);
          continue;
        }
      }

      CompletableFuture<JsonNode> candidate = new CompletableFuture<>();
      if (inflightFetches.putIfAbsent(key, candidate) != null) {
        continue;
      }

      try {
        JsonNode result = doFetch(runtime, normalizedRef, normalizedMode);
        candidate.complete(result);
        return result;
      } catch (Throwable failure) {
        candidate.completeExceptionally(failure);
        throw failure;
      } finally {
        inflightFetches.remove(key, candidate);
      }
    }
  }

  /**
   * Fetches, validates, binds to the confirmed post-fetch runtime identity, and caches a catalog.
   * When the catalog identity changes while the catalog is being fetched, acquisition is retried
   * once (bounded by {@link #MAX_FETCH_ATTEMPTS}) before giving up.
   */
  private JsonNode doFetch(
      Micro6Runtime runtimeBefore, String normalizedRef, String normalizedMode) {
    return doFetchAttempt(runtimeBefore, normalizedRef, normalizedMode, 1);
  }

  /**
   * One bounded fetch-and-verify attempt. Every lifecycle response (200/202/503) is re-resolved and
   * reconciled against the post-fetch runtime before any header is rejected: core ownership
   * (machine/executor/session) is re-confirmed, a selectable runtime must still be contract-valid,
   * then response headers and payload are validated against the post-fetch runtime, never the
   * pre-fetch one. A response whose catalog identity no longer matches the post-fetch runtime is
   * retried once when the catalog changed during the fetch; a permanent mismatch is rejected.
   */
  private JsonNode doFetchAttempt(
      Micro6Runtime runtimeBefore, String normalizedRef, String normalizedMode, int attempt) {
    CatalogFetch fetch;
    try {
      fetch = registryClient.fetchCatalog(normalizedRef, normalizedMode);
    } catch (Micro6RuntimeRegistryClient.Micro6RegistryException e) {
      return staleOrUnavailable(
          runtimeBefore,
          normalizedMode,
          "REGISTRY_FAILURE",
          e.reasonCode(),
          List.of(e.getMessage()));
    }

    boolean lifecycleStatus =
        fetch.statusCode() == 200 || fetch.statusCode() == 202 || fetch.statusCode() == 503;
    Micro6Runtime runtimeAfter;
    if (lifecycleStatus) {
      // Every lifecycle response is re-resolved after the fetch before any header is rejected, so a
      // mid-fetch ownership or contract failure is never masked by an unrelated header problem.
      runtimeAfter = reResolve(normalizedRef);
      if (runtimeAfter == null) {
        return unavailablePayload(
            normalizedRef,
            normalizedMode,
            "RUNTIME_CHANGED",
            List.of("runtime identity could not be re-confirmed after catalog fetch"));
      }
      // Core ownership (machine/executor/session) is a process/ownership replacement, never a
      // retryable catalog change.
      if (coreOwnershipChanged(runtimeBefore, runtimeAfter)) {
        return unavailablePayload(
            normalizedRef,
            normalizedMode,
            "RUNTIME_CHANGED",
            List.of("executor identity changed while the catalog was being fetched"));
      }
      // A post-fetch runtime that is no longer selectable may only serve matching last-known-good
      // data; it never contributes fresh catalog data.
      if (!"ONLINE".equalsIgnoreCase(runtimeAfter.runtimeState())) {
        return staleOrUnavailable(
            runtimeAfter,
            normalizedMode,
            "RUNTIME_NOT_SELECTABLE",
            "RUNTIME_NOT_SELECTABLE",
            runtimeAfter.reasons() == null
                ? List.of("runtime is not selectable after catalog fetch")
                : runtimeAfter.reasons());
      }
      // A selectable post-fetch runtime must itself be contract-valid before any lifecycle status,
      // malformed body, or cache fallback is handled. A missing/invalid attestation is a permanent
      // contract violation and never satisfies stale-or-unavailable data.
      String postContractError = postFetchRuntimeContractError(runtimeAfter, normalizedMode);
      if (postContractError != null) {
        return unavailablePayload(
            normalizedRef, normalizedMode, "CONTRACT_INVALID", List.of(postContractError));
      }
    } else {
      runtimeAfter = runtimeBefore;
    }

    // A post-fetch runtime must itself attest a valid lifecycle before any response header,
    // malformed body, or cache fallback is handled. A missing catalog_state or schema_bundle_state
    // means ChromaScape cannot bind the catalog to a verifiable lifecycle, and an impossible state
    // pair or a schema-bundle hash violating the lifecycle matrix is a permanent contract
    // violation. Both fail closed before any header, status, or stale fallback is considered.
    RuntimeLifecycleAttestation postLifecycleError = runtimeLifecycleAttestation(runtimeAfter);
    if (postLifecycleError != null) {
      return unavailablePayload(
          normalizedRef,
          normalizedMode,
          postLifecycleError.reasonCode(),
          List.of(postLifecycleError.message()));
    }

    // The backend must stamp identity headers on the executor lifecycle statuses (200/202/503);
    // missing required headers are a permanent IDENTITY_UNVERIFIED failure. Other statuses are
    // registry errors and carry no executor identity to verify.
    String presenceError = identityHeaderPresenceError(fetch);
    if (presenceError != null) {
      return unavailablePayload(
          normalizedRef, normalizedMode, "IDENTITY_UNVERIFIED", List.of(presenceError));
    }

    // The backend-attested core identity must agree with the confirmed post-fetch runtime. Non-
    // lifecycle statuses (proxy errors) carry no executor identity to verify.
    if (lifecycleStatus) {
      String coreDrift = coreIdentityValueError(runtimeAfter, fetch);
      if (coreDrift != null) {
        return unavailablePayload(
            normalizedRef, normalizedMode, "IDENTITY_UNVERIFIED", List.of(coreDrift));
      }
    }

    if (fetch.statusCode() == 202) {
      // Generation lifecycle: return GENERATING only when both the post-fetch runtime and the
      // response headers attest generation. A runtime that crossed from a completed state into
      // GENERATING during the fetch (while the response still carried the stale completed headers)
      // is re-acquired once using the confirmed post-fetch runtime; a completed runtimeAfter also
      // causes exactly one re-fetch even when catalog hashes/versions did not change, and a second
      // stale 202 is RUNTIME_CHANGED.
      if (runtimeConfirmsGeneration(runtimeAfter, fetch.identity())) {
        return catalogResponse(
            normalizedRef,
            normalizedMode,
            "GENERATING",
            null,
            List.of("catalog generation is in progress; no data replaced"));
      }
      if (shouldRetry202GenerationDrift(runtimeBefore, runtimeAfter, attempt)) {
        return doFetchAttempt(runtimeAfter, normalizedRef, normalizedMode, attempt + 1);
      }
      if (catalogLifecycleCompleted(runtimeAfter)) {
        if (attempt < MAX_FETCH_ATTEMPTS) {
          return doFetchAttempt(runtimeAfter, normalizedRef, normalizedMode, attempt + 1);
        }
        return unavailablePayload(
            normalizedRef,
            normalizedMode,
            "RUNTIME_CHANGED",
            List.of(
                "catalog generation completed but the response was still a stale 202; "
                    + "the acquisition did not settle"));
      }
      return unavailablePayload(
          normalizedRef,
          normalizedMode,
          "IDENTITY_UNVERIFIED",
          List.of("202 response while the runtime lifecycle is neither generating nor completed"));
    }
    if (fetch.statusCode() == 503) {
      return staleOrUnavailable(
          runtimeAfter,
          normalizedMode,
          "EXECUTOR_UNAVAILABLE",
          "CATALOG_503",
          List.of("executor reported catalog unavailable"));
    }
    if (fetch.statusCode() >= 400) {
      return staleOrUnavailable(
          runtimeAfter,
          normalizedMode,
          "EXECUTOR_ERROR",
          "CATALOG_HTTP_" + fetch.statusCode(),
          List.of("executor returned HTTP " + fetch.statusCode()));
    }

    JsonNode payload = fetch.body();
    if (payload == null || !payload.isObject()) {
      return staleOrUnavailable(
          runtimeAfter,
          normalizedMode,
          "MALFORMED_CATALOG",
          "MALFORMED_CATALOG",
          List.of("executor returned a non-JSON or non-object catalog body"));
    }

    ValidationResult validation =
        "full".equals(normalizedMode)
            ? Micro6CatalogV2Contract.validateFull(payload)
            : Micro6CatalogV2Contract.validateLightweight(payload);
    if (!validation.valid()) {
      // Permanent contract error: never cache, never fabricate an empty-valid catalog.
      return staleOrUnavailable(
          runtimeAfter,
          normalizedMode,
          "CONTRACT_INVALID",
          "CONTRACT_INVALID",
          validation.reasons());
    }

    // The catalog identity attested by the response must match the confirmed post-fetch runtime. A
    // mismatch caused by the catalog changing during the fetch (runtimeBefore != runtimeAfter) is
    // re-acquired once; a retry that still does not settle reports RUNTIME_CHANGED, while a
    // permanent mismatch on a stable identity is IDENTITY_UNVERIFIED.
    String catalogDrift = catalogIdentityValueError(runtimeAfter, fetch);
    if (catalogDrift != null) {
      boolean changedDuringFetch = catalogIdentityChanged(runtimeBefore, runtimeAfter);
      if (attempt == 1 && changedDuringFetch) {
        return doFetchAttempt(runtimeAfter, normalizedRef, normalizedMode, attempt + 1);
      }
      if (attempt > 1) {
        return unavailablePayload(
            normalizedRef,
            normalizedMode,
            "RUNTIME_CHANGED",
            List.of(
                "catalog identity changed while being fetched and did not settle; "
                    + catalogDrift));
      }
      return unavailablePayload(
          normalizedRef, normalizedMode, "IDENTITY_UNVERIFIED", List.of(catalogDrift));
    }

    // Bind the payload's identity to both the resolved runtime and the backend-attested response
    // identity: a conflicting catalog is never cached and never served as last-known-good for a
    // different identity.
    String identityConflict = identityConflict(payload, runtimeAfter, fetch.identity());
    if (identityConflict != null) {
      return unavailablePayload(
          normalizedRef, normalizedMode, "IDENTITY_CONFLICT", List.of(identityConflict));
    }

    CatalogState state = Micro6CatalogV2Contract.classify(payload, validation);
    if (state == CatalogState.UNAVAILABLE) {
      return staleOrUnavailable(
          runtimeAfter,
          normalizedMode,
          "CATALOG_UNAVAILABLE",
          "CATALOG_STATE_UNAVAILABLE",
          List.of("executor catalog has no usable tasks"));
    }

    String stateName = state == CatalogState.DEGRADED ? "DEGRADED" : "READY";
    // Cache only under the confirmed post-fetch identity; stale pre-fetch lifecycle metadata is
    // never stored.
    CachedCatalog entry =
        new CachedCatalog(
            runtimeAfter.machineUuid(),
            runtimeAfter.executorInstanceId(),
            runtimeAfter.sessionId(),
            normalizedRef,
            payloadCatalogVersion(payload),
            validation.catalogHash(),
            validation.schemaBundleHash(),
            payload.deepCopy(),
            Instant.now(),
            stateName);
    executorCache.put(cacheKey(runtimeAfter, normalizedMode), entry);
    ObjectNode response =
        (ObjectNode)
            catalogResponse(
                normalizedRef, normalizedMode, stateName, payload.deepCopy(), List.of());
    response.put("captured_at", entry.fetchedAt().toString());
    return response;
  }

  /** Re-resolves the runtime after a catalog fetch, or {@code null} when the registry fails. */
  private Micro6Runtime reResolve(String endpointRef) {
    try {
      return registryClient.resolveRuntime(endpointRef);
    } catch (Micro6RuntimeRegistryClient.Micro6RegistryException e) {
      return null;
    }
  }

  /** Core ownership (machine/executor/session) must be identical before and after the fetch. */
  private boolean coreOwnershipChanged(Micro6Runtime before, Micro6Runtime after) {
    return !Objects.equals(before.machineUuid(), after.machineUuid())
        || !Objects.equals(before.executorInstanceId(), after.executorInstanceId())
        || !Objects.equals(before.sessionId(), after.sessionId());
  }

  /** Whether any catalog identity field drifted between the pre-fetch and post-fetch runtime. */
  private boolean catalogIdentityChanged(Micro6Runtime before, Micro6Runtime after) {
    return !Objects.equals(before.catalogVersion(), after.catalogVersion())
        || !Objects.equals(before.catalogHash(), after.catalogHash())
        || !Objects.equals(before.schemaBundleHash(), after.schemaBundleHash())
        || !Objects.equals(before.catalogState(), after.catalogState())
        || !Objects.equals(before.schemaBundleState(), after.schemaBundleState());
  }

  /** Whether the post-fetch runtime has finished catalog generation. */
  private boolean catalogLifecycleCompleted(Micro6Runtime runtime) {
    String state = runtime.catalogState();
    return "READY".equals(state) || "DEGRADED".equals(state);
  }

  /**
   * A {@code 202} whose post-fetch runtime has just crossed from a completed state into {@code
   * GENERATING} while the response still carries the stale completed headers is a transient race:
   * the runtime changed during the fetch, so acquisition is retried once using the confirmed {@code
   * runtimeAfter}. A runtime that was already stably {@code GENERATING} with only contradictory
   * headers is a stable bad attestation and is not retried.
   */
  private boolean shouldRetry202GenerationDrift(
      Micro6Runtime runtimeBefore, Micro6Runtime runtimeAfter, int attempt) {
    return attempt < MAX_FETCH_ATTEMPTS
        && "GENERATING".equals(runtimeAfter.catalogState())
        && "GENERATING".equals(runtimeAfter.schemaBundleState())
        && catalogIdentityChanged(runtimeBefore, runtimeAfter);
  }

  /**
   * A {@code 202} is {@code GENERATING} only when both the post-fetch runtime and the response
   * headers attest generation for the catalog and the schema bundle. The core identity is already
   * verified against {@code runtimeAfter} before this helper runs.
   */
  private boolean runtimeConfirmsGeneration(Micro6Runtime runtime, ResponseIdentity identity) {
    return "GENERATING".equals(runtime.catalogState())
        && "GENERATING".equals(runtime.schemaBundleState())
        && "GENERATING".equals(identity.catalogState())
        && "GENERATING".equals(identity.schemaBundleState());
  }

  /**
   * The post-fetch runtime contract validation applied before any lifecycle status, body, or cache
   * fallback: a selectable runtime must still attest the identity fields ChromaScape binds a
   * catalog to.
   */
  private String postFetchRuntimeContractError(Micro6Runtime runtime, String mode) {
    return runtimeContractError(runtime, mode);
  }

  /**
   * Binds a payload schema bundle assertion to the response header and the confirmed post-fetch
   * runtime. A missing, {@code null}, empty, or whitespace-only {@code schema_bundle_hash} carries
   * no bundle claim; any non-text value (object, array, number, boolean) is a malformed claimed
   * bundle; and any non-empty text value is a claimed bundle whether valid or malformed. A claim is
   * accepted only when the header and runtime are both {@code AVAILABLE} and all three hashes are
   * valid and equal. Returns a conflict description, or {@code null} when the payload carries no
   * bundle.
   */
  private String schemaBundleBindingError(
      JsonNode payload, Micro6Runtime runtime, ResponseIdentity identity) {
    JsonNode bundleNode = payload.get("schema_bundle_hash");
    if (bundleNode == null || bundleNode.isNull() || bundleNode.isMissingNode()) {
      return null;
    }
    if (!bundleNode.isTextual()) {
      // Any non-text node (object, array, number, boolean) is a malformed claimed bundle and is
      // rejected before cache/classification, never silently ignored as "no bundle".
      return "schema bundle hash conflict: payload carries a malformed bundle hash "
          + bundleNode.toString();
    }
    String payloadBundle = bundleNode.textValue();
    if (payloadBundle == null || payloadBundle.isBlank()) {
      // An empty or whitespace-only text value carries no bundle claim.
      return null;
    }
    payloadBundle = payloadBundle.trim();
    if (!isValidSha256(payloadBundle)) {
      return "schema bundle hash conflict: payload carries a malformed bundle hash "
          + payloadBundle;
    }
    if (!"AVAILABLE".equals(identity.schemaBundleState())) {
      return "schema bundle state conflict: payload carries a bundle hash while header state is "
          + identity.schemaBundleState();
    }
    if (!"AVAILABLE".equals(runtime.schemaBundleState())) {
      return "schema bundle state conflict: payload carries a bundle hash while runtime state is "
          + runtime.schemaBundleState();
    }
    if (identity.schemaBundleHash() != null && !payloadBundle.equals(identity.schemaBundleHash())) {
      return "schema bundle hash conflict: payload="
          + payloadBundle
          + " header="
          + identity.schemaBundleHash();
    }
    if (runtime.schemaBundleHash() != null && !payloadBundle.equals(runtime.schemaBundleHash())) {
      return "schema bundle hash conflict: payload="
          + payloadBundle
          + " runtime="
          + runtime.schemaBundleHash();
    }
    if (identity.schemaBundleHash() == null) {
      return "schema bundle hash conflict: payload carries a bundle hash while the header attests"
          + " none";
    }
    if (runtime.schemaBundleHash() == null) {
      return "schema bundle hash conflict: payload carries a bundle hash while the runtime attests"
          + " none";
    }
    return null;
  }

  /**
   * A selectable runtime must attest the identity fields ChromaScape binds a catalog to: an ONLINE
   * runtime requires a valid catalog hash, and a full-mode runtime claiming {@code AVAILABLE}
   * requires a valid schema bundle hash. Returns a human-readable failure description, or {@code
   * null} when the runtime is contract-valid.
   */
  private String runtimeContractError(Micro6Runtime runtime, String mode) {
    if (!"ONLINE".equalsIgnoreCase(runtime.runtimeState())) {
      return null;
    }
    if (runtime.catalogHash() == null || !isValidSha256(runtime.catalogHash())) {
      return "runtime reports ONLINE without a valid catalog hash";
    }
    if ("full".equals(mode)
        && "AVAILABLE".equals(runtime.schemaBundleState())
        && (runtime.schemaBundleHash() == null || !isValidSha256(runtime.schemaBundleHash()))) {
      return "runtime reports schema_bundle_state AVAILABLE without a valid schema bundle hash";
    }
    return null;
  }

  /**
   * Structured result of validating a runtime's lifecycle attestation. {@code reasonCode} is either
   * {@code IDENTITY_UNVERIFIED} (missing lifecycle state) or {@code CONTRACT_INVALID}
   * (unknown/impossible state pair or a schema-bundle hash violating the matrix).
   */
  private record RuntimeLifecycleAttestation(String reasonCode, String message) {}

  /**
   * Validates a runtime's lifecycle attestation against the catalog/schema-bundle matrix. A missing
   * {@code catalog_state} or {@code schema_bundle_state} means ChromaScape cannot bind the catalog
   * to a verifiable lifecycle and is reported {@code IDENTITY_UNVERIFIED}; a present but unknown or
   * impossible state pair, a non-{@code AVAILABLE} state carrying a schema-bundle hash, or an
   * {@code AVAILABLE} state without a valid lowercase {@code sha256:} hash is a permanent {@code
   * CONTRACT_INVALID} violation. Returns {@code null} when the attestation is valid. This matrix
   * applies in both full and lightweight modes.
   */
  private RuntimeLifecycleAttestation runtimeLifecycleAttestation(Micro6Runtime runtime) {
    if (runtime.catalogState() == null) {
      return new RuntimeLifecycleAttestation(
          "IDENTITY_UNVERIFIED", "runtime lifecycle attestation missing catalog_state");
    }
    if (runtime.schemaBundleState() == null) {
      return new RuntimeLifecycleAttestation(
          "IDENTITY_UNVERIFIED", "runtime lifecycle attestation missing schema_bundle_state");
    }
    if (!ResponseIdentity.hasValidLifecycleStates(
        runtime.catalogState(), runtime.schemaBundleState(), runtime.schemaBundleHash())) {
      return new RuntimeLifecycleAttestation(
          "CONTRACT_INVALID",
          "runtime lifecycle attestation violates the catalog/schema-bundle matrix");
    }
    return null;
  }

  /**
   * Requires the backend identity headers expected for each executor lifecycle status. 200 requires
   * the full identity (machine/executor/session/version/catalog hash/observed_at); 202 and 503
   * require the core identity (machine/executor/session) plus valid lifecycle states. Every
   * lifecycle status must carry valid catalog/schema-bundle states and a valid {@code observed_at}.
   * Non-lifecycle statuses (e.g. 502) carry no executor identity and are never verified here; they
   * fall through to last-known-good handling. Returns a human-readable failure description, or
   * {@code null} when the headers are present.
   */
  private String identityHeaderPresenceError(CatalogFetch fetch) {
    boolean lifecycleStatus =
        fetch.statusCode() == 200 || fetch.statusCode() == 202 || fetch.statusCode() == 503;
    if (!lifecycleStatus) {
      return null;
    }
    ResponseIdentity identity = fetch.identity();
    if (identity == null) {
      return "backend response carried no identity headers";
    }
    if (fetch.statusCode() == 200) {
      if (!identity.hasFullIdentity()) {
        return "backend response identity headers incomplete for 200"
            + " (machine/executor/session/version/catalog hash/observed_at required)";
      }
    } else if (!identity.hasCoreIdentity()) {
      return "backend response identity headers incomplete for HTTP "
          + fetch.statusCode()
          + " (machine/executor/session required)";
    }
    // Every forwarded lifecycle status must carry valid catalog/schema-bundle states; a missing or
    // impossible combination is a permanent contract error before the body is processed.
    if (!identity.hasValidLifecycleStates()) {
      return "backend response lifecycle states invalid (catalog_state="
          + identity.catalogState()
          + " schema_bundle_state="
          + identity.schemaBundleState()
          + " bundle_hash="
          + identity.schemaBundleHash()
          + ")";
    }
    if (!identity.hasValidObservedAt(Instant.now(), runtimeTtl())) {
      return "backend response observed_at missing, stale, or too far in the future";
    }
    return null;
  }

  /**
   * Verifies the backend-attested core identity (machine/executor/session) against the confirmed
   * post-fetch runtime. Core identity must agree on every lifecycle status. Returns a
   * human-readable failure description, or {@code null} when the core identity is consistent.
   */
  private String coreIdentityValueError(Micro6Runtime runtime, CatalogFetch fetch) {
    ResponseIdentity identity = fetch.identity();
    if (!Objects.equals(identity.machineUuid(), runtime.machineUuid())) {
      return "machine_uuid mismatch: header="
          + identity.machineUuid()
          + " runtime="
          + runtime.machineUuid();
    }
    if (!Objects.equals(identity.executorInstanceId(), runtime.executorInstanceId())) {
      return "executor_instance_id mismatch: header="
          + identity.executorInstanceId()
          + " runtime="
          + runtime.executorInstanceId();
    }
    if (!Objects.equals(identity.sessionId(), runtime.sessionId())) {
      return "session_id mismatch: header="
          + identity.sessionId()
          + " runtime="
          + runtime.sessionId();
    }
    return null;
  }

  /**
   * Verifies the catalog identity attested by a 200 response against the confirmed post-fetch
   * runtime: the header catalog state, schema-bundle state, catalog version, catalog hash, and the
   * applicable schema bundle hash must agree exactly with the runtime's attestation, and a
   * completed catalog response must not contradict a runtime that is still generating. Returns a
   * human-readable drift description, or {@code null} when the response is consistent with the
   * post-fetch runtime.
   */
  private String catalogIdentityValueError(Micro6Runtime runtime, CatalogFetch fetch) {
    if (fetch.statusCode() != 200) {
      return null;
    }
    if ("GENERATING".equals(runtime.catalogState())) {
      return "catalog lifecycle conflict: completed catalog while runtime is still generating";
    }
    ResponseIdentity identity = fetch.identity();
    if (runtime.catalogState() != null
        && !Objects.equals(identity.catalogState(), runtime.catalogState())) {
      return "catalog state mismatch: header="
          + identity.catalogState()
          + " runtime="
          + runtime.catalogState();
    }
    if (runtime.schemaBundleState() != null
        && !Objects.equals(identity.schemaBundleState(), runtime.schemaBundleState())) {
      return "schema bundle state mismatch: header="
          + identity.schemaBundleState()
          + " runtime="
          + runtime.schemaBundleState();
    }
    if (runtime.catalogVersion() != null
        && !Objects.equals(identity.catalogVersion(), runtime.catalogVersion())) {
      return "catalog version mismatch: header="
          + identity.catalogVersion()
          + " runtime="
          + runtime.catalogVersion();
    }
    if (runtime.catalogHash() != null
        && !Objects.equals(identity.catalogHash(), runtime.catalogHash())) {
      return "catalog hash mismatch: header="
          + identity.catalogHash()
          + " runtime="
          + runtime.catalogHash();
    }
    if (runtime.schemaBundleHash() != null
        && !Objects.equals(identity.schemaBundleHash(), runtime.schemaBundleHash())) {
      return "schema bundle hash mismatch: header="
          + identity.schemaBundleHash()
          + " runtime="
          + runtime.schemaBundleHash();
    }
    return null;
  }

  /**
   * Returns a human-readable conflict description when the catalog payload's attested identity or
   * hashes disagree with the resolved runtime or the backend-attested response identity, or {@code
   * null} when the payload is consistent with both. A 200 payload's {@code catalog_state} must bind
   * to the response catalog-state header, and any non-empty payload {@code schema_bundle_hash} is a
   * claimed bundle that requires an {@code AVAILABLE} header and runtime state with matching, valid
   * header/runtime hashes.
   */
  private String identityConflict(
      JsonNode payload, Micro6Runtime runtime, ResponseIdentity identity) {
    String payloadInstance = text(payload.get("executor_instance_id"));
    if (payloadInstance != null
        && identity.executorInstanceId() != null
        && !payloadInstance.equals(identity.executorInstanceId())) {
      return "executor_instance_id conflict: payload="
          + payloadInstance
          + " header="
          + identity.executorInstanceId();
    }
    if (payloadInstance != null && !payloadInstance.equals(runtime.executorInstanceId())) {
      return "executor_instance_id conflict: payload="
          + payloadInstance
          + " runtime="
          + runtime.executorInstanceId();
    }
    String payloadVersion =
        firstNonBlank(text(payload.get("catalog_version")), text(payload.get("version")));
    if (payloadVersion != null
        && identity.catalogVersion() != null
        && !payloadVersion.equals(identity.catalogVersion())) {
      return "catalog version conflict: payload="
          + payloadVersion
          + " header="
          + identity.catalogVersion();
    }
    if (payloadVersion != null
        && runtime.catalogVersion() != null
        && !payloadVersion.equals(runtime.catalogVersion())) {
      return "catalog version conflict: payload="
          + payloadVersion
          + " runtime="
          + runtime.catalogVersion();
    }
    String payloadHash = text(payload.get("catalog_hash"));
    if (payloadHash != null
        && isValidSha256(payloadHash)
        && identity.catalogHash() != null
        && !payloadHash.equals(identity.catalogHash())) {
      return "catalog hash conflict: payload=" + payloadHash + " header=" + identity.catalogHash();
    }
    if (payloadHash != null
        && isValidSha256(payloadHash)
        && runtime.catalogHash() != null
        && !payloadHash.equals(runtime.catalogHash())) {
      return "catalog hash conflict: payload=" + payloadHash + " runtime=" + runtime.catalogHash();
    }

    // The payload's catalog_state must bind to the backend-attested response state header.
    String payloadState = text(payload.get("catalog_state"));
    if (payloadState != null
        && identity.catalogState() != null
        && !payloadState.equals(identity.catalogState())) {
      return "catalog state conflict: payload="
          + payloadState
          + " header="
          + identity.catalogState();
    }

    // A payload carrying a schema bundle hash is a claimed bundle whether the value is valid or
    // malformed; it is rejected unless the header and runtime are both AVAILABLE and all hashes
    // match exactly.
    return schemaBundleBindingError(payload, runtime, identity);
  }

  /** The catalog's own version as attested in the payload. */
  private String payloadCatalogVersion(JsonNode payload) {
    return firstNonBlank(text(payload.get("catalog_version")), text(payload.get("version")));
  }

  /**
   * A cached entry is usable for the currently resolved runtime only when its identity (machine,
   * executor instance, session) matches and its catalog metadata has not drifted. In full mode a
   * runtime attesting {@code AVAILABLE} requires exact schema-bundle hash equality with the cache.
   * A runtime in any other schema-bundle state (GENERATING/ERROR/NOT_AVAILABLE) still matches a
   * same-identity cached full entry so the caller may serve it as STALE only. Lightweight mode
   * ignores bundle changes.
   */
  private boolean cacheMatchesRuntime(CachedCatalog cached, Micro6Runtime runtime, String mode) {
    if (!Objects.equals(cached.machineUuid(), runtime.machineUuid())) {
      return false;
    }
    if (!Objects.equals(cached.executorInstanceId(), runtime.executorInstanceId())) {
      return false;
    }
    if (!Objects.equals(cached.sessionId(), runtime.sessionId())) {
      return false;
    }
    if (runtime.catalogVersion() != null
        && cached.catalogVersion() != null
        && !Objects.equals(runtime.catalogVersion(), cached.catalogVersion())) {
      return false;
    }
    if (runtime.catalogHash() != null
        && cached.catalogHash() != null
        && !Objects.equals(runtime.catalogHash(), cached.catalogHash())) {
      return false;
    }
    if ("full".equals(mode) && "AVAILABLE".equals(runtime.schemaBundleState())) {
      // AVAILABLE requires exact bundle-hash equality; a missing runtime bundle was already
      // rejected as CONTRACT_INVALID upstream.
      return Objects.equals(cached.schemaBundleHash(), runtime.schemaBundleHash());
    }
    return true;
  }

  private boolean isValidSha256(String value) {
    if (value == null || !value.startsWith("sha256:")) {
      return false;
    }
    return SHA256_HEX.matcher(value.substring("sha256:".length())).matches();
  }

  /** The freshness window backend-attested {@code observed_at} must fall within. */
  private Duration runtimeTtl() {
    return DEFAULT_RUNTIME_TTL;
  }

  /** Returns a catalog payload or an error payload for a given executor reference. */
  private JsonNode catalogResponse(
      String endpointRef,
      String mode,
      String catalogState,
      JsonNode catalog,
      List<String> reasons) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("catalog_state", catalogState);
    root.put("mode", mode);
    root.put("endpoint_ref", endpointRef);
    root.put("source", "leeuwbotapi_authenticated_runtime_registry");
    if (catalog != null) {
      root.set("catalog", catalog);
      root.put("catalog_hash", text(catalog.get("catalog_hash")));
      root.put("schema_bundle_hash", text(catalog.get("schema_bundle_hash")));
    }
    root.put("last_known_good", catalog != null);
    ArrayNode reasonArray = objectMapper.createArrayNode();
    if (reasons != null) {
      reasons.forEach(reasonArray::add);
    }
    root.set("reasons", reasonArray);
    return root;
  }

  /**
   * Preserves last-known-good data when a refresh fails, scoped to the executor identity resolved
   * at fetch time. A cached payload is served as {@code STALE} only when its identity still matches
   * the current runtime.
   */
  private JsonNode staleOrUnavailable(
      Micro6Runtime runtime,
      String mode,
      String reasonCode,
      String stateHint,
      List<String> reasons) {
    CachedCatalog cached = executorCache.get(cacheKey(runtime, mode));
    if (cached != null && cached.payload() != null && cacheMatchesRuntime(cached, runtime, mode)) {
      log.warn(
          "[Micro6Catalog] refresh failed for ref={} mode={} code={}; serving last-known-good",
          runtime.endpointRef(),
          mode,
          reasonCode);
      ObjectNode response =
          (ObjectNode)
              catalogResponse(
                  runtime.endpointRef(), mode, "STALE", cached.payload().deepCopy(), reasons);
      response.put("reason_code", reasonCode);
      response.put("detailed_reason_code", stateHint);
      response.put("captured_at", cached.fetchedAt().toString());
      return response;
    }
    ObjectNode response =
        (ObjectNode) unavailablePayload(runtime.endpointRef(), mode, reasonCode, reasons);
    response.put("detailed_reason_code", stateHint);
    return response;
  }

  /** Reports a hard failure that must never be satisfied by last-known-good data. */
  private JsonNode unavailablePayload(
      String endpointRef, String mode, String reasonCode, List<String> reasons) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("catalog_state", "UNAVAILABLE");
    root.put("mode", mode);
    root.put("endpoint_ref", endpointRef);
    root.put("reason_code", reasonCode);
    root.put("source", "leeuwbotapi_authenticated_runtime_registry");
    ArrayNode reasonArray = objectMapper.createArrayNode();
    if (reasons != null) {
      reasons.forEach(reasonArray::add);
    }
    root.set("reasons", reasonArray);
    return root;
  }

  private JsonNode registryErrorPayload(
      Micro6RuntimeRegistryClient.Micro6RegistryException e, String context) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("error", "micro6_registry_failure");
    root.put("reason_code", e.reasonCode());
    root.put("message", e.getMessage());
    root.put("source", "leeuwbotapi_authenticated_runtime_registry");
    root.put("context", context);
    root.set("executors", objectMapper.createArrayNode());
    root.put("count", 0);
    return root;
  }

  // ------------------------------------------------------------------
  // Development fallback (fixed local URL, marked DEV_STATIC_ROUTE)
  // ------------------------------------------------------------------

  private JsonNode devFallbackCatalog(boolean forceRefresh) {
    JsonNode catalog = getCatalogForBaseUrl(config.getExecutorCatalog().getBaseUrl(), forceRefresh);
    ObjectNode wrapped;
    if (catalog != null && catalog.isObject()) {
      wrapped = ((ObjectNode) catalog).deepCopy();
    } else {
      wrapped = objectMapper.createObjectNode();
      wrapped.set("tasks", objectMapper.createArrayNode());
    }
    wrapped.put("dev_static_route", true);
    wrapped.put("route_source", "DEV_STATIC_ROUTE");
    wrapped.put(
        "route_disclaimer",
        "Development-only fixed URL; not valid production or acceptance evidence");
    return wrapped;
  }

  // ------------------------------------------------------------------
  // Legacy fixed-URL API (kept for existing consumers; dev fallback only)
  // ------------------------------------------------------------------

  public JsonNode getCatalog(boolean forceRefresh) {
    if (!config.getExecutorCatalog().isEnabled()) {
      return objectMapper.valueToTree(Map.of("enabled", false, "tasks", List.of()));
    }
    JsonNode catalog = getCatalogForBaseUrl(config.getExecutorCatalog().getBaseUrl(), forceRefresh);
    if (catalog == null || !catalog.isObject()) {
      return catalog;
    }
    // The fixed local URL is an explicit development-only fallback and must be
    // visibly marked so it can never be mistaken for production or acceptance
    // evidence.
    ObjectNode marked = ((ObjectNode) catalog).deepCopy();
    marked.put("dev_static_route", true);
    marked.put("route_source", "DEV_STATIC_ROUTE");
    marked.put(
        "route_disclaimer",
        "Development-only fixed URL; not valid production or acceptance evidence");
    return marked;
  }

  public JsonNode peekCatalog() {
    return peekCatalogForBaseUrl(config.getExecutorCatalog().getBaseUrl());
  }

  public JsonNode peekCatalogForBaseUrl(String baseUrl) {
    if (!config.getExecutorCatalog().isEnabled()) {
      return objectMapper.valueToTree(Map.of("enabled", false, "tasks", List.of()));
    }
    String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
    if (normalizedBaseUrl == null) {
      return null;
    }
    if (normalizedBaseUrl.equals(normalizeBaseUrl(config.getExecutorCatalog().getBaseUrl()))) {
      return cache.get();
    }
    BaseUrlCachedCatalog cached = cacheByBaseUrl.get(normalizedBaseUrl);
    return cached != null ? cached.payload() : null;
  }

  @org.springframework.beans.factory.annotation.Autowired
  private com.chromascape.web.debug.buildergoal.service.BuilderGoalEndpointAuthority
      endpointAuthority;

  public JsonNode getCatalogForIdentity(Map<String, Object> identity, boolean forceRefresh) {
    String route =
        endpointAuthority == null
            ? null
            : endpointAuthority.resolve(objectMapper.valueToTree(identity));
    if (route == null)
      return objectMapper.createObjectNode().put("reason_code", "ENDPOINT_UNTRUSTED");
    JsonNode result = fetchCatalogForOrigin(route, true);
    if (!route.equals(endpointAuthority.resolve(objectMapper.valueToTree(identity)))) {
      cacheByBaseUrl.remove(route);
      if (route.equals(normalizeBaseUrl(config.getExecutorCatalog().getBaseUrl()))) cache.set(null);
      return objectMapper.createObjectNode().put("reason_code", "ENDPOINT_FENCED");
    }
    return result;
  }

  @Deprecated
  public JsonNode getCatalogForBaseUrl(String baseUrl, boolean forceRefresh) {
    if (endpointAuthority != null)
      return objectMapper.createObjectNode().put("reason_code", "RAW_ENDPOINT_AUTHORITY_FORBIDDEN");
    return fetchCatalogForOrigin(baseUrl, forceRefresh);
  }

  private JsonNode fetchCatalogForOrigin(String baseUrl, boolean forceRefresh) {
    String safeOrigin =
        com.chromascape.web.debug.buildergoal.service.BuilderGoalEndpointAuthority.normalize(
            baseUrl);
    if (safeOrigin == null)
      return objectMapper.createObjectNode().put("reason_code", "ENDPOINT_UNTRUSTED");
    baseUrl = safeOrigin;
    if (!config.getExecutorCatalog().isEnabled()) {
      return objectMapper.valueToTree(Map.of("enabled", false, "tasks", List.of()));
    }
    long ttl = Math.max(1, config.getExecutorCatalog().getRefreshSeconds());
    String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
    if (normalizedBaseUrl == null) {
      return objectMapper.valueToTree(
          Map.of("enabled", true, "error", "Missing Micro6 executor base URL", "tasks", List.of()));
    }

    if (normalizedBaseUrl.equals(normalizeBaseUrl(config.getExecutorCatalog().getBaseUrl()))) {
      if (!forceRefresh
          && cache.get() != null
          && Instant.now().isBefore(lastRefresh.plusSeconds(ttl))) {
        return cache.get();
      }
    } else {
      BaseUrlCachedCatalog cached = cacheByBaseUrl.get(normalizedBaseUrl);
      if (!forceRefresh
          && cached != null
          && Instant.now().isBefore(cached.refreshedAt().plusSeconds(ttl))) {
        return cached.payload();
      }
    }

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(normalizedBaseUrl + "/api/v1/catalog?mode=lightweight"))
              .timeout(Duration.ofMillis(config.getExecutorCatalog().getTimeoutMs()))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(
              topologyGuard == null
                  ? request
                  : HttpRequest.newBuilder(
                          request, (name, value) -> !name.equalsIgnoreCase("Authorization"))
                      .header("Authorization", topologyGuard.executorAuthorization())
                      .build(),
              com.chromascape.web.http.BoundedJsonResponse.handler(8 * 1024 * 1024));
      if (response.statusCode() >= 400) {
        throw new IllegalStateException("Catalog fetch failed: HTTP " + response.statusCode());
      }
      JsonNode parsed = objectMapper.readTree(response.body());
      Instant refreshedAt = Instant.now();
      if (normalizedBaseUrl.equals(normalizeBaseUrl(config.getExecutorCatalog().getBaseUrl()))) {
        cache.set(parsed);
        lastRefresh = refreshedAt;
      } else {
        cacheByBaseUrl.put(normalizedBaseUrl, new BaseUrlCachedCatalog(parsed, refreshedAt));
      }
      return parsed;
    } catch (Exception e) {
      log.warn("Micro6 catalog fetch failed: {}", e.getClass().getSimpleName());
      if (endpointAuthority != null)
        return objectMapper.createObjectNode().put("reason_code", "ENDPOINT_UNAVAILABLE");
      if (normalizedBaseUrl.equals(normalizeBaseUrl(config.getExecutorCatalog().getBaseUrl()))
          && cache.get() != null) {
        return cache.get();
      }
      BaseUrlCachedCatalog cached = cacheByBaseUrl.get(normalizedBaseUrl);
      if (cached != null) {
        return cached.payload();
      }
      Map<String, Object> fallback = new LinkedHashMap<>();
      fallback.put("enabled", true);

      fallback.put("error", "Executor catalog unavailable");
      fallback.put("tasks", List.of());
      return objectMapper.valueToTree(fallback);
    }
  }

  private String normalizeBaseUrl(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.isBlank() ? null : normalized;
  }

  private String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private String text(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    String value = node.isTextual() ? node.textValue() : node.asText();
    return value == null || value.isBlank() ? null : value.trim();
  }

  private JsonNode errorPayload(String reasonCode, String message) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("catalog_state", "UNAVAILABLE");
    root.put("reason_code", reasonCode);
    root.put("message", message);
    root.put("source", "leeuwbotapi_authenticated_runtime_registry");
    root.set("reasons", objectMapper.createArrayNode().add(message));
    return root;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /** Legacy fixed-URL development-fallback cache entry keyed by normalized base URL. */
  private record BaseUrlCachedCatalog(JsonNode payload, Instant refreshedAt) {}

  /**
   * Identity-scoped catalog cache entry. The runtime identity captured at fetch time is stored with
   * the payload so a cached copy is never served for a different executor identity.
   */
  private record CachedCatalog(
      String machineUuid,
      String executorInstanceId,
      String sessionId,
      String endpointRef,
      String catalogVersion,
      String catalogHash,
      String schemaBundleHash,
      JsonNode payload,
      Instant fetchedAt,
      String state) {
    boolean isFresh(int refreshSeconds) {
      return Instant.now().isBefore(fetchedAt.plusSeconds(Math.max(1, refreshSeconds)));
    }
  }
}

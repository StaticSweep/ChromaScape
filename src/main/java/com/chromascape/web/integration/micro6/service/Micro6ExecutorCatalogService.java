package com.chromascape.web.integration.micro6.service;

import com.chromascape.web.integration.micro6.config.Micro6Config;
import com.chromascape.web.integration.micro6.service.Micro6CatalogV2Contract.CatalogState;
import com.chromascape.web.integration.micro6.service.Micro6CatalogV2Contract.ValidationResult;
import com.chromascape.web.integration.micro6.service.Micro6RuntimeRegistryClient.CatalogFetch;
import com.chromascape.web.integration.micro6.service.Micro6RuntimeRegistryClient.Micro6Runtime;
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

  private final Micro6Config config;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Micro6RuntimeRegistryClient registryClient;

  private final AtomicReference<JsonNode> cache = new AtomicReference<>();
  private final ConcurrentHashMap<String, BaseUrlCachedCatalog> cacheByBaseUrl =
      new ConcurrentHashMap<>();
  private volatile Instant lastRefresh = Instant.EPOCH;

  private final ConcurrentHashMap<String, CachedCatalog> executorCache =
      new ConcurrentHashMap<>();
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
    node.put("advertised_base_url", runtime.advertisedBaseUrl());
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
    return runtime.machineUuid() + "|" + runtime.executorInstanceId() + "|" + runtime.sessionId()
        + "|" + mode;
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

    // The cache is identity-scoped: only a fresh entry whose identity matches the currently
    // resolved runtime is usable.
    String key = cacheKey(runtime, normalizedMode);
    CachedCatalog cached = executorCache.get(key);
    if (!forceRefresh
        && cached != null
        && cached.isFresh(config.getExecutorCatalog().getRefreshSeconds())
        && cacheMatchesRuntime(cached, runtime)) {
      return catalogResponse(normalizedRef, normalizedMode, cached.state(), cached.payload(), List.of());
    }

    return awaitOrFetch(runtime, normalizedRef, normalizedMode);
  }

  /**
   * Coalesces concurrent requests for the same executor identity+mode onto a single in-flight fetch.
   * Only the winning request updates the cache; the losers await the winner's result.
   */
  private JsonNode awaitOrFetch(Micro6Runtime runtime, String normalizedRef, String normalizedMode) {
    String key = cacheKey(runtime, normalizedMode);
    CompletableFuture<JsonNode> inflight = inflightFetches.get(key);
    if (inflight != null) {
      try {
        return inflight.join();
      } catch (Exception winnerFailed) {
        // The winner failed and cleared its entry; fall through to fetch ourselves.
      }
    }
    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    CompletableFuture<JsonNode> winner = inflightFetches.putIfAbsent(key, future);
    if (winner != null) {
      try {
        return winner.join();
      } catch (Exception winnerFailed) {
        // The winner failed and cleared its entry; fetch ourselves.
      }
    } else {
      try {
        JsonNode result = doFetch(runtime, normalizedRef, normalizedMode);
        future.complete(result);
        return result;
      } catch (Throwable t) {
        future.completeExceptionally(t);
        throw t;
      } finally {
        inflightFetches.remove(key, future);
      }
    }
    return doFetch(runtime, normalizedRef, normalizedMode);
  }

  /** Fetches, validates, binds to the resolved runtime identity, and caches a catalog. */
  private JsonNode doFetch(Micro6Runtime runtime, String normalizedRef, String normalizedMode) {
    CatalogFetch fetch;
    try {
      fetch = registryClient.fetchCatalog(normalizedRef, normalizedMode);
    } catch (Micro6RuntimeRegistryClient.Micro6RegistryException e) {
      return staleOrUnavailable(
          runtime, normalizedMode, "REGISTRY_FAILURE", e.reasonCode(), List.of(e.getMessage()));
    }

    if (fetch.statusCode() == 202) {
      // Generation active: never touch the cache.
      return catalogResponse(
          normalizedRef,
          normalizedMode,
          "GENERATING",
          null,
          List.of("catalog generation is in progress; no data replaced"));
    }
    if (fetch.statusCode() == 503) {
      return staleOrUnavailable(
          runtime,
          normalizedMode,
          "EXECUTOR_UNAVAILABLE",
          "CATALOG_503",
          List.of("executor reported catalog unavailable"));
    }
    if (fetch.statusCode() >= 400) {
      return staleOrUnavailable(
          runtime,
          normalizedMode,
          "EXECUTOR_ERROR",
          "CATALOG_HTTP_" + fetch.statusCode(),
          List.of("executor returned HTTP " + fetch.statusCode()));
    }

    JsonNode payload = fetch.body();
    if (payload == null || !payload.isObject()) {
      return staleOrUnavailable(
          runtime,
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
          runtime,
          normalizedMode,
          "CONTRACT_INVALID",
          "CONTRACT_INVALID",
          validation.reasons());
    }

    // Bind the payload's identity to the resolved runtime: a conflicting catalog is never cached
    // and never served as last-known-good for a different identity.
    String identityConflict = identityConflict(payload, runtime);
    if (identityConflict != null) {
      return unavailablePayload(
          normalizedRef, normalizedMode, "IDENTITY_CONFLICT", List.of(identityConflict));
    }

    // Re-resolve after the fetch to guard the heartbeat/process-replacement race. If the executor
    // identity changed while the catalog was being fetched, discard it instead of caching.
    Micro6Runtime runtimeAfter;
    try {
      runtimeAfter = registryClient.resolveRuntime(normalizedRef);
    } catch (Micro6RuntimeRegistryClient.Micro6RegistryException e) {
      return unavailablePayload(
          normalizedRef,
          normalizedMode,
          "RUNTIME_CHANGED",
          List.of("runtime identity could not be re-confirmed after catalog fetch"));
    }
    if (runtimeAfter == null
        || !Objects.equals(runtimeAfter.executorInstanceId(), runtime.executorInstanceId())
        || !Objects.equals(runtimeAfter.sessionId(), runtime.sessionId())) {
      return unavailablePayload(
          normalizedRef,
          normalizedMode,
          "RUNTIME_CHANGED",
          List.of("executor identity changed while the catalog was being fetched"));
    }

    CatalogState state = Micro6CatalogV2Contract.classify(payload, validation);
    if (state == CatalogState.UNAVAILABLE) {
      return staleOrUnavailable(
          runtime,
          normalizedMode,
          "CATALOG_UNAVAILABLE",
          "CATALOG_STATE_UNAVAILABLE",
          List.of("executor catalog has no usable tasks"));
    }

    String stateName = state == CatalogState.DEGRADED ? "DEGRADED" : "READY";
    CachedCatalog entry =
        new CachedCatalog(
            runtime.machineUuid(),
            runtime.executorInstanceId(),
            runtime.sessionId(),
            normalizedRef,
            payloadCatalogVersion(payload),
            validation.catalogHash(),
            validation.schemaBundleHash(),
            payload,
            Instant.now(),
            stateName);
    executorCache.put(cacheKey(runtime, normalizedMode), entry);
    return catalogResponse(normalizedRef, normalizedMode, stateName, payload, List.of());
  }

  /**
   * Returns a human-readable conflict description when the catalog payload's attested identity
   * disagrees with the resolved runtime, or {@code null} when the payload is consistent.
   */
  private String identityConflict(JsonNode payload, Micro6Runtime runtime) {
    String payloadInstance = text(payload.get("executor_instance_id"));
    if (payloadInstance != null && !payloadInstance.equals(runtime.executorInstanceId())) {
      return "executor_instance_id conflict: payload=" + payloadInstance + " runtime="
          + runtime.executorInstanceId();
    }
    String payloadVersion =
        firstNonBlank(text(payload.get("catalog_version")), text(payload.get("version")));
    if (payloadVersion != null
        && runtime.catalogVersion() != null
        && !payloadVersion.equals(runtime.catalogVersion())) {
      return "catalog version conflict: payload=" + payloadVersion + " runtime="
          + runtime.catalogVersion();
    }
    String payloadHash = text(payload.get("catalog_hash"));
    if (payloadHash != null
        && isValidSha256(payloadHash)
        && runtime.catalogHash() != null
        && !payloadHash.equals(runtime.catalogHash())) {
      return "catalog hash conflict: payload=" + payloadHash + " runtime=" + runtime.catalogHash();
    }
    return null;
  }

  /** The catalog's own version as attested in the payload. */
  private String payloadCatalogVersion(JsonNode payload) {
    return firstNonBlank(text(payload.get("catalog_version")), text(payload.get("version")));
  }

  /**
   * A cached entry is usable for the currently resolved runtime only when its identity (machine,
   * executor instance, session) matches and its catalog metadata has not drifted.
   */
  private boolean cacheMatchesRuntime(CachedCatalog cached, Micro6Runtime runtime) {
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
    return true;
  }

  private boolean isValidSha256(String value) {
    if (value == null || !value.startsWith("sha256:")) {
      return false;
    }
    return SHA256_HEX.matcher(value.substring("sha256:".length())).matches();
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
      Micro6Runtime runtime, String mode, String reasonCode, String stateHint, List<String> reasons) {
    CachedCatalog cached = executorCache.get(cacheKey(runtime, mode));
    if (cached != null && cached.payload() != null && cacheMatchesRuntime(cached, runtime)) {
      log.warn(
          "[Micro6Catalog] refresh failed for ref={} mode={} code={}; serving last-known-good",
          runtime.endpointRef(),
          mode,
          reasonCode);
      return catalogResponse(runtime.endpointRef(), mode, "STALE", cached.payload(), reasons);
    }
    return unavailablePayload(runtime.endpointRef(), mode, reasonCode, reasons);
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

  public JsonNode getCatalogForBaseUrl(String baseUrl, boolean forceRefresh) {
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
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
      log.warn("Failed to fetch Micro6 catalog from {}: {}", normalizedBaseUrl, e.getMessage());
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
      fallback.put("baseUrl", normalizedBaseUrl);
      fallback.put("error", e.getMessage());
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

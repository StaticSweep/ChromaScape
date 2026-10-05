package com.chromascape.web.integration.micro6.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticated client for the leeuwbotapi Micro6 runtime registry.
 *
 * <p>ChromaScape never accepts a raw executor URL from a browser. It resolves opaque endpoint
 * references through this client, and the registry fetches catalogs server-side from the executor.
 * The only URLs this client ever sends are the configured registry base URL and endpoint
 * references; the executor's advertised base URL stays observational metadata.
 */
public class Micro6RuntimeRegistryClient {
  private static final Logger log = LoggerFactory.getLogger(Micro6RuntimeRegistryClient.class);

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String baseUrl;
  private final String authToken;
  private final long timeoutMs;

  public Micro6RuntimeRegistryClient(
      ObjectMapper objectMapper, String baseUrl, String authToken, long timeoutMs) {
    this.objectMapper = objectMapper;
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.authToken = normalizeOptional(authToken);
    this.timeoutMs = Math.max(1000L, timeoutMs);
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(this.timeoutMs)).build();
  }

  /** Normalized, credential-free view of an authenticated Micro6 runtime candidate. */
  public record Micro6Runtime(
      String machineUuid,
      String sessionId,
      String accountHash,
      String clientType,
      String executorInstanceId,
      String catalogVersion,
      String catalogHash,
      String schemaBundleHash,
      String catalogState,
      String schemaBundleState,
      String runtimeState,
      Instant lastHeartbeatAt,
      Instant expiresAt,
      String endpointRef,
      String advertisedBaseUrl,
      List<String> reasons) {}

  public boolean isConfigured() {
    return baseUrl != null;
  }

  public List<Micro6Runtime> listRuntimes() {
    JsonNode root = sendGet("/api/v1/launcher/runtimes/micro6");
    JsonNode items = root;
    if (root != null && root.isObject() && root.path("runtimes").isArray()) {
      items = root.path("runtimes");
    }
    if (items == null || !items.isArray()) {
      return List.of();
    }
    List<Micro6Runtime> runtimes = new ArrayList<>();
    items.forEach(
        item -> {
          Micro6Runtime runtime = parseRuntime(item);
          if (runtime != null) {
            runtimes.add(runtime);
          }
        });
    return runtimes;
  }

  public JsonNode resolveEndpointDocument(String endpointRef) {
    return sendGet("/api/v1/launcher/runtimes/micro6/" + encodePath(endpointRef));
  }

  public Micro6Runtime resolveRuntime(String endpointRef) {
    JsonNode node = sendGet("/api/v1/launcher/runtimes/micro6/" + encodePath(endpointRef));
    return parseRuntime(node);
  }

  /** Fetches a catalog from the registered executor server-side. Preserves 202/200/503 statuses. */
  public CatalogFetch fetchCatalog(String endpointRef, String mode) {
    if (baseUrl == null) {
      throw new Micro6RegistryException(
          "registry_not_configured", "Micro6 runtime registry base URL is not configured", null);
    }
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(
                resolveUri(
                    "/api/v1/launcher/runtimes/micro6/" + encodePath(endpointRef) + "/catalog",
                    "mode=" + mode))
            .timeout(Duration.ofMillis(this.timeoutMs))
            .GET()
            .header("Accept", "application/json");
    applyAuth(builder);
    try {
      HttpResponse<String> response =
          httpClient.send(
              builder.build(),
              com.chromascape.web.http.BoundedJsonResponse.handler(8 * 1024 * 1024));
      JsonNode body = null;
      try {
        body = objectMapper.readTree(response.body());
      } catch (Exception parseError) {
        log.warn(
            "[Micro6Registry] catalog response was not JSON from ref={} mode={}: {}",
            endpointRef,
            mode,
            parseError.getClass().getSimpleName());
      }
      ResponseIdentity identity = ResponseIdentity.from(response.headers());
      return new CatalogFetch(response.statusCode(), body, response.body(), identity);
    } catch (Exception e) {
      throw new Micro6RegistryException(
          "registry_unreachable", "Micro6 runtime registry unavailable", e);
    }
  }

  /**
   * Backend-attested response identity carried on catalog proxy responses (200/202/503). Headers
   * are parsed case-insensitively; a missing optional hash is {@code null}.
   */
  public record ResponseIdentity(
      String machineUuid,
      String executorInstanceId,
      String sessionId,
      String catalogVersion,
      String catalogHash,
      String schemaBundleHash,
      String catalogState,
      String schemaBundleState,
      Instant observedAt) {

    /** Core identity (machine/executor/session) is required on every forwarded status. */
    public boolean hasCoreIdentity() {
      return machineUuid != null && executorInstanceId != null && sessionId != null;
    }

    /**
     * Full identity expected on 200 responses: core identity plus the catalog version, a valid
     * backend-attested catalog hash, and a valid {@code observed_at}. ChromaScape never accepts a
     * 200 payload as attested identity without every applicable header.
     */
    public boolean hasFullIdentity() {
      return hasCoreIdentity()
          && catalogVersion != null
          && isValidSha256(catalogHash)
          && observedAt != null;
    }

    /** Both lifecycle states must be present on every forwarded status. */
    public boolean hasLifecycleStates() {
      return catalogState != null && schemaBundleState != null;
    }

    /** Matches {@code ^sha256:[0-9a-f]{64}$}. */
    public static boolean isValidSha256(String value) {
      return value != null && value.matches("^sha256:[0-9a-f]{64}$");
    }

    /**
     * {@code observed_at} is valid when it is present, RFC3339 (enforced at parse time), no more
     * than 30 seconds in the future, and recent enough for the runtime TTL. A timestamp within the
     * 30-second future-skew allowance is treated as age zero: the skew covers launcher/backend
     * clock differences without penalizing an otherwise fresh attestation.
     *
     * <ul>
     *   <li>Missing timestamp: invalid.
     *   <li>Null, zero, or negative TTL: invalid.
     *   <li>{@code observedAt <= now}: valid when age is at most TTL.
     *   <li>{@code now < observedAt <= now + 30s}: valid and treated as age zero.
     *   <li>{@code observedAt > now + 30s}: invalid.
     *   <li>TTL boundary is inclusive.
     * </ul>
     */
    public boolean hasValidObservedAt(Instant now, Duration ttl) {
      if (observedAt == null || now == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
        return false;
      }

      if (observedAt.isAfter(now.plusSeconds(30))) {
        return false;
      }

      if (observedAt.isAfter(now)) {
        return true;
      }

      return Duration.between(observedAt, now).compareTo(ttl) <= 0;
    }

    /**
     * The catalog/schema-bundle state combination must be one the contract matrix defines. Unknown
     * or impossible combinations (including {@code GENERATING+ERROR}, {@code READY+ERROR}, and any
     * non-AVAILABLE state carrying a bundle hash) are a permanent contract error before the body is
     * processed.
     *
     * <pre>
     * Catalog state    Allowed schema state    Bundle hash
     * READY            AVAILABLE               required
     * READY            NOT_AVAILABLE           absent
     * DEGRADED         AVAILABLE               required
     * DEGRADED         NOT_AVAILABLE           absent
     * DEGRADED         ERROR                   absent
     * GENERATING       GENERATING              absent
     * UNAVAILABLE      NOT_AVAILABLE           absent
     * UNAVAILABLE      ERROR                   absent
     * </pre>
     */
    public boolean hasValidLifecycleStates() {
      return hasValidLifecycleStates(catalogState, schemaBundleState, schemaBundleHash);
    }

    /**
     * Shared catalog/schema-bundle lifecycle matrix used for both backend response headers and
     * registry runtime attestations.
     */
    public static boolean hasValidLifecycleStates(
        String catalogState, String schemaBundleState, String schemaBundleHash) {
      if (catalogState == null || schemaBundleState == null) {
        return false;
      }
      switch (catalogState) {
        case "READY":
        case "DEGRADED":
          if ("AVAILABLE".equals(schemaBundleState)) {
            return isValidSha256(schemaBundleHash);
          }
          if ("NOT_AVAILABLE".equals(schemaBundleState)) {
            return schemaBundleHash == null;
          }
          return "DEGRADED".equals(catalogState)
              && "ERROR".equals(schemaBundleState)
              && schemaBundleHash == null;
        case "GENERATING":
          return "GENERATING".equals(schemaBundleState) && schemaBundleHash == null;
        case "UNAVAILABLE":
          if ("NOT_AVAILABLE".equals(schemaBundleState)) {
            return schemaBundleHash == null;
          }
          return "ERROR".equals(schemaBundleState) && schemaBundleHash == null;
        default:
          return false;
      }
    }

    static ResponseIdentity from(HttpHeaders headers) {
      if (headers == null) {
        return new ResponseIdentity(null, null, null, null, null, null, null, null, null);
      }
      return new ResponseIdentity(
          header(headers, "X-Micro6-Machine-Uuid"),
          header(headers, "X-Micro6-Executor-Instance-Id"),
          header(headers, "X-Micro6-Session-Id"),
          header(headers, "X-Micro6-Catalog-Version"),
          header(headers, "X-Micro6-Catalog-Hash"),
          header(headers, "X-Micro6-Schema-Bundle-Hash"),
          header(headers, "X-Micro6-Catalog-State"),
          header(headers, "X-Micro6-Schema-Bundle-State"),
          parseInstant(header(headers, "X-Micro6-Observed-At")));
    }

    private static String header(HttpHeaders headers, String name) {
      return headers.firstValue(name).map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }

    private static Instant parseInstant(String value) {
      if (value == null) {
        return null;
      }
      try {
        return Instant.parse(value);
      } catch (Exception e) {
        return null;
      }
    }
  }

  public record CatalogFetch(
      int statusCode, JsonNode body, String rawBody, ResponseIdentity identity) {}

  private JsonNode sendGet(String path) {
    if (baseUrl == null) {
      throw new Micro6RegistryException(
          "registry_not_configured", "Micro6 runtime registry base URL is not configured", null);
    }
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(resolveUri(path))
            .timeout(Duration.ofMillis(this.timeoutMs))
            .GET()
            .header("Accept", "application/json");
    applyAuth(builder);
    try {
      HttpResponse<String> response =
          httpClient.send(
              builder.build(),
              com.chromascape.web.http.BoundedJsonResponse.handler(8 * 1024 * 1024));
      if (response.statusCode() >= 400) {
        throw new Micro6RegistryException(
            "registry_http_" + response.statusCode(),
            "Micro6 runtime registry returned HTTP " + response.statusCode(),
            null);
      }
      return objectMapper.readTree(response.body());
    } catch (Micro6RegistryException e) {
      throw e;
    } catch (Exception e) {
      throw new Micro6RegistryException(
          "registry_unreachable", "Micro6 runtime registry unavailable", e);
    }
  }

  public Micro6Runtime parseEndpointDocument(JsonNode node) {
    return parseRuntime(node);
  }

  private Micro6Runtime parseRuntime(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    String machineUuid = text(node.get("machine_uuid"));
    if (machineUuid == null) {
      return null;
    }
    return new Micro6Runtime(
        machineUuid,
        text(node.get("session_id")),
        text(node.get("account_hash")),
        text(node.get("client_type")),
        text(node.get("executor_instance_id")),
        text(node.get("catalog_version")),
        text(node.get("catalog_hash")),
        text(node.get("schema_bundle_hash")),
        text(node.get("catalog_state")),
        text(node.get("schema_bundle_state")),
        text(node.get("runtime_state")),
        instant(node.get("last_heartbeat_at")),
        instant(node.get("expires_at")),
        text(node.get("endpoint_ref")),
        text(node.get("advertised_base_url")),
        stringList(node.get("reasons")));
  }

  private void applyAuth(HttpRequest.Builder builder) {
    if (authToken != null && !authToken.isBlank()) {
      builder.header("Authorization", "Bearer " + authToken);
    }
  }

  private Instant instant(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      return Instant.parse(node.asText());
    } catch (Exception e) {
      return null;
    }
  }

  private List<String> stringList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    node.forEach(
        item -> {
          String value = text(item);
          if (value != null) {
            values.add(value);
          }
        });
    return values;
  }

  /**
   * Validates and returns a safe endpoint reference for use as a single URL path segment.
   *
   * <p>The reference must never become a route or traversal vector: {@code /}, {@code ?}, {@code
   * #}, {@code %}, whitespace, and control characters are rejected outright, and {@code ..}
   * traversal is forbidden. The returned value is embedded in a path built via the multi-argument
   * {@link URI} constructor, which percent-encodes anything the path segment still requires.
   */
  private String encodePath(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    validateEndpointRef(trimmed);
    return trimmed;
  }

  private void validateEndpointRef(String ref) {
    if (ref.contains("..")) {
      throw new Micro6RegistryException(
          "endpoint_ref_invalid",
          "endpoint reference must not contain path traversal ('..')",
          null);
    }
    for (int i = 0; i < ref.length(); i++) {
      char c = ref.charAt(i);
      if (c == '/' || c == '?' || c == '#' || c == '%') {
        throw new Micro6RegistryException(
            "endpoint_ref_invalid",
            "endpoint reference must not contain reserved character '" + c + "'",
            null);
      }
      if (Character.isWhitespace(c) || Character.isISOControl(c)) {
        throw new Micro6RegistryException(
            "endpoint_ref_invalid",
            "endpoint reference must not contain whitespace or control characters",
            null);
      }
    }
  }

  /**
   * Builds a URI for a registry API path. The multi-argument constructor quotes the path so the
   * endpoint reference can never inject a new route or query component.
   */
  private URI resolveUri(String path) {
    return resolveUri(path, null);
  }

  private URI resolveUri(String path, String query) {
    URI base = URI.create(baseUrl);
    String basePath = base.getPath();
    String fullPath =
        (basePath == null || basePath.isEmpty() || "/".equals(basePath)) ? path : basePath + path;
    try {
      return new URI(base.getScheme(), null, base.getHost(), base.getPort(), fullPath, query, null);
    } catch (java.net.URISyntaxException e) {
      throw new Micro6RegistryException("endpoint_ref_invalid", "Invalid endpoint reference", e);
    }
  }

  private String text(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    String value = node.isTextual() ? node.textValue() : node.asText();
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String normalizeBaseUrl(String value) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      return null;
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  /** Structured failure carrying an operator-visible reason code. */
  public static class Micro6RegistryException extends RuntimeException {
    private final String reasonCode;

    public Micro6RegistryException(String reasonCode, String message, Throwable cause) {
      super(message, cause);
      this.reasonCode = reasonCode;
    }

    public String reasonCode() {
      return reasonCode;
    }

    public ObjectNode toJson() {
      ObjectNode node = new ObjectMapper().createObjectNode();
      node.put("error", "micro6_registry_failure");
      node.put("reason_code", reasonCode);
      node.put("message", getMessage() == null ? "unknown registry failure" : getMessage());
      return node;
    }
  }
}

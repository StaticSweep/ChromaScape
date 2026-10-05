package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.integration.micro6.service.Micro6RuntimeRegistryClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Resolves references against the authenticated registry; advertised URLs never select a route. */
@Service
public class BuilderGoalEndpointAuthority {
  private final java.util.concurrent.Semaphore probes = new java.util.concurrent.Semaphore(8);
  private volatile Instant retryAfter = Instant.EPOCH;
  private final java.util.concurrent.atomic.AtomicInteger failures =
      new java.util.concurrent.atomic.AtomicInteger();
  private final Micro6RuntimeRegistryClient registry;
  private final ExecutorCertificateTelemetry certificates = new ExecutorCertificateTelemetry();
  private final Set<String> origins;
  private final String devOrigin;
  private final boolean development;
  private final String environment;
  private final boolean registryAuthenticated;
  private final boolean staticEnabled;

  public BuilderGoalEndpointAuthority(
      ObjectMapper mapper,
      @Value("${micro6.runtime-selection.registry-base-url:http://127.0.0.1:8084}")
          String registryUrl,
      @Value("${micro6.runtime-selection.registry-auth-token:}") String registryToken,
      @Value("${builder-goal.topology.environment:DEV}") String environment,
      @Value("${chromascape.patchloop.micro6-executor.base-url:http://127.0.0.1:8098}")
          String devOrigin,
      @Value("${builder-goal.executor.trusted-origins:}") String allowedOrigins,
      @Value("${builder-goal.topology.dev-static-enabled:false}") boolean staticEnabled) {
    this.registry = new Micro6RuntimeRegistryClient(mapper, registryUrl, registryToken, 5000);
    this.environment = environment;
    this.development = "DEV".equals(environment);
    this.staticEnabled = staticEnabled;
    this.registryAuthenticated =
        registryToken != null
            && registryToken.length() >= 32
            && registryToken.length() <= 256
            && registryToken.matches("[A-Za-z0-9_-]+");
    this.devOrigin = normalize(devOrigin);
    if (!development && (allowedOrigins.isBlank() || allowedOrigins.contains("*")))
      throw new IllegalStateException("TOPOLOGY_ORIGIN_POLICY_REQUIRED");
    this.origins =
        java.util.Arrays.stream(allowedOrigins.split(","))
            .map(BuilderGoalEndpointAuthority::normalize)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (!development && this.origins.isEmpty())
      throw new IllegalStateException("TOPOLOGY_ORIGIN_POLICY_REQUIRED");
  }

  /** Capture peer dates only for a provisioned origin after the normal TLS handshake succeeds. */
  public void observeCertificate(java.net.http.HttpResponse<?> response) {
    if (response == null || response.uri() == null) return;
    var uri = response.uri();
    String origin;
    try {
      origin =
          normalize(
              new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null)
                  .toString());
    } catch (Exception invalid) {
      return;
    }
    if (approvedCertificateOrigin(origin)) certificates.observe(origin, response);
  }

  public java.util.Map<String, Object> certificateSummary(String origin) {
    String normalized = normalize(origin);
    return certificates.summary(approvedCertificateOrigin(normalized) ? normalized : null);
  }

  private boolean approvedCertificateOrigin(String origin) {
    return origin != null
        && (origins.contains(origin) || (development && staticEnabled && origin.equals(devOrigin)));
  }

  public String environment() {
    return environment;
  }

  /** Null means no route authority; callers must not substitute a URL from run metadata. */
  public String resolve(JsonNode identity) {
    if (identity == null || !identity.isObject()) return null;
    String ref = text(identity, "endpoint_ref");
    if (ref == null) {
      // Explicit local DEV route is server configuration, never the advertised heartbeat URL.
      return development
              && staticEnabled
              && "DEV_STATIC_ROUTE".equals(text(identity, "route_source"))
          ? devOrigin
          : null;
    }
    if (!registryAuthenticated || Instant.now().isBefore(retryAfter) || !probes.tryAcquire())
      return null;
    try {
      JsonNode document = registry.resolveEndpointDocument(ref);
      ExecutorEndpointRef grant = ExecutorEndpointRef.from(document, environment);
      if (grant == null || !grant.matches(identity)) return null;
      // Resolve once: document parsing and route identity use the same authenticated snapshot.
      var current = registry.parseEndpointDocument(document);
      if (current == null
          || !ref.equals(current.endpointRef())
          || !"ONLINE".equals(current.runtimeState())
          || current.expiresAt() == null
          || !current.expiresAt().isAfter(Instant.now())
          || current.lastHeartbeatAt() == null
          || current.lastHeartbeatAt().isBefore(Instant.now().minusSeconds(30))
          || current.lastHeartbeatAt().isAfter(Instant.now().plusSeconds(30))
          || !matches(identity, "executor_instance_id", current.executorInstanceId())
          || !matches(identity, "machine_uuid", current.machineUuid())
          || !matches(identity, "launcher_session_id", current.sessionId())
          || !matches(identity, "account_hash", current.accountHash())) return null;
      String route = normalize(current.advertisedBaseUrl());
      // Only explicitly provisioned origins are dialable even when the issuer is authenticated.
      if (route == null || !origins.contains(route)) return null;
      if (!development
          && (!route.startsWith("https://")
              || java.net.InetAddress.getByName(URI.create(route).getHost()).isLoopbackAddress()))
        return null;
      failures.set(0);
      return route;
    } catch (Exception unavailable) {
      retryAfter =
          Instant.now().plusSeconds(Math.min(30, 1L << Math.min(5, failures.getAndIncrement())));
      return null;
    } finally {
      probes.release();
    }
  }

  private static boolean matches(JsonNode n, String key, String expected) {
    String actual = text(n, key);
    return actual != null && expected != null && actual.equals(expected);
  }

  private static String text(JsonNode n, String key) {
    if (n == null) return null;
    JsonNode v = n.path(key);
    return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
  }

  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      URI u = URI.create(raw.trim());
      String host = u.getHost();
      if (host == null
          || u.getUserInfo() != null
          || u.getRawQuery() != null
          || u.getRawFragment() != null
          || u.getPort() < 1
          || u.getPort() > 65535
          || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))
          || !(u.getPath().isEmpty() || "/".equals(u.getPath()))) return null;
      // Literal addresses only: no DNS lookup or time-of-check/time-of-connect rebinding window.
      String h = host.replace("[", "").replace("]", "");
      if ("localhost".equals(h)) h = "127.0.0.1";
      else if (!h.matches("[0-9.]+") && !h.contains(":")) return null;
      if (h.contains("%")) return null;
      var ip = java.net.InetAddress.getByName(h);
      if (!h.contains(":") && !h.equals(ip.getHostAddress())) return null;
      if (ip.isAnyLocalAddress()
          || ip.isLinkLocalAddress()
          || ip.isMulticastAddress()
          || (!ip.isLoopbackAddress()
              && !ip.isSiteLocalAddress()
              && !(ip.getAddress().length == 4
                  && Byte.toUnsignedInt(ip.getAddress()[0]) == 100
                  && Byte.toUnsignedInt(ip.getAddress()[1]) >= 64
                  && Byte.toUnsignedInt(ip.getAddress()[1]) <= 127))) return null;
      return new URI(u.getScheme(), null, ip.getHostAddress(), u.getPort(), null, null, null)
          .toString();
    } catch (Exception invalid) {
      return null;
    }
  }
}

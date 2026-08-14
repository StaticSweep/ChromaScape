package com.chromascape.web.accounttask.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Lightweight authorization guard for stabilization endpoints. Requires an {@code
 * X-Stabilization-Auth} header equal to the configured {@code stabilization.auth.token} (read
 * access) or {@code stabilization.auth.write-token} (write access, e.g. triage resolution). When no
 * token is configured (local development), requests are only permitted from loopback sources, so
 * unauthenticated cross-host access to harness manifests, matrices, runs, evidence, KPI, triage and
 * the catalog is never possible. Log/evidence access is never served to unauthenticated callers.
 *
 * <p>Request bodies beyond {@code stabilization.auth.max-json-bytes} are rejected with 413 before
 * any controller parses them, bounding ingestion memory and parser exposure.
 */
@Component
public class StabilizationAuthInterceptor implements HandlerInterceptor {

  private static final String AUTH_HEADER = "X-Stabilization-Auth";
  private static final String TRIAGE_RESOLVE_SUFFIX = "/resolve";
  private static final long DEFAULT_MAX_JSON_BYTES = 5_000_000L;

  private final String authToken;
  private final String writeToken;
  private final long maxJsonBytes;
  private final boolean localDevelopment;

  @Autowired
  public StabilizationAuthInterceptor(
      @Value("${stabilization.auth.token:}") String authToken,
      @Value("${stabilization.auth.write-token:}") String writeToken,
      @Value("${stabilization.auth.max-json-bytes:5000000}") long maxJsonBytes,
      Environment environment) {
    this.authToken = authToken == null ? "" : authToken.trim();
    this.writeToken = writeToken == null ? "" : writeToken.trim();
    this.maxJsonBytes = maxJsonBytes <= 0 ? DEFAULT_MAX_JSON_BYTES : maxJsonBytes;
    this.localDevelopment =
        Arrays.asList(environment.getActiveProfiles()).contains("local-development");
    if (!localDevelopment && (this.authToken.isBlank() || this.writeToken.isBlank())) {
      throw new IllegalStateException(
          "stabilization read and write tokens are required outside local-development");
    }
  }

  /** Compatibility constructor for tests and call sites that configure only the read token. */
  public StabilizationAuthInterceptor(String authToken) {
    this(authToken, "", DEFAULT_MAX_JSON_BYTES, localDevelopmentEnvironment());
  }

  /** Compatibility constructor used by focused MVC tests; production uses the injected profile. */
  public StabilizationAuthInterceptor(String authToken, String writeToken, long maxJsonBytes) {
    this(authToken, writeToken, maxJsonBytes, localDevelopmentEnvironment());
  }

  private static Environment localDevelopmentEnvironment() {
    org.springframework.core.env.StandardEnvironment environment =
        new org.springframework.core.env.StandardEnvironment();
    environment.setActiveProfiles("local-development");
    return environment;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (request.getContentLengthLong() > maxJsonBytes) {
      response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"request_body_too_large\"}");
      return false;
    }

    String supplied = request.getHeader(AUTH_HEADER);
    boolean writeRequest = isWriteRequest(request);
    String requiredToken = writeRequest ? writeToken : authToken;

    if (!requiredToken.isBlank()) {
      if (requiredToken.equals(supplied)) {
        return true;
      }
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"unauthorized\"}");
      return false;
    }
    // No token configured: only the explicit local-development profile receives this fallback.
    if (!localDevelopment) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"unauthorized_environment\"}");
      return false;
    }
    if (isLoopback(request.getRemoteAddr())) {
      return true;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"unauthorized_environment\"}");
    return false;
  }

  /** Every POST mutates state, including complete report ingestion. */
  private static boolean isWriteRequest(HttpServletRequest request) {
    String method = request.getMethod();
    return method != null && "POST".equalsIgnoreCase(method);
  }

  private static boolean isLoopback(String address) {
    if (address == null) {
      return false;
    }
    if ("localhost".equalsIgnoreCase(address)
        || "::1".equals(address)
        || "0:0:0:0:0:0:0:1".equals(address)) {
      return true;
    }
    String[] octets = address.split("\\.");
    if (octets.length != 4 || !"127".equals(octets[0])) {
      return false;
    }
    return Arrays.stream(octets).skip(1).allMatch(o -> o.matches("\\d{1,3}"));
  }
}

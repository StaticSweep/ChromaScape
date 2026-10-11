package com.chromascape.web.debug.buildergoal.service;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Trusted deployment-topology endpoint policy for Builder Goal executor dial-out (SC-802).
 *
 * <p>A Micro6 heartbeat self-reports {@code advertised_base_url}, so that URL is observational
 * metadata, never an implicit dial target. Every executor dial-out (catalog fetch, health probe,
 * publish, status, stop, control) must resolve through this policy: only {@code http}/{@code https}
 * URLs without credentials, with an explicit port, an empty path, and a host inside the trusted
 * deployment topology (loopback plus the hosts of the configured launcher, executor, and registry
 * base URLs plus explicit extra hosts) are dialable. Anything else fails closed with an explicit
 * reason and must never fall back to a default URL for a registry-selected run.
 */
public final class TrustedExecutorEndpoint {

  /** Reason code when no endpoint URL was provided at all. */
  public static final String REASON_MISSING = "ENDPOINT_MISSING";

  /** Reason code when an endpoint URL failed the trust policy. */
  public static final String REASON_UNTRUSTED = "ENDPOINT_UNTRUSTED";

  /** Hosts that are always trusted: loopback only. */
  public static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1");

  /**
   * Default launcher base URL whose host joins the trusted topology when nothing is configured.
   * Mirrors {@code builder-goal.launcher.base-url}.
   */
  public static final String DEFAULT_LAUNCHER_BASE_URL = "http://192.168.1.6:8084";

  /**
   * Default executor base URL whose host joins the trusted topology when nothing is configured.
   * Mirrors {@code chromascape.patchloop.micro6-executor.base-url}.
   */
  public static final String DEFAULT_EXECUTOR_BASE_URL = "http://127.0.0.1:8098";

  /** Trust verdict for one endpoint URL. */
  public record Verdict(boolean trusted, String normalizedUrl, String reasonCode, String reason) {

    static Verdict trusted(String normalizedUrl) {
      return new Verdict(true, normalizedUrl, null, null);
    }

    static Verdict missing() {
      return new Verdict(false, null, REASON_MISSING, "No executor endpoint URL was provided");
    }

    static Verdict untrusted(String reason) {
      return new Verdict(false, null, REASON_UNTRUSTED, reason);
    }
  }

  private TrustedExecutorEndpoint() {}

  /**
   * Evaluates one endpoint URL against the trusted topology. Never throws: every failure mode
   * returns an untrusted verdict.
   *
   * @param rawUrl candidate endpoint URL, may be null
   * @param trustedHosts additional trusted hosts (compared case-insensitively, brackets ignored);
   *     loopback hosts are always trusted and need not be listed
   */
  public static Verdict evaluate(String rawUrl, Set<String> trustedHosts) {
    if (rawUrl == null || rawUrl.isBlank()) {
      return Verdict.missing();
    }
    String candidate = rawUrl.trim();
    while (candidate.endsWith("/")) {
      candidate = candidate.substring(0, candidate.length() - 1);
    }
    if (candidate.isBlank()) {
      return Verdict.missing();
    }
    URI uri;
    try {
      uri = new URI(candidate);
    } catch (Exception e) {
      return Verdict.untrusted("Executor endpoint URL is not a valid URI");
    }
    String scheme = uri.getScheme();
    if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
      return Verdict.untrusted("Executor endpoint URL must use http or https");
    }
    if (uri.getUserInfo() != null) {
      return Verdict.untrusted("Executor endpoint URL must not embed credentials");
    }
    String host = normalizeHost(uri.getHost());
    if ("localhost".equals(host)) host = "127.0.0.1";
    if (host != null && !host.matches("[0-9.]+") && !host.contains(":"))
      return Verdict.untrusted("Executor DNS origins require a pinned allocation proxy");
    if (host != null) {
      try {
        var address = java.net.InetAddress.getByName(host);
        if (address.isAnyLocalAddress()
            || address.isLinkLocalAddress()
            || address.isMulticastAddress()
            || (!address.isLoopbackAddress()
                && !address.isSiteLocalAddress()
                && !(address.getAddress().length == 4
                    && Byte.toUnsignedInt(address.getAddress()[0]) == 100
                    && Byte.toUnsignedInt(address.getAddress()[1]) >= 64
                    && Byte.toUnsignedInt(address.getAddress()[1]) <= 127)))
          return Verdict.untrusted("Executor address class is forbidden");
      } catch (Exception invalid) {
        return Verdict.untrusted("Executor address is invalid");
      }
    }
    if (host == null) {
      return Verdict.untrusted("Executor endpoint URL must have a valid host");
    }
    int port = uri.getPort();
    String origin =
        scheme.toLowerCase(Locale.ROOT)
            + "://"
            + (host.contains(":") ? "[" + host + "]" : host)
            + ":"
            + port;
    boolean exactOrigin = trustedHosts != null && trustedHosts.contains(origin);
    if (port != 8098 && !exactOrigin) {
      return Verdict.untrusted(
          "Executor endpoint port is not approved; the development executor port is 8098");
    }
    String path = uri.getRawPath();
    if ((path != null && !path.isEmpty())
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      return Verdict.untrusted("Executor endpoint URL must be a bare host endpoint");
    }
    if (exactOrigin
        || LOOPBACK_HOSTS.contains(host)
        || normalizeHosts(trustedHosts).contains(host)) {
      return Verdict.trusted(
          scheme.toLowerCase(Locale.ROOT)
              + "://"
              + (host.contains(":") ? "[" + host + "]" : host)
              + ":"
              + port);
    }
    return Verdict.untrusted("Executor endpoint host is outside the trusted deployment topology");
  }

  /** Extracts the lowercased hosts of the given base URLs, silently skipping unparseable ones. */
  public static Set<String> trustedHostsFromBaseUrls(String... baseUrls) {
    Set<String> hosts = new LinkedHashSet<>();
    if (baseUrls == null) {
      return hosts;
    }
    for (String baseUrl : baseUrls) {
      if (baseUrl == null || baseUrl.isBlank()) {
        continue;
      }
      try {
        String host = normalizeHost(new URI(baseUrl.trim()).getHost());
        if (host != null) {
          hosts.add(host);
        }
      } catch (Exception ignored) {
        // Unparseable configured URL contributes no trusted host; the endpoint policy still
        // rejects anything outside the remaining topology.
      }
    }
    return hosts;
  }

  /**
   * Parses a comma-separated extra-hosts value into lowercased hosts. Tokens containing whitespace,
   * slashes, or credential separators are dropped instead of trusted.
   */
  public static Set<String> parseExtraHosts(String csv) {
    Set<String> hosts = new LinkedHashSet<>();
    if (csv == null || csv.isBlank()) {
      return hosts;
    }
    for (String token : csv.split(",")) {
      String host = normalizeHost(token);
      if (host == null || host.contains("/") || host.contains("@") || host.contains("?")) {
        continue;
      }
      hosts.add(host);
    }
    return hosts;
  }

  /** Builds the full trusted-host set: loopback plus topology hosts plus explicit extras. */
  public static Set<String> buildTrustedHosts(Set<String> topologyHosts, Set<String> extraHosts) {
    Set<String> hosts = new LinkedHashSet<>(LOOPBACK_HOSTS);
    hosts.addAll(normalizeHosts(topologyHosts));
    hosts.addAll(normalizeHosts(extraHosts));
    return Collections.unmodifiableSet(hosts);
  }

  static String normalizeHost(String host) {
    if (host == null || host.isBlank()) {
      return null;
    }
    String normalized = host.trim().toLowerCase(Locale.ROOT);
    if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    return normalized.isEmpty() ? null : normalized;
  }

  private static Set<String> normalizeHosts(Set<String> hosts) {
    Set<String> normalized = new LinkedHashSet<>();
    if (hosts == null) {
      return normalized;
    }
    for (String host : hosts) {
      String value = normalizeHost(host);
      if (value != null) {
        normalized.add(value);
      }
    }
    return normalized;
  }
}

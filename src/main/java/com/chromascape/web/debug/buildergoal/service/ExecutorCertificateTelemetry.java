package com.chromascape.web.debug.buildergoal.service;

import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Passive telemetry from verified HTTP TLS sessions; never a substitute for TLS validation. */
final class ExecutorCertificateTelemetry {
  private static final int CAPACITY = 512;
  private static final Duration RETENTION = Duration.ofHours(24);
  private static final Duration FRESHNESS = Duration.ofMinutes(5);
  private final Clock clock;
  private final Map<String, Observation> observations = new LinkedHashMap<>();

  ExecutorCertificateTelemetry() {
    this(Clock.systemUTC());
  }

  ExecutorCertificateTelemetry(Clock clock) {
    this.clock = clock;
  }

  synchronized void observe(String origin, HttpResponse<?> response) {
    if (origin == null || response == null || !origin.startsWith("https://")) return;
    try {
      var session = response.sslSession();
      if (session.isEmpty()) return;
      Instant notBefore = Instant.MIN;
      Instant notAfter = Instant.MAX;
      boolean found = false;
      for (var certificate : session.get().getPeerCertificates()) {
        if (!(certificate instanceof X509Certificate x509)) return;
        notBefore =
            notBefore.isAfter(x509.getNotBefore().toInstant())
                ? notBefore
                : x509.getNotBefore().toInstant();
        notAfter =
            notAfter.isBefore(x509.getNotAfter().toInstant())
                ? notAfter
                : x509.getNotAfter().toInstant();
        found = true;
      }
      if (!found) return;
      Instant now = clock.instant();
      observations
          .entrySet()
          .removeIf(e -> e.getValue().observedAt().isBefore(now.minus(RETENTION)));
      observations.remove(origin);
      while (observations.size() >= CAPACITY)
        observations.remove(observations.keySet().iterator().next());
      observations.put(origin, new Observation(notBefore, notAfter, now));
    } catch (javax.net.ssl.SSLPeerUnverifiedException unavailable) {
      // No verified peer means no observation. Never record a certificate from an exception.
    }
  }

  synchronized Map<String, Object> summary(String origin) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("certificateExpiryClass", "NOT_OBSERVED");
    Observation observation = observations.get(origin);
    Instant now = clock.instant();
    if (observation == null) return result;
    if (observation.observedAt().isBefore(now.minus(RETENTION))) {
      observations.remove(origin);
      return result;
    }
    String classification =
        !now.isBefore(observation.notAfter())
            ? "EXPIRED"
            : now.isBefore(observation.notBefore())
                ? "NOT_YET_VALID"
                : observation.observedAt().isBefore(now.minus(FRESHNESS))
                    ? "OBSERVATION_STALE"
                    : observation.notAfter().isBefore(now.plus(Duration.ofDays(7)))
                        ? "EXPIRING_SOON"
                        : "VALID";
    result.put("certificateExpiryClass", classification);
    result.put("certificateNotBefore", observation.notBefore().toString());
    result.put("certificateNotAfter", observation.notAfter().toString());
    result.put("certificateObservedAt", observation.observedAt().toString());
    result.put("certificateObservationSource", "VERIFIED_TLS_SESSION");
    return result;
  }

  private record Observation(Instant notBefore, Instant notAfter, Instant observedAt) {}
}

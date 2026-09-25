package com.chromascape.web.integration.micro6.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.integration.micro6.service.Micro6RuntimeRegistryClient.ResponseIdentity;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for the SC-584A lifecycle matrix and full-identity attestation rules enforced by
 * {@link ResponseIdentity}. The matrix is shared with the launcher and the backend: any combination
 * outside the table (including GENERATING+ERROR, READY+ERROR, and any non-AVAILABLE state carrying
 * a bundle hash) is a permanent contract error.
 */
class Micro6ResponseIdentityValidationTest {
  private static final String HASH_A = "sha256:" + "a".repeat(64);
  private static final String HASH_B = "sha256:" + "b".repeat(64);
  private static final Duration TTL = Duration.ofMinutes(2);

  private static ResponseIdentity identity(
      String catalogState, String schemaState, String bundleHash) {
    return new ResponseIdentity(
        "machine-a",
        "executor-a",
        "session-a",
        "2",
        HASH_A,
        bundleHash,
        catalogState,
        schemaState,
        Instant.now());
  }

  private static ResponseIdentity with(ResponseIdentity base, String... pairs) {
    String machine = base.machineUuid();
    String executor = base.executorInstanceId();
    String session = base.sessionId();
    String version = base.catalogVersion();
    String catalogHash = base.catalogHash();
    String bundleHash = base.schemaBundleHash();
    String catalogState = base.catalogState();
    String schemaState = base.schemaBundleState();
    Instant observedAt = base.observedAt();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      switch (pairs[i]) {
        case "machine":
          machine = pairs[i + 1];
          break;
        case "executor":
          executor = pairs[i + 1];
          break;
        case "session":
          session = pairs[i + 1];
          break;
        case "version":
          version = pairs[i + 1];
          break;
        case "catalogHash":
          catalogHash = pairs[i + 1];
          break;
        case "bundleHash":
          bundleHash = pairs[i + 1];
          break;
        case "catalogState":
          catalogState = pairs[i + 1];
          break;
        case "schemaState":
          schemaState = pairs[i + 1];
          break;
        case "observedAt":
          observedAt = pairs[i + 1].isEmpty() ? null : Instant.parse(pairs[i + 1]);
          break;
        default:
          throw new IllegalArgumentException("unknown key " + pairs[i]);
      }
    }
    return new ResponseIdentity(
        machine,
        executor,
        session,
        version,
        catalogHash,
        bundleHash,
        catalogState,
        schemaState,
        observedAt);
  }

  @Test
  void fullIdentityRequiresCatalogVersionCatalogHashAndObservedAt() {
    ResponseIdentity base = identity("READY", "AVAILABLE", HASH_B);
    assertThat(base.hasFullIdentity()).isTrue();

    assertThat(with(base, "catalogHash", null).hasFullIdentity()).isFalse();
    assertThat(with(base, "catalogHash", "sha256:short").hasFullIdentity()).isFalse();
    assertThat(with(base, "version", null).hasFullIdentity()).isFalse();
    assertThat(with(base, "observedAt", "").hasFullIdentity()).isFalse();
    assertThat(with(base, "executor", null).hasFullIdentity()).isFalse();
    assertThat(with(base, "catalogHash", "md5:" + "a".repeat(64)).hasFullIdentity()).isFalse();
  }

  @Test
  void acceptedLifecycleMatrix() {
    assertThat(identity("READY", "AVAILABLE", HASH_B).hasValidLifecycleStates()).isTrue();
    assertThat(identity("READY", "NOT_AVAILABLE", null).hasValidLifecycleStates()).isTrue();
    assertThat(identity("DEGRADED", "AVAILABLE", HASH_B).hasValidLifecycleStates()).isTrue();
    assertThat(identity("DEGRADED", "NOT_AVAILABLE", null).hasValidLifecycleStates()).isTrue();
    assertThat(identity("DEGRADED", "ERROR", null).hasValidLifecycleStates()).isTrue();
    assertThat(identity("GENERATING", "GENERATING", null).hasValidLifecycleStates()).isTrue();
    assertThat(identity("UNAVAILABLE", "NOT_AVAILABLE", null).hasValidLifecycleStates()).isTrue();
    assertThat(identity("UNAVAILABLE", "ERROR", null).hasValidLifecycleStates()).isTrue();
  }

  @Test
  void rejectedLifecycleMatrix() {
    assertThat(identity("GENERATING", "ERROR", null).hasValidLifecycleStates()).isFalse();
    assertThat(identity("READY", "ERROR", null).hasValidLifecycleStates()).isFalse();
    assertThat(identity("READY", "GENERATING", null).hasValidLifecycleStates()).isFalse();
    assertThat(identity("GENERATING", "AVAILABLE", HASH_B).hasValidLifecycleStates()).isFalse();
    assertThat(identity("UNAVAILABLE", "AVAILABLE", HASH_B).hasValidLifecycleStates()).isFalse();
    assertThat(identity("READY", "AVAILABLE", null).hasValidLifecycleStates()).isFalse();
    assertThat(identity("READY", "AVAILABLE", "sha256:short").hasValidLifecycleStates()).isFalse();
    assertThat(identity("READY", "NOT_AVAILABLE", HASH_B).hasValidLifecycleStates()).isFalse();
    assertThat(identity("GENERATING", "GENERATING", HASH_B).hasValidLifecycleStates()).isFalse();
    assertThat(identity("DEGRADED", "ERROR", HASH_B).hasValidLifecycleStates()).isFalse();
    assertThat(identity("UNAVAILABLE", "ERROR", HASH_B).hasValidLifecycleStates()).isFalse();
    assertThat(identity("WHATEVER", "NOT_AVAILABLE", null).hasValidLifecycleStates()).isFalse();
    assertThat(identity("READY", "WHATEVER", null).hasValidLifecycleStates()).isFalse();
  }

  @Test
  void observedAtMustBeFreshAndWithinSkew() {
    Instant now = Instant.now();
    ResponseIdentity base =
        with(identity("READY", "NOT_AVAILABLE", null), "observedAt", now.toString());
    assertThat(base.hasValidObservedAt(now, TTL)).isTrue();
    // A minute-old attestation is still within the 2-minute runtime TTL.
    assertThat(
            with(base, "observedAt", now.minus(Duration.ofMinutes(1)).toString())
                .hasValidObservedAt(now, TTL))
        .isTrue();
    // More than the TTL old is stale.
    assertThat(
            with(base, "observedAt", now.minus(Duration.ofMinutes(3)).toString())
                .hasValidObservedAt(now, TTL))
        .isFalse();
    // More than 30 seconds in the future is invalid.
    assertThat(
            with(base, "observedAt", now.plus(Duration.ofMinutes(1)).toString())
                .hasValidObservedAt(now, TTL))
        .isFalse();
    // Missing observed_at is invalid.
    assertThat(with(base, "observedAt", "").hasValidObservedAt(now, TTL)).isFalse();
  }

  @Test
  void observedAtHonorsTheThirtySecondFutureSkewBoundary() {
    Instant now = Instant.now();
    ResponseIdentity base =
        with(identity("READY", "NOT_AVAILABLE", null), "observedAt", now.toString());

    // Exactly now is valid.
    assertThat(base.hasValidObservedAt(now, TTL)).isTrue();

    // Future timestamps inside the 30s skew are valid and treated as age zero.
    assertThat(
            with(base, "observedAt", now.plusSeconds(29).toString()).hasValidObservedAt(now, TTL))
        .isTrue();
    assertThat(
            with(base, "observedAt", now.plusSeconds(30).toString()).hasValidObservedAt(now, TTL))
        .isTrue();

    // Past the 30s skew, even by one nanosecond, is invalid.
    assertThat(
            with(base, "observedAt", now.plusSeconds(30).plusNanos(1).toString())
                .hasValidObservedAt(now, TTL))
        .isFalse();
  }

  @Test
  void observedAtHonorsTheInclusiveTtlBoundary() {
    Instant now = Instant.now();
    ResponseIdentity base =
        with(identity("READY", "NOT_AVAILABLE", null), "observedAt", now.toString());

    // Exactly TTL old is still valid (inclusive boundary).
    assertThat(with(base, "observedAt", now.minus(TTL).toString()).hasValidObservedAt(now, TTL))
        .isTrue();

    // One nanosecond older than the TTL is stale.
    assertThat(
            with(base, "observedAt", now.minus(TTL).minusNanos(1).toString())
                .hasValidObservedAt(now, TTL))
        .isFalse();
  }

  @Test
  void observedAtRejectsInvalidTtlAndNullArguments() {
    Instant now = Instant.now();
    ResponseIdentity base =
        with(identity("READY", "NOT_AVAILABLE", null), "observedAt", now.toString());

    // Zero, negative, and null TTL are invalid.
    assertThat(base.hasValidObservedAt(now, Duration.ZERO)).isFalse();
    assertThat(base.hasValidObservedAt(now, Duration.ofSeconds(-1))).isFalse();
    assertThat(base.hasValidObservedAt(now, null)).isFalse();

    // Null now and a missing observed_at are invalid.
    assertThat(base.hasValidObservedAt(null, TTL)).isFalse();
    assertThat(with(base, "observedAt", "").hasValidObservedAt(now, TTL)).isFalse();
  }
}

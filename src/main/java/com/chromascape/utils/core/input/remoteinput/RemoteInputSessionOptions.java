package com.chromascape.utils.core.input.remoteinput;

import java.time.Duration;

/**
 * Immutable, bounded options for RemoteInput attachment and native calls.
 *
 * <p>Every duration is a hard deadline. {@link #injectTimeout()} in particular is the
 * <em>total</em> budget for attachment: the native {@code EIOS_Inject_PID} call and every
 * subsequent client poll share it, so a slow native call can never silently extend the wait. The
 * injected library needs roughly 5 s for the JVM to become available plus up to about 20 s for the
 * AWT/window/reflection phase, so the default deadline covers that ~25 s interval with polling
 * margin.
 *
 * <p>The record rejects zero, negative, and unbounded values so no caller can construct an
 * effectively infinite wait; operator-facing minimums live in the typed configuration.
 */
public record RemoteInputSessionOptions(
    boolean attachmentEnabled,
    Duration injectTimeout,
    Duration pairTimeout,
    Duration nativeCallTimeout,
    Duration releaseTimeout,
    Duration validationInterval,
    int maxClients) {

  /** Longest total attachment deadline any configuration may request. */
  public static final Duration MAX_INJECT_TIMEOUT = Duration.ofSeconds(60);

  /** Longest pairing deadline any configuration may request. */
  public static final Duration MAX_PAIR_TIMEOUT = Duration.ofSeconds(30);

  /** Longest single native call deadline any configuration may request. */
  public static final Duration MAX_NATIVE_CALL_TIMEOUT = Duration.ofSeconds(10);

  /** Longest release deadline any configuration may request. */
  public static final Duration MAX_RELEASE_TIMEOUT = Duration.ofSeconds(10);

  /** Longest client-poll interval any configuration may request. */
  public static final Duration MAX_VALIDATION_INTERVAL = Duration.ofSeconds(1);

  /** Largest native client count treated as plausible. */
  public static final int MAX_CLIENTS = 4096;

  /** Conservative defaults; attachment itself is disabled until explicitly enabled. */
  public static final RemoteInputSessionOptions DEFAULT =
      new RemoteInputSessionOptions(
          false,
          Duration.ofSeconds(30),
          Duration.ofSeconds(10),
          Duration.ofSeconds(2),
          Duration.ofSeconds(3),
          Duration.ofMillis(250),
          MAX_CLIENTS);

  /** Validates that all configured bounds are present, strictly positive, and not unbounded. */
  public RemoteInputSessionOptions {
    requirePositive(injectTimeout, "injectTimeout");
    requirePositive(pairTimeout, "pairTimeout");
    requirePositive(nativeCallTimeout, "nativeCallTimeout");
    requirePositive(releaseTimeout, "releaseTimeout");
    requirePositive(validationInterval, "validationInterval");
    requireAtMost(injectTimeout, MAX_INJECT_TIMEOUT, "injectTimeout");
    requireAtMost(pairTimeout, MAX_PAIR_TIMEOUT, "pairTimeout");
    requireAtMost(nativeCallTimeout, MAX_NATIVE_CALL_TIMEOUT, "nativeCallTimeout");
    requireAtMost(releaseTimeout, MAX_RELEASE_TIMEOUT, "releaseTimeout");
    requireAtMost(validationInterval, MAX_VALIDATION_INTERVAL, "validationInterval");
    if (maxClients <= 0 || maxClients > MAX_CLIENTS) {
      throw new IllegalArgumentException(
          "RemoteInput maxClients must be between 1 and " + MAX_CLIENTS + " but was " + maxClients);
    }
    if (validationInterval.compareTo(injectTimeout) > 0) {
      throw new IllegalArgumentException(
          "RemoteInput validationInterval "
              + validationInterval
              + " must not exceed the injection deadline "
              + injectTimeout);
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(
          "RemoteInput " + name + " must be a positive duration but was " + value);
    }
  }

  private static void requireAtMost(Duration value, Duration max, String name) {
    if (value.compareTo(max) > 0) {
      throw new IllegalArgumentException(
          "RemoteInput " + name + " must not exceed " + max + " but was " + value);
    }
  }
}

package com.chromascape.utils.core.input.remoteinput;

import java.time.Duration;

/** Immutable, bounded options for RemoteInput attachment and native calls. */
public record RemoteInputSessionOptions(
    boolean attachmentEnabled,
    Duration injectTimeout,
    Duration pairTimeout,
    Duration nativeCallTimeout,
    Duration releaseTimeout,
    Duration validationInterval,
    int maxClients) {

  /** Conservative defaults; attachment itself is disabled until explicitly enabled. */
  public static final RemoteInputSessionOptions DEFAULT =
      new RemoteInputSessionOptions(
          false,
          Duration.ofSeconds(10),
          Duration.ofSeconds(10),
          Duration.ofSeconds(2),
          Duration.ofSeconds(3),
          Duration.ofMillis(250),
          4096);

  /** Validates that all configured bounds are present and strictly positive. */
  public RemoteInputSessionOptions {
    if (injectTimeout == null
        || pairTimeout == null
        || nativeCallTimeout == null
        || releaseTimeout == null
        || validationInterval == null
        || injectTimeout.isZero()
        || injectTimeout.isNegative()
        || pairTimeout.isZero()
        || pairTimeout.isNegative()
        || nativeCallTimeout.isZero()
        || nativeCallTimeout.isNegative()
        || releaseTimeout.isZero()
        || releaseTimeout.isNegative()
        || validationInterval.isZero()
        || validationInterval.isNegative()
        || maxClients <= 0) {
      throw new IllegalArgumentException("RemoteInput options must be bounded and positive");
    }
  }
}

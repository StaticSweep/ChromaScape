package com.chromascape.utils.core.input.remoteinput;

import com.chromascape.utils.core.screen.capture.FrameFreshness;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/** Per-attachment content fingerprint tracker; it never retains native or caller-owned pixels. */
final class FrameFreshnessTracker {
  record Observation(FrameFreshness freshness, Instant observedAt, Instant contentChangedAt) {}

  private final Duration pauseThreshold;
  private final Clock clock;
  private byte[] lastDigest;
  private Instant contentChangedAt;

  FrameFreshnessTracker(Duration pauseThreshold, Clock clock) {
    if (pauseThreshold == null || pauseThreshold.isNegative() || pauseThreshold.isZero()) {
      throw new IllegalArgumentException("Pause threshold must be positive");
    }
    this.pauseThreshold = pauseThreshold;
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
  }

  Observation observe(byte[] pixels) {
    Instant now = clock.instant();
    byte[] digest = sha256(pixels);
    FrameFreshness freshness;
    if (lastDigest == null) {
      freshness = FrameFreshness.NEW;
      contentChangedAt = now;
    } else if (!Arrays.equals(lastDigest, digest)) {
      freshness = FrameFreshness.CHANGED;
      contentChangedAt = now;
    } else if (Duration.between(contentChangedAt, now).compareTo(pauseThreshold) >= 0) {
      freshness = FrameFreshness.PAUSED;
    } else {
      freshness = FrameFreshness.UNCHANGED;
    }
    lastDigest = digest;
    return new Observation(freshness, now, contentChangedAt);
  }

  private static byte[] sha256(byte[] pixels) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(pixels);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}

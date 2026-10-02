package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RemoteInputSessionOptionsTest {

  private static RemoteInputSessionOptions options(
      Duration inject,
      Duration pair,
      Duration nativeCall,
      Duration release,
      Duration interval,
      int maxClients) {
    return new RemoteInputSessionOptions(
        true, inject, pair, nativeCall, release, interval, maxClients);
  }

  @Test
  void defaultsAreDisabledAndCoverTheDocumentedNativeBudget() {
    RemoteInputSessionOptions defaults = RemoteInputSessionOptions.DEFAULT;

    assertFalse(defaults.attachmentEnabled(), "attachment must stay disabled by default");
    assertEquals(Duration.ofSeconds(30), defaults.injectTimeout());
    assertTrue(
        defaults.injectTimeout().compareTo(Duration.ofSeconds(25)) > 0,
        "the default deadline must exceed the ~25 s native initialization interval");
    assertEquals(Duration.ofSeconds(10), defaults.pairTimeout());
    assertEquals(Duration.ofSeconds(2), defaults.nativeCallTimeout());
    assertEquals(Duration.ofSeconds(3), defaults.releaseTimeout());
    assertEquals(Duration.ofMillis(250), defaults.validationInterval());
    assertEquals(4096, defaults.maxClients());
  }

  @Test
  void rejectsZeroDuration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            options(
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(1),
                1));
  }

  @Test
  void rejectsNegativeAndNullDurations() {
    Duration one = Duration.ofSeconds(1);
    assertThrows(
        IllegalArgumentException.class,
        () -> options(one, Duration.ofSeconds(-1), one, one, Duration.ofMillis(1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> options(one, one, null, one, Duration.ofMillis(1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> options(one, one, one, Duration.ZERO, Duration.ofMillis(1), 1));
    assertThrows(
        IllegalArgumentException.class, () -> options(one, one, one, one, Duration.ZERO, 1));
  }

  @Test
  void rejectsUnboundedTimeouts() {
    Duration one = Duration.ofSeconds(1);
    Duration ms = Duration.ofMillis(1);
    assertThrows(
        IllegalArgumentException.class,
        () -> options(Duration.ofSeconds(61), one, one, one, ms, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> options(one, Duration.ofSeconds(31), one, one, ms, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> options(one, one, Duration.ofSeconds(11), one, ms, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> options(one, one, one, Duration.ofSeconds(11), ms, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> options(Duration.ofSeconds(5), one, one, one, Duration.ofMillis(1001), 1));
  }

  @Test
  void acceptsTheDocumentedUpperBoundsExactly() {
    RemoteInputSessionOptions maximal =
        options(
            RemoteInputSessionOptions.MAX_INJECT_TIMEOUT,
            RemoteInputSessionOptions.MAX_PAIR_TIMEOUT,
            RemoteInputSessionOptions.MAX_NATIVE_CALL_TIMEOUT,
            RemoteInputSessionOptions.MAX_RELEASE_TIMEOUT,
            RemoteInputSessionOptions.MAX_VALIDATION_INTERVAL,
            RemoteInputSessionOptions.MAX_CLIENTS);

    assertEquals(Duration.ofSeconds(60), maximal.injectTimeout());
    assertEquals(4096, maximal.maxClients());
  }

  @Test
  void rejectsExcessiveOrNonPositiveClientCounts() {
    Duration one = Duration.ofSeconds(1);
    Duration ms = Duration.ofMillis(1);
    assertThrows(IllegalArgumentException.class, () -> options(one, one, one, one, ms, 0));
    assertThrows(IllegalArgumentException.class, () -> options(one, one, one, one, ms, -5));
    assertThrows(IllegalArgumentException.class, () -> options(one, one, one, one, ms, 4097));
  }

  @Test
  void rejectsAPollingIntervalLongerThanTheInjectionDeadline() {
    Duration one = Duration.ofSeconds(1);
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> options(Duration.ofMillis(500), one, one, one, Duration.ofMillis(501), 1));

    assertTrue(failure.getMessage().contains("validationInterval"), failure.getMessage());
    assertTrue(failure.getMessage().contains("injection deadline"), failure.getMessage());
  }

  @Test
  void acceptsAPollingIntervalEqualToTheInjectionDeadline() {
    Duration one = Duration.ofSeconds(1);
    RemoteInputSessionOptions equal = options(one, one, one, one, one, 1);
    assertEquals(equal.injectTimeout(), equal.validationInterval());
  }
}

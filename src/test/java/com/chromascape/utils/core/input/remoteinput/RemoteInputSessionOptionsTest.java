package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RemoteInputSessionOptionsTest {
  @Test
  void rejectsZeroDuration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemoteInputSessionOptions(
                true,
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(1),
                1));
  }
}

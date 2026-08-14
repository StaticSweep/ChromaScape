package com.chromascape.web.recording.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for initial-frame readiness configuration bounds in {@link RecordingConfig}.
 *
 * <p>Verifies clamping of {@code initialFrameTimeoutMs} (min 250, max 120000) and {@code
 * initialFrameRetryIntervalMs} (min 25, max 5000, and never exceeding the effective timeout), plus
 * presence of both values in {@code toString}.
 */
class RecordingConfigInitialFrameTest {

  @Test
  void defaults() {
    RecordingConfig c = new RecordingConfig();
    assertThat(c.getInitialFrameTimeoutMs()).isEqualTo(15_000);
    assertThat(c.getInitialFrameRetryIntervalMs()).isEqualTo(250);
  }

  @Test
  void timeoutClampedToMinimum() {
    RecordingConfig c = new RecordingConfig();
    c.setInitialFrameTimeoutMs(100);
    assertThat(c.getInitialFrameTimeoutMs()).isEqualTo(250);
  }

  @Test
  void timeoutClampedToMaximum() {
    RecordingConfig c = new RecordingConfig();
    c.setInitialFrameTimeoutMs(200_000);
    assertThat(c.getInitialFrameTimeoutMs()).isEqualTo(120_000);
  }

  @Test
  void retryIntervalClampedToMinimum() {
    RecordingConfig c = new RecordingConfig();
    c.setInitialFrameRetryIntervalMs(10);
    assertThat(c.getInitialFrameRetryIntervalMs()).isEqualTo(25);
  }

  @Test
  void retryIntervalClampedToMaximum() {
    RecordingConfig c = new RecordingConfig();
    c.setInitialFrameRetryIntervalMs(10_000);
    assertThat(c.getInitialFrameRetryIntervalMs()).isEqualTo(5_000);
  }

  @Test
  void retryIntervalNeverExceedsEffectiveTimeout() {
    RecordingConfig c = new RecordingConfig();
    c.setInitialFrameTimeoutMs(300);
    c.setInitialFrameRetryIntervalMs(5_000);
    assertThat(c.getInitialFrameRetryIntervalMs()).isEqualTo(300);
  }

  @Test
  void toStringIncludesInitialFrameValues() {
    RecordingConfig c = new RecordingConfig();
    String s = c.toString();
    assertThat(s).contains("initialFrameTimeoutMs=15000");
    assertThat(s).contains("initialFrameRetryIntervalMs=250");
  }
}

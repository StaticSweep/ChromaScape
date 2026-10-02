package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chromascape.utils.core.screen.capture.FrameFreshness;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class FrameFreshnessTrackerTest {

  @Test
  void reportsNewChangedUnchangedAndPausedFromContent() {
    MutableClock clock = new MutableClock(Instant.parse("2026-09-18T10:00:00Z"));
    FrameFreshnessTracker tracker = new FrameFreshnessTracker(Duration.ofSeconds(2), clock);

    final FrameFreshnessTracker.Observation first = tracker.observe(new byte[] {1});
    clock.advance(Duration.ofSeconds(1));
    final FrameFreshnessTracker.Observation unchanged = tracker.observe(new byte[] {1});
    clock.advance(Duration.ofSeconds(1));
    final FrameFreshnessTracker.Observation paused = tracker.observe(new byte[] {1});
    clock.advance(Duration.ofSeconds(1));
    final FrameFreshnessTracker.Observation changed = tracker.observe(new byte[] {2});

    assertEquals(FrameFreshness.NEW, first.freshness());
    assertEquals(FrameFreshness.UNCHANGED, unchanged.freshness());
    assertEquals(FrameFreshness.PAUSED, paused.freshness());
    assertEquals(FrameFreshness.CHANGED, changed.freshness());
    assertEquals(changed.observedAt(), changed.contentChangedAt());
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}

package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProcessIdentityTest {

  private static final Instant START = Instant.ofEpochMilli(1_000_000L);

  @Test
  void equalityIgnoresHostIdButIncludesOriginPidStartTimeAndBootId() {
    ProcessIdentity identity =
        new ProcessIdentity(ProcessOrigin.LOCAL, "host-a", 4242L, START, "boot-1");
    ProcessIdentity sameProcessDifferentHostId =
        new ProcessIdentity(ProcessOrigin.LOCAL, "host-b", 4242L, START, "boot-1");

    assertEquals(identity, sameProcessDifferentHostId);
    assertEquals(identity.hashCode(), sameProcessDifferentHostId.hashCode());
    assertNotEquals(
        identity,
        new ProcessIdentity(ProcessOrigin.LOCAL, "host-a", 4242L, START.plusSeconds(1), "boot-1"));
    assertNotEquals(
        identity, new ProcessIdentity(ProcessOrigin.LOCAL, "host-a", 4242L, START, "boot-2"));
    assertNotEquals(
        identity, new ProcessIdentity(ProcessOrigin.LOCAL, "host-a", 4243L, START, "boot-1"));
    assertNotEquals(
        identity, new ProcessIdentity(ProcessOrigin.REMOTE, "host-a", 4242L, START, "boot-1"));
  }

  @Test
  void nullHostIdAndBootIdNormalizeToEmpty() {
    ProcessIdentity identity = new ProcessIdentity(ProcessOrigin.LOCAL, null, 7L, START, null);

    assertEquals("", identity.hostId());
    assertEquals("", identity.bootId());
  }

  @Test
  void isLocalReflectsOrigin() {
    assertTrue(new ProcessIdentity(ProcessOrigin.LOCAL, "", 7L, START, "").isLocal());
    assertFalse(new ProcessIdentity(ProcessOrigin.REMOTE, "", 7L, START, "").isLocal());
  }

  @Test
  void displayStringCarriesNoProcessContent() {
    ProcessIdentity identity =
        new ProcessIdentity(ProcessOrigin.LOCAL, "host-a", 7L, START, "boot-1");

    String display = identity.toDisplayString();

    assertTrue(display.contains("pid=7"));
    assertTrue(display.contains("origin=LOCAL"));
    assertTrue(display.contains("startTime=" + START));
    assertTrue(display.contains("bootId=present"));
    assertFalse(display.contains("host-a"));
  }
}

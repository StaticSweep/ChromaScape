package com.chromascape.web.gateway.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import org.junit.jupiter.api.Test;

class DebuggerContextValidatorTest {

  private final DebuggerContextValidator validator = new DebuggerContextValidator();

  @Test
  void mapsCurrentSliceWithoutReplacingItsIdentityGroups() {
    CurrentSliceIdentity source = identity();

    DebuggerContext context =
        validator.map(
            source, "account-1", "session-1", "patch-1", DebuggerContext.Mode.replay, 42L, 7L);

    assertEquals(DebuggerContext.SCHEMA_VERSION, context.schemaVersion());
    assertEquals(source.client().clientId(), context.clientId());
    assertEquals(source.run().runId(), context.runId());
    assertEquals(source.run().executorInstanceId(), context.agentId());
    assertEquals(source.capture().recordingId(), context.recordingId());
    assertEquals(source.client().clientBuildId(), context.buildId());
    assertEquals(7L, context.contextRevision());
  }

  @Test
  void rejectsReplayWithoutSelectedTick() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.map(
                identity(),
                "account-1",
                "session-1",
                "patch-1",
                DebuggerContext.Mode.replay,
                null,
                1L));
  }

  @Test
  void validatesDebugSessionSeparatelyFromTheCurrentSliceCaptureSession() {
    DebuggerContext context =
        validator.map(
            identity(),
            "account-1",
            "debug-session-1",
            "patch-1",
            DebuggerContext.Mode.recording,
            null,
            1L);

    assertEquals("debug-session-1", context.debugSessionId());
    assertEquals("recording-1", context.recordingId());
  }

  private static CurrentSliceIdentity identity() {
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("task-1", hash('1'), "profile-1", hash('2'), hash('3')),
        new CurrentSliceIdentity.Run("run-1", "attempt-1", 1, "agent-1"),
        new CurrentSliceIdentity.Client(
            "client-1",
            "MICRO6_MANAGED",
            "client-build-1",
            hash('4'),
            "micro6-builder/v2",
            "MICRO6"),
        new CurrentSliceIdentity.Capture("session-1", "recording-1", hash('5')),
        new CurrentSliceIdentity.Source(
            "srcsnap_aaaaaaaaaaaaaaaaaaaaaaaa",
            hash('6'),
            "micro6",
            "0123456",
            "fedcba9",
            "build-1",
            hash('4'),
            true),
        null,
        null,
        null,
        false);
  }

  private static String hash(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}

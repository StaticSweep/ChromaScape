package com.chromascape.web.debug.tickdebugger.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.websocket.TickStreamWebSocketHandler;
import org.junit.jupiter.api.Test;

class ManualRecordingCoordinatorTest {
  private final TickStreamWebSocketHandler transport = mock(TickStreamWebSocketHandler.class);
  private final TickSnapshotRepository snapshots = mock(TickSnapshotRepository.class);

  @Test
  void waitsForACommittedNewTickAndTheExactBinding() {
    when(snapshots.countBySessionId("debug_1")).thenReturn(7L, 8L);
    when(transport.isRecorderBound("socket", "debug_1")).thenReturn(false, true);
    new ManualRecordingCoordinator(transport, snapshots, 1000)
        .startAndAwaitTick("socket", "debug_1", "manual");
    verify(transport).sendRecordingControl("socket", "START", "debug_1", "manual");
    verify(snapshots, times(2)).countBySessionId("debug_1");
  }

  @Test
  void forwardsExactRunContextForUuidSessions() throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var args = json.readTree("{\"targetFish\":\"TROUT\"}");
    var context = json.readTree("{\"dataOrigin\":\"HUMAN\",\"behaviorProfileVersion\":1}");
    String sessionId = "23441431-b139-470d-9c5d-203761924274";
    when(snapshots.countBySessionId(sessionId)).thenReturn(0L, 1L);
    when(transport.isRecorderBound("socket", sessionId)).thenReturn(false, true);
    new ManualRecordingCoordinator(transport, snapshots, 1000)
        .startAndAwaitTick("socket", sessionId, "manual", "FISHING_TRAINING", args, context);
    verify(transport)
        .sendRecordingControl(
            "socket", "START", sessionId, "manual", "FISHING_TRAINING", args, context);
  }

  @Test
  void oldTicksAndAnOpenSocketCannotEstablishReadiness() {
    when(snapshots.countBySessionId("debug_1")).thenReturn(7L);
    when(transport.isRecorderBound("socket", "debug_1")).thenReturn(true);
    var failed =
        assertThrows(
            IllegalStateException.class,
            () ->
                new ManualRecordingCoordinator(transport, snapshots, 1)
                    .startAndAwaitTick("socket", "debug_1", "manual"));
    assertTrue(failed.getMessage().contains("RECORDER_TICK_TIMEOUT"));
    verify(transport, never()).sendRecordingControl(any(), any(), any(), any());
  }

  @Test
  void tickWithoutTheExactOwnerCannotEstablishReadiness() {
    when(transport.isRecorderBound("socket", "debug_1")).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () ->
            new ManualRecordingCoordinator(transport, snapshots, 1)
                .startAndAwaitTick("socket", "debug_1", "manual"));
    verify(snapshots, times(1)).countBySessionId("debug_1");
  }

  @Test
  void stopWaitsForTheNormalProtocolToReleaseOwnership() {
    when(transport.recorderOwner("debug_1")).thenReturn("socket", "socket", null);
    new ManualRecordingCoordinator(transport, snapshots, 1000).stopAndAwaitDrain("debug_1");
    verify(transport).sendRecordingControl("socket", "STOP", "debug_1", null);
  }

  @Test
  void settledStopIsIdempotent() {
    new ManualRecordingCoordinator(transport, snapshots, 1).stopAndAwaitDrain("debug_1");
    verify(transport, never()).sendRecordingControl(any(), any(), any(), any());
  }
}

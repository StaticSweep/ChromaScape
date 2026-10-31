package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.websocket.TickStreamWebSocketHandler;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;

/** A manual recording is ready only after its owning recorder commits a new real tick. */
@Service
public class ManualRecordingCoordinator {
  private final TickStreamWebSocketHandler transport;
  private final TickSnapshotRepository snapshots;
  private final long waitMillis;

  @org.springframework.beans.factory.annotation.Autowired
  public ManualRecordingCoordinator(
      TickStreamWebSocketHandler transport, TickSnapshotRepository snapshots) {
    this(transport, snapshots, 15_000);
  }

  ManualRecordingCoordinator(
      TickStreamWebSocketHandler transport, TickSnapshotRepository snapshots, long waitMillis) {
    this.transport = transport;
    this.snapshots = snapshots;
    this.waitMillis = waitMillis;
  }

  public String selectRecorder(String preferredSessionId) {
    return transport.selectManualRecorder(preferredSessionId);
  }

  public String selectRecorder(String preferredSessionId, String recorderConnectionId) {
    return transport.selectManualRecorder(preferredSessionId, recorderConnectionId);
  }

  public void startAndAwaitTick(String clientId, String sessionId, String scriptName) {
    startAndAwaitTick(clientId, sessionId, scriptName, null, null, null);
  }

  public void startAndAwaitTick(
      String clientId,
      String sessionId,
      String scriptName,
      String taskKey,
      com.fasterxml.jackson.databind.JsonNode taskArgs,
      com.fasterxml.jackson.databind.JsonNode runContext) {
    long before = snapshots.countBySessionId(sessionId);
    if (!transport.isRecorderBound(clientId, sessionId)) {
      if (runContext == null || runContext.isNull()) {
        transport.sendRecordingControl(clientId, "START", sessionId, scriptName);
      } else {
        transport.sendRecordingControl(
            clientId, "START", sessionId, scriptName, taskKey, taskArgs, runContext);
      }
    }
    await(
        () ->
            transport.isRecorderBound(clientId, sessionId)
                && snapshots.countBySessionId(sessionId) > before,
        "RECORDER_TICK_TIMEOUT");
  }

  public void stopAndAwaitDrain(String sessionId) {
    String owner = transport.recorderOwner(sessionId);
    if (owner == null) {
      return;
    }
    transport.sendRecordingControl(owner, "STOP", sessionId, null);
    await(() -> transport.recorderOwner(sessionId) == null, "RECORDER_STOP_TIMEOUT");
  }

  private void await(BooleanSupplier completed, String failureCode) {
    long deadline = System.nanoTime() + java.time.Duration.ofMillis(waitMillis).toNanos();
    while (!completed.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            failureCode + ": recorder did not confirm capture readiness");
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("RECORDER_WAIT_INTERRUPTED", interrupted);
      }
    }
  }
}

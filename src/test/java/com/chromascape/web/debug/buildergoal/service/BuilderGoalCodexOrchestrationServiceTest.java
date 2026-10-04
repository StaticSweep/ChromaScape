package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.integration.adapter.RuntimeAdapterRegistry;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SC-666: each AI-bot request is one recoverable run. The Codex session wrapper is in-memory, but
 * the run/task/session/recording linkage itself is durable: after a restart, a fresh session bound
 * to the same iteration resolves the same run, recording, and debug session from the repositories
 * instead of depending on the previous session instance.
 */
@ExtendWith(MockitoExtension.class)
class BuilderGoalCodexOrchestrationServiceTest {

  @Mock private BuilderGoalRunnerService builderGoalRunnerService;
  @Mock private RuntimeAdapterRegistry runtimeAdapterRegistry;
  @Mock private DebugSessionRepository debugSessionRepository;
  @Mock private TickSnapshotRepository tickSnapshotRepository;
  @Mock private TickLogRepository tickLogRepository;
  @Mock private TickOverlayRepository tickOverlayRepository;
  @Mock private RecordingSessionRepository recordingSessionRepository;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private BuilderGoalCodexOrchestrationService service() {
    return new BuilderGoalCodexOrchestrationService(
        builderGoalRunnerService,
        runtimeAdapterRegistry,
        debugSessionRepository,
        tickSnapshotRepository,
        tickLogRepository,
        tickOverlayRepository,
        recordingSessionRepository,
        objectMapper);
  }

  private Map<String, Object> persistedRun() {
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("iterationId", "buildergoal_sc666_1");
    run.put("taskKey", "configurable_woodcutting");
    run.put("runRecordingId", "rec-sc666-1");
    run.put("runSessionId", "dbg-sc666-1");
    return run;
  }

  @Test
  void bindRun_afterRestart_resolvesSamePersistedRun() {
    when(builderGoalRunnerService.getRunSummary("buildergoal_sc666_1")).thenReturn(persistedRun());

    BuilderGoalCodexOrchestrationService beforeRestart = service();
    String firstSessionId = (String) beforeRestart.createSession().get("sessionId");
    beforeRestart.bindRun(firstSessionId, "buildergoal_sc666_1");

    // Simulate a process restart: the previous session instance is gone, but the run,
    // recording, and debug session remain persisted.
    BuilderGoalCodexOrchestrationService afterRestart = service();
    String secondSessionId = (String) afterRestart.createSession().get("sessionId");
    afterRestart.bindRun(secondSessionId, "buildergoal_sc666_1");

    Map<String, Object> activeRun = afterRestart.getActiveRun(secondSessionId);
    assertThat(activeRun.get("activeIterationId")).isEqualTo("buildergoal_sc666_1");
    @SuppressWarnings("unchecked")
    Map<String, Object> run = (Map<String, Object>) activeRun.get("run");
    assertThat(run.get("iterationId")).isEqualTo("buildergoal_sc666_1");
    assertThat(run.get("runRecordingId")).isEqualTo("rec-sc666-1");
    assertThat(run.get("runSessionId")).isEqualTo("dbg-sc666-1");

    Map<String, Object> context = afterRestart.getContext(secondSessionId);
    assertThat(context.get("activeIterationId")).isEqualTo("buildergoal_sc666_1");
    assertThat(context.get("activeRecordingId")).isEqualTo("rec-sc666-1");
    assertThat(context.get("activeDebugSessionId")).isEqualTo("dbg-sc666-1");
  }

  @Test
  void getReplayContext_resolvesPersistedRecordingAndDebugSession() {
    when(builderGoalRunnerService.getRunSummary("buildergoal_sc666_1")).thenReturn(persistedRun());

    DebugSession debugSession = new DebugSession();
    debugSession.setSessionId("dbg-sc666-1");
    debugSession.setRecordingId("rec-sc666-1");
    debugSession.setScriptName("configurable_woodcutting");
    when(debugSessionRepository.findBySessionId("dbg-sc666-1"))
        .thenReturn(Optional.of(debugSession));

    RecordingSession recordingSession = new RecordingSession();
    recordingSession.setRecordingId("rec-sc666-1");
    recordingSession.setScriptName("configurable_woodcutting");
    when(recordingSessionRepository.findByRecordingId("rec-sc666-1"))
        .thenReturn(Optional.of(recordingSession));

    BuilderGoalCodexOrchestrationService fresh = service();
    String sessionId = (String) fresh.createSession().get("sessionId");
    fresh.bindRun(sessionId, "buildergoal_sc666_1");

    Map<String, Object> replayContext = fresh.getReplayContext(sessionId, null);
    assertThat(replayContext.get("iterationId")).isEqualTo("buildergoal_sc666_1");
    assertThat(replayContext.get("recordingId")).isEqualTo("rec-sc666-1");
    assertThat(replayContext.get("debugSessionId")).isEqualTo("dbg-sc666-1");
    assertThat(replayContext.get("recordingAvailable")).isEqualTo(true);
    assertThat(replayContext.get("debugSessionAvailable")).isEqualTo(true);
    assertThat(fresh.getReplaySessionInfo(sessionId, null).get("sessionId"))
        .isEqualTo("dbg-sc666-1");
  }

  @Test
  void unknownSession_afterRestart_isRejected() {
    // The chat session wrapper itself is not durable: an id from before the restart is
    // rejected and the caller must create a session and rebind to the persisted run.
    BuilderGoalCodexOrchestrationService fresh = service();
    try {
      fresh.getSession("codex_bg_before_restart");
      assertThat(false).as("expected unknown session to be rejected").isTrue();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).contains("Codex session not found");
    }
  }
}

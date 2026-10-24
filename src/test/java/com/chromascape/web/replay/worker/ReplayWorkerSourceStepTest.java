package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.run.ReplayFence;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.entity.StateHashSample;
import com.chromascape.web.replay.run.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Real Java request/evidence boundary, mocked transport/persistence; not database or native parity.
 */
class ReplayWorkerSourceStepTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final ReplayRunRepository runs = mock(ReplayRunRepository.class);
  private final ReplayWorkerPersistenceService persistence =
      mock(ReplayWorkerPersistenceService.class);
  private final XrspsKernelProcess kernel = mock(XrspsKernelProcess.class);
  private final ReplayRun run = mock(ReplayRun.class);
  private final ReplayFence fence =
      new ReplayFence(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "test");

  private ReplayWorker worker() {
    when(run.getId()).thenReturn(fence.runId());
    when(runs.remainingDeadlineMs(eq(fence.runId()), anyLong())).thenReturn(10000L);
    return new ReplayWorker(
        runs,
        mock(ReplayAttemptRepository.class),
        mock(ReplayRunService.class),
        mock(ScenarioManifestRepository.class),
        mock(ReplayCheckpointRepository.class),
        mock(ArtifactRefRepository.class),
        mock(ReplayMismatchRepository.class),
        persistence,
        mock(ReplayLeaseHeartbeat.class),
        mock(ReplayExpiredAttemptRecoveryService.class),
        mock(ReplayArtifactService.class),
        mock(MismatchEngine.class),
        mock(ReplayReportService.class),
        new CanonicalProjectionService(),
        new ReplayProperties(),
        mapper);
  }

  private long execute(
      ReplayWorker worker, ReplayWorker.ExecutionState state, int index, int bound) {
    var params = mapper.createObjectNode().put("expectedTick", 42).put("maxTicks", bound);
    if (bound == 0) {
      params
          .put("sourceActionIndex", index)
          .putObject("action")
          .put("type", "INTERACT_NPC")
          .put("targetStableId", "spot")
          .put("option", "net");
    }
    return ReflectionTestUtils.invokeMethod(
        worker,
        "executeScheduledStep",
        kernel,
        mock(ReplayLeaseHeartbeat.Handle.class),
        fence,
        run,
        state,
        params,
        index);
  }

  private void response(long terminalTick, boolean applied) {
    var result =
        mapper.createObjectNode().put("terminalTick", terminalTick).put("actionApplied", applied);
    result.putObject("observation").put("tick", terminalTick);
    result.putArray("events");
    when(kernel.send(eq("step"), any(JsonNode.class), anyLong()))
        .thenReturn(new KernelMessage("step", null, result, null, false, false));
  }

  @Test
  void sameTickActionsHaveDistinctPersistedHashCoordinatesAndOneRequestEach() {
    try (var worker = worker()) {
      response(42, true);
      var state = new ReplayWorker.ExecutionState("sha256:" + "a".repeat(64));
      assertThat(execute(worker, state, 0, 0)).isEqualTo(42);
      assertThat(execute(worker, state, 1, 0)).isEqualTo(42);
      var samples = ArgumentCaptor.forClass(StateHashSample.class);
      verify(persistence, times(2)).appendActionResult(eq(fence), anyList(), samples.capture());
      assertThat(samples.getAllValues())
          .extracting(s -> s.getId().getSequence())
          .containsExactly(0, 1);
      assertThat(samples.getAllValues())
          .extracting(s -> s.getId().getTick())
          .containsExactly(42L, 42L);
      verify(kernel, times(2)).send(eq("step"), any(JsonNode.class), anyLong());
    }
  }

  @Test
  void refusedZeroTickActionOrOvershootOrNoProgressPersistsNoAcceptedEvidence() {
    try (var worker = worker()) {
      response(42, false);
      assertThatThrownBy(
              () ->
                  execute(
                      worker, new ReplayWorker.ExecutionState("sha256:" + "a".repeat(64)), 0, 0))
          .isInstanceOf(ReplayException.class)
          .hasMessageContaining("scheduled step coordinates");
      response(44, false);
      assertThatThrownBy(
              () ->
                  execute(
                      worker, new ReplayWorker.ExecutionState("sha256:" + "a".repeat(64)), 0, 1))
          .isInstanceOf(ReplayException.class)
          .hasMessageContaining("scheduled step coordinates");
      response(42, false);
      assertThatThrownBy(
              () ->
                  execute(
                      worker, new ReplayWorker.ExecutionState("sha256:" + "a".repeat(64)), 0, 1))
          .isInstanceOf(ReplayException.class)
          .hasMessageContaining("scheduled step coordinates");
      verifyNoInteractions(persistence);
    }
  }

  @Test
  void timedSourceProtocolIsExplicitClosedAndCannotBeDefaultedFromMethodNames() {
    try (var worker = worker()) {
      for (String json :
          new String[] {
            "{}",
            "{\"version\":1}",
            "{\"version\":1,\"zeroTickStep\":false,\"receipts\":\"INDEXED_ONE_USE\"}"
          }) {
        JsonNode protocol;
        try {
          protocol = mapper.readTree(json);
        } catch (Exception e) {
          throw new AssertionError(e);
        }
        var result = mapper.createObjectNode();
        result.putObject("capabilities").set("sourceActionProtocol", protocol);
        var caps = new KernelMessage("caps", null, result, null, false, false);
        assertThatThrownBy(
                () -> ReflectionTestUtils.invokeMethod(worker, "requireSourceActionProtocol", caps))
            .isInstanceOf(ReplayException.class);
      }
      var result = mapper.createObjectNode();
      result
          .putObject("capabilities")
          .putObject("sourceActionProtocol")
          .put("version", 1)
          .put("zeroTickStep", true)
          .put("receipts", "INDEXED_ONE_USE");
      ReflectionTestUtils.invokeMethod(
          worker,
          "requireSourceActionProtocol",
          new KernelMessage("caps", null, result, null, false, false));
    }
  }
}

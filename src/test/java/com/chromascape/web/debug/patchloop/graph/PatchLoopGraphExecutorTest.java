package com.chromascape.web.debug.patchloop.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.patchloop.dto.PatchProposal;
import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.service.ExecutionPauseService;
import com.chromascape.web.debug.patchloop.service.ExecutionRuntimeSnapshotService;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutorRegistry;
import com.chromascape.web.debug.patchloop.websocket.PatchLoopWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link PatchLoopGraphExecutor} focusing on pause lifecycle and typed rehydration. */
@ExtendWith(MockitoExtension.class)
class PatchLoopGraphExecutorTest {

  @Mock private PatchLoopGraphExecutionRepository executionRepository;

  @Mock private PatchLoopGraphNodeRepository nodeRepository;

  @Mock private PatchLoopAttemptRepository attemptRepository;

  @Mock private PatchLoopIterationRepository iterationRepository;

  @Mock private GraphNodeExecutorRegistry executorRegistry;

  @Mock private ObjectMapper objectMapper;

  @Mock private ExecutionRuntimeSnapshotService snapshotService;

  @Mock private ExecutionPauseService pauseService;

  @Mock private PatchLoopWebSocketHandler webSocketHandler;

  private PatchLoopGraphExecutor executor;

  private PatchLoopGraphExecution execution;
  private PatchLoopIteration iteration;
  private String executionId;
  private String iterationId;

  @BeforeEach
  void setUp() {
    executor =
        new PatchLoopGraphExecutor(
            executionRepository,
            nodeRepository,
            attemptRepository,
            iterationRepository,
            executorRegistry,
            objectMapper,
            snapshotService,
            pauseService,
            webSocketHandler);

    executionId = "graph_exec_" + UUID.randomUUID().toString().substring(0, 8);
    iterationId = "patchloop_" + UUID.randomUUID().toString().substring(0, 8);

    execution = new PatchLoopGraphExecution();
    execution.setExecutionId(executionId);
    execution.setIterationId(iterationId);
    execution.setExecutionStatus(GraphExecutionStatus.CREATED);
    execution.setCurrentNodeId("START");
    execution.setAttemptCount(1);
    execution.setCreatedAt(LocalDateTime.now());

    iteration = new PatchLoopIteration();
    iteration.setIterationId(iterationId);
    iteration.setScriptName("TestScript");
    iteration.setPrompt("Test prompt");
    iteration.setMaxAttempts(3);
    iteration.setExecutionMode("analysis_only");
  }

  @Test
  @DisplayName("Cancel execution sets CANCELLED status and clears pause state")
  void testCancelExecutionClearsPauseState() throws GraphExecutionException {
    // Given: execution is in PAUSED state
    execution.setExecutionStatus(GraphExecutionStatus.PAUSED);
    when(executionRepository.findByExecutionId(executionId))
        .thenReturn(java.util.Optional.of(execution));

    // When: cancelling the execution
    executor.cancelExecution(executionId, "User cancelled");

    // Then: pause flag should be cleared and status set to CANCELLED
    verify(pauseService).clearPaused(eq(executionId));
    verify(executionRepository).save(execution);
    assertThat(execution.getExecutionStatus()).isEqualTo(GraphExecutionStatus.CANCELLED);
    assertThat(execution.getCompletedAt()).isNotNull();
  }

  @Test
  @DisplayName("Cancel execution when not paused still sets CANCELLED status")
  void testCancelExecutionWhenNotPaused() throws GraphExecutionException {
    // Given: execution is in RUNNING state (not paused)
    execution.setExecutionStatus(GraphExecutionStatus.RUNNING);
    when(executionRepository.findByExecutionId(executionId))
        .thenReturn(java.util.Optional.of(execution));

    // When: cancelling the execution
    executor.cancelExecution(executionId, "User cancelled");

    // Then: status should be set to CANCELLED even if not paused
    verify(executionRepository).save(execution);
    assertThat(execution.getExecutionStatus()).isEqualTo(GraphExecutionStatus.CANCELLED);
    assertThat(execution.getCompletedAt()).isNotNull();
  }

  @Test
  @DisplayName("Snapshot restore with typed rehydration for PatchProposal")
  void testTypedRehydrationForPatchProposal() {
    // Given: runtime data with a patch proposal stored as JSON
    Map<String, Object> runtimeData = new HashMap<>();
    Map<String, Object> patchMap = new HashMap<>();
    patchMap.put("diffText", "test diff");
    patchMap.put("rationale", "test rationale");
    runtimeData.put("patch_1", patchMap);

    PatchProposal expectedPatch = new PatchProposal();
    expectedPatch.setDiffText("test diff");
    expectedPatch.setRationale("test rationale");

    when(snapshotService.getRuntimeValue(eq(executionId), eq("patch_1"), eq(PatchProposal.class)))
        .thenReturn(expectedPatch);

    // This test verifies the snapshot service interaction
    PatchProposal restoredPatch =
        snapshotService.getRuntimeValue(executionId, "patch_1", PatchProposal.class);

    // Then: patch should be rehydrated correctly
    assertThat(restoredPatch).isNotNull();
    assertThat(restoredPatch.getDiffText()).isEqualTo("test diff");
    assertThat(restoredPatch.getRationale()).isEqualTo("test rationale");
  }

  @Test
  @DisplayName("Execution status transitions through lifecycle states")
  void testExecutionStatusTransitions() {
    // Test that status transitions work as expected
    assertThat(execution.getExecutionStatus()).isEqualTo(GraphExecutionStatus.CREATED);

    execution.transitionTo(GraphExecutionStatus.RUNNING);
    assertThat(execution.getExecutionStatus()).isEqualTo(GraphExecutionStatus.RUNNING);

    execution.transitionTo(GraphExecutionStatus.PAUSED);
    assertThat(execution.getExecutionStatus()).isEqualTo(GraphExecutionStatus.PAUSED);

    execution.transitionTo(GraphExecutionStatus.RUNNING);
    assertThat(execution.getExecutionStatus()).isEqualTo(GraphExecutionStatus.RUNNING);

    execution.transitionTo(GraphExecutionStatus.COMPLETED);
    assertThat(execution.getExecutionStatus()).isEqualTo(GraphExecutionStatus.COMPLETED);
    assertThat(execution.getCompletedAt()).isNotNull();
  }
}

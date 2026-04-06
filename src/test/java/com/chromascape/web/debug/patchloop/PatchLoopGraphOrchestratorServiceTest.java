package com.chromascape.web.debug.patchloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.dto.GraphControlRequest;
import com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest;
import com.chromascape.web.debug.patchloop.dto.GraphExecuteResponse;
import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.graph.*;
import com.chromascape.web.debug.patchloop.service.*;
import com.chromascape.web.debug.patchloop.websocket.PatchLoopWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link PatchLoopGraphOrchestratorService} focusing on async control paths and retry
 * guards.
 */
@ExtendWith(MockitoExtension.class)
class PatchLoopGraphOrchestratorServiceTest {

  @Mock private PatchLoopGraphCompiler compiler;

  @Mock private PatchLoopGraphExecutor executor;

  @Mock private PatchLoopGraphExecutionRepository executionRepository;

  @Mock private PatchLoopIterationRepository iterationRepository;

  @Mock private PatchLoopGraphNodeRepository nodeRepository;

  @Mock private PatchLoopAttemptRepository attemptRepository;

  @Mock private PatchLoopPatchArtifactRepository patchRepository;

  @Mock private PatchLoopAnalysisClient analysisClient;

  @Mock private PatchLoopEvidenceService evidenceService;

  @Mock private PatchLoopWebSocketHandler webSocketHandler;

  @Mock private PatchLoopValidationArtifactRepository validationRepository;

  @Mock private PatchLoopRetestArtifactRepository retestRepository;

  @Mock private PatchLoopAnalysisArtifactRepository analysisArtifactRepository;

  @Mock private PatchLoopEvidenceArtifactRepository evidenceArtifactRepository;

  @Mock private PatchLoopAsyncWorker asyncWorker;

  private PatchLoopGraphOrchestratorService orchestrator;

  private String executionId;
  private String iterationId;
  private PatchLoopGraphExecution execution;
  private PatchLoopIteration iteration;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper();

    orchestrator =
        new PatchLoopGraphOrchestratorService(
            compiler,
            executor,
            executionRepository,
            iterationRepository,
            nodeRepository,
            attemptRepository,
            patchRepository,
            analysisClient,
            evidenceService,
            webSocketHandler,
            validationRepository,
            retestRepository,
            analysisArtifactRepository,
            evidenceArtifactRepository,
            mapper,
            asyncWorker);

    executionId = "graph_exec_" + UUID.randomUUID().toString().substring(0, 8);
    iterationId = "patchloop_" + UUID.randomUUID().toString().substring(0, 8);

    execution = new PatchLoopGraphExecution();
    execution.setExecutionId(executionId);
    execution.setIterationId(iterationId);
    execution.setExecutionStatus(GraphExecutionStatus.PAUSED);
    execution.setCurrentNodeId("ANALYZE");
    execution.setAttemptCount(1);
    execution.setCreatedAt(LocalDateTime.now());

    // Create a proper LoopGraph object and serialize it for clearNodeResults()
    LoopGraph graph = new LoopGraph();
    GraphNode analyzeNode = new GraphNode("ANALYZE", NodeType.ANALYZE, "Analyze", new HashMap<>());
    graph.addNode(analyzeNode);
    graph.setEntryNodeId("ANALYZE");
    // No need to mark terminal for our test - the graph just needs to be loadable

    JsonNode graphJson = mapper.valueToTree(graph);
    execution.setGraphJson(graphJson);

    iteration = new PatchLoopIteration();
    iteration.setIterationId(iterationId);
    iteration.setGraphExecutionId(executionId);
    iteration.setScriptName("TestScript");

    // Use lenient() for stubbings not used by all tests
    lenient()
        .when(iterationRepository.findByIterationId(iterationId))
        .thenReturn(Optional.of(iteration));
    lenient()
        .when(executionRepository.findByExecutionId(executionId))
        .thenReturn(Optional.of(execution));

    // Use lenient() for stubbings not used by all tests
    lenient()
        .when(executionRepository.save(any(PatchLoopGraphExecution.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient()
        .when(asyncWorker.resumeExecutionAsync(anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    lenient()
        .when(asyncWorker.retryFromNodeAsync(anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  @Test
  @DisplayName("RESUME uses async worker and returns immediately")
  void testResumeUsesAsyncWorker() throws Exception {
    // Given: execution is in PAUSED state
    execution.setExecutionStatus(GraphExecutionStatus.PAUSED);

    // When: resuming execution
    long startTime = System.currentTimeMillis();
    orchestrator.controlExecution(iterationId, GraphControlRequest.resume());
    long duration = System.currentTimeMillis() - startTime;

    // Then: should return immediately (<100ms) and call async worker
    assertThat(duration).isLessThan(100);
    verify(asyncWorker).resumeExecutionAsync(executionId);
    verify(executor, never()).resumeExecution(anyString());
  }

  @Test
  @DisplayName("RETRY_FROM_NODE uses async worker and returns immediately")
  void testRetryFromNodeUsesAsyncWorker() throws Exception {
    // Given: execution is in PAUSED state
    execution.setExecutionStatus(GraphExecutionStatus.PAUSED);

    // When: retrying from a node
    long startTime = System.currentTimeMillis();
    orchestrator.controlExecution(iterationId, GraphControlRequest.retryFromNode("ANALYZE"));
    long duration = System.currentTimeMillis() - startTime;

    // Then: should return immediately (<100ms) and call async worker
    assertThat(duration).isLessThan(100);
    verify(asyncWorker).retryFromNodeAsync(executionId, "ANALYZE");
    verify(executor, never()).retryFromNode(anyString(), anyString());
  }

  @Test
  @DisplayName("RETRY_FROM_NODE rejected from RUNNING state")
  void testRetryFromNodeRejectedFromRunningState() {
    // Given: execution is in RUNNING state
    execution.setExecutionStatus(GraphExecutionStatus.RUNNING);

    // When/Then: retrying should throw exception
    assertThatThrownBy(
            () ->
                orchestrator.controlExecution(
                    iterationId, GraphControlRequest.retryFromNode("ANALYZE")))
        .isInstanceOf(GraphExecutionException.class)
        .hasMessageContaining("Cannot retry from node in state: RUNNING");

    verify(asyncWorker, never()).retryFromNodeAsync(anyString(), anyString());
  }

  @Test
  @DisplayName("RETRY_FROM_NODE rejected from CREATED state")
  void testRetryFromNodeRejectedFromCreatedState() {
    // Given: execution is in CREATED state
    execution.setExecutionStatus(GraphExecutionStatus.CREATED);

    // When/Then: retrying should throw exception
    assertThatThrownBy(
            () ->
                orchestrator.controlExecution(
                    iterationId, GraphControlRequest.retryFromNode("ANALYZE")))
        .isInstanceOf(GraphExecutionException.class)
        .hasMessageContaining("Cannot retry from node in state: CREATED");

    verify(asyncWorker, never()).retryFromNodeAsync(anyString(), anyString());
  }

  @Test
  @DisplayName("RETRY_FROM_NODE allowed from PAUSED state")
  void testRetryFromNodeAllowedFromPausedState() throws GraphExecutionException {
    // Given: execution is in PAUSED state
    execution.setExecutionStatus(GraphExecutionStatus.PAUSED);

    // When: retrying from a node
    orchestrator.controlExecution(iterationId, GraphControlRequest.retryFromNode("ANALYZE"));

    // Then: should call async worker
    verify(asyncWorker).retryFromNodeAsync(executionId, "ANALYZE");
  }

  @Test
  @DisplayName("RETRY_FROM_NODE allowed from COMPLETED state")
  void testRetryFromNodeAllowedFromCompletedState() throws GraphExecutionException {
    // Given: execution is in COMPLETED state
    execution.setExecutionStatus(GraphExecutionStatus.COMPLETED);

    // When: retrying from a node
    orchestrator.controlExecution(iterationId, GraphControlRequest.retryFromNode("ANALYZE"));

    // Then: should call async worker
    verify(asyncWorker).retryFromNodeAsync(executionId, "ANALYZE");
  }

  @Test
  @DisplayName("RESUME rejected from RUNNING state")
  void testResumeRejectedFromRunningState() {
    // Given: execution is in RUNNING state
    execution.setExecutionStatus(GraphExecutionStatus.RUNNING);

    // When/Then: resuming should throw exception
    assertThatThrownBy(
            () -> orchestrator.controlExecution(iterationId, GraphControlRequest.resume()))
        .isInstanceOf(GraphExecutionException.class)
        .hasMessageContaining("Cannot resume execution in state: RUNNING");

    verify(asyncWorker, never()).resumeExecutionAsync(anyString());
  }

  @Test
  @DisplayName("RESUME allowed from PAUSED state")
  void testResumeAllowedFromPausedState() throws GraphExecutionException {
    // Given: execution is in PAUSED state
    execution.setExecutionStatus(GraphExecutionStatus.PAUSED);

    // When: resuming execution
    orchestrator.controlExecution(iterationId, GraphControlRequest.resume());

    // Then: should call async worker
    verify(asyncWorker).resumeExecutionAsync(executionId);
  }

  @Test
  @DisplayName("executeGraph reuses existing iteration when iterationId is provided (E6.5)")
  void testExecuteGraphReusesIteration() throws GraphCompilationException, GraphExecutionException {
    // Arrange
    String existingIterationId = "existing_iter_123";
    PatchLoopIteration existingIteration = new PatchLoopIteration();
    existingIteration.setIterationId(existingIterationId);
    existingIteration.setScriptName("TestScript");

    when(iterationRepository.findByIterationId(existingIterationId))
        .thenReturn(Optional.of(existingIteration));

    when(compiler.compile(anyString())).thenReturn(new LoopGraph());

    // Stub async worker for graph execution
    when(asyncWorker.executeGraphAsync(anyString(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));

    // Act
    GraphExecuteRequest request = new GraphExecuteRequest();
    request.setScriptName("TestScript");
    request.setMermaid("ANALYZE-->GENERATE_PATCH");
    request.setIterationId(existingIterationId); // Set iterationId to trigger reuse path

    GraphExecuteResponse response = orchestrator.executeGraph(request);

    // Assert
    assertThat(response.getIterationId()).isEqualTo(existingIterationId);
    assertThat(response.getWsUrl()).isEqualTo("/ws/debug/patch-loop/" + response.getExecutionId());
    assertThat(response.getApiUrl()).contains(existingIterationId);

    // Verify existing iteration was reused (findByIterationId called)
    verify(iterationRepository).findByIterationId(existingIterationId);

    // Verify save was called once to update the existing iteration with graphExecutionId
    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(iterationRepository, times(1)).save(captor.capture());

    // Assert saved iteration preserves the same iterationId and receives graphExecutionId
    PatchLoopIteration savedIteration = captor.getValue();
    assertThat(savedIteration.getIterationId()).isEqualTo(existingIterationId);
    assertThat(savedIteration.getGraphExecutionId()).isEqualTo(response.getExecutionId());
  }
}

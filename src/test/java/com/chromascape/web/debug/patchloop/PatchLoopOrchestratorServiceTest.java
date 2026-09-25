package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest;
import com.chromascape.web.debug.patchloop.dto.GraphExecuteResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteRequest;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopIterationResponse;
import com.chromascape.web.debug.patchloop.entity.PatchLoopAnalysisArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopAnalysisArtifactRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEventRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEvidenceArtifactRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopPatchArtifactRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopRetestArtifactRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.entity.PatchLoopValidationArtifactRepository;
import com.chromascape.web.debug.patchloop.service.PatchLoopGraphOrchestratorService;
import com.chromascape.web.debug.patchloop.service.PatchLoopOrchestratorService;
import com.chromascape.web.debug.patchloop.service.PatchLoopSessionResolverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** Test suite for PatchLoopOrchestratorService. */
@ExtendWith(MockitoExtension.class)
class PatchLoopOrchestratorServiceTest {

  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private PatchLoopEventRepository eventRepository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private PatchLoopGraphOrchestratorService graphOrchestratorService;
  @Mock private PatchLoopAnalysisArtifactRepository analysisArtifactRepository;
  @Mock private PatchLoopPatchArtifactRepository patchArtifactRepository;
  @Mock private PatchLoopValidationArtifactRepository validationArtifactRepository;
  @Mock private PatchLoopRetestArtifactRepository retestArtifactRepository;
  @Mock private PatchLoopEvidenceArtifactRepository evidenceArtifactRepository;
  @Mock private PatchLoopSessionResolverService sessionResolverService;

  private PatchLoopOrchestratorService orchestratorService;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    orchestratorService =
        new PatchLoopOrchestratorService(
            iterationRepository,
            eventRepository,
            eventPublisher,
            objectMapper,
            graphOrchestratorService,
            analysisArtifactRepository,
            patchArtifactRepository,
            validationArtifactRepository,
            retestArtifactRepository,
            evidenceArtifactRepository,
            sessionResolverService);
  }

  @Test
  @DisplayName("Execute iteration creates iteration and delegates to graph orchestrator (E6.5)")
  void testExecuteIterationCreatesAndReturns()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException,
          com.chromascape.web.debug.patchloop.graph.GraphExecutionException {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Why did it fail?");

    PatchLoopIteration savedIteration = new PatchLoopIteration();
    savedIteration.setIterationId("patchloop_test123");
    savedIteration.setStatus(PatchLoopStatus.QUEUED);
    savedIteration.setScriptName("TestScript");

    when(iterationRepository.save(any(PatchLoopIteration.class))).thenReturn(savedIteration);

    // Mock graph orchestrator response
    when(graphOrchestratorService.executeGraph(any(GraphExecuteRequest.class)))
        .thenAnswer(
            invocation -> {
              GraphExecuteResponse response = new GraphExecuteResponse();
              response.setExecutionId("graph_exec_xyz");
              response.setIterationId("patchloop_test123");
              response.setWsUrl("/ws/debug/patch-loop/graph_exec_xyz");
              response.setApiUrl("/api/debug/patch-loop/patchloop_test123/graph");
              return response;
            });

    // Act
    PatchLoopExecuteResponse response = orchestratorService.executeIteration(request);

    // Assert
    assertNotNull(response);
    assertEquals("patchloop_test123", response.getIterationId());
    assertEquals("QUEUED", response.getStatus());
    assertEquals("graph_exec_xyz", response.getExecutionId());
    assertEquals("/ws/debug/patch-loop/graph_exec_xyz", response.getWsUrl());
    assertEquals("/api/debug/patch-loop/patchloop_test123/graph", response.getApiUrl());
    assertTrue(response.getMessage().contains("asynchronously"));

    // Verify iteration was saved with QUEUED status
    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(iterationRepository, times(1)).save(captor.capture());

    PatchLoopIteration captured = captor.getValue();
    assertEquals(PatchLoopStatus.QUEUED, captured.getStatus());
    assertEquals("TestScript", captured.getScriptName());
    assertEquals("Why did it fail?", captured.getPrompt());

    // Verify graph orchestrator was called
    verify(graphOrchestratorService, times(1)).executeGraph(any(GraphExecuteRequest.class));
  }

  @Test
  @DisplayName("Execute iteration fails with null script name")
  void testExecuteIterationFailsWithNullScriptName() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName(null);
    request.setPrompt("Test prompt");

    // Act & Assert
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> orchestratorService.executeIteration(request));

    assertTrue(ex.getMessage().contains("Script name"));
  }

  @Test
  @DisplayName("Execute iteration fails with empty script name")
  void testExecuteIterationFailsWithEmptyScriptName() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("   ");
    request.setPrompt("Test prompt");

    // Act & Assert
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> orchestratorService.executeIteration(request));

    assertTrue(ex.getMessage().contains("Script name"));
  }

  @Test
  @DisplayName("Execute iteration fails with null prompt")
  void testExecuteIterationFailsWithNullPrompt() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt(null);

    // Act & Assert
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> orchestratorService.executeIteration(request));

    assertTrue(ex.getMessage().contains("Prompt"));
  }

  @Test
  @DisplayName("Execute iteration stores evidence config if provided")
  void testExecuteIterationStoresEvidenceConfig()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException,
          com.chromascape.web.debug.patchloop.graph.GraphExecutionException {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Test prompt");
    request.setEvidenceConfig(new com.chromascape.web.debug.patchloop.dto.EvidenceConfigDto());
    request.getEvidenceConfig().setMaxLogEntries(100);

    PatchLoopIteration savedIteration = new PatchLoopIteration();
    savedIteration.setIterationId("patchloop_test456");
    savedIteration.setStatus(PatchLoopStatus.QUEUED);

    when(iterationRepository.save(any(PatchLoopIteration.class))).thenReturn(savedIteration);

    // Mock graph orchestrator response (E6.5)
    when(graphOrchestratorService.executeGraph(any(GraphExecuteRequest.class)))
        .thenAnswer(
            invocation -> {
              GraphExecuteResponse response = new GraphExecuteResponse();
              response.setExecutionId("graph_exec_xyz");
              response.setIterationId("patchloop_test456");
              response.setWsUrl("/ws/debug/patch-loop/graph_exec_xyz");
              response.setApiUrl("/api/debug/patch-loop/patchloop_test456/graph");
              return response;
            });

    // Act
    PatchLoopExecuteResponse response = orchestratorService.executeIteration(request);

    // Assert
    assertNotNull(response);

    // Verify evidence config was stored with numeric value fidelity
    ArgumentCaptor<PatchLoopIteration> captor = ArgumentCaptor.forClass(PatchLoopIteration.class);
    verify(iterationRepository, times(1)).save(captor.capture());

    PatchLoopIteration captured = captor.getValue();
    assertNotNull(captured.getEvidenceConfig());
    // Assert serialized value fidelity (maxLogEntries == 100)
    assertEquals(100, captured.getEvidenceConfig().get("maxLogEntries").asInt());
  }

  @Test
  @DisplayName("Get iteration returns iteration if found")
  void testGetIterationReturnsIfFound() {
    // Arrange
    PatchLoopIteration iteration = createTestIteration("iter-123", PatchLoopStatus.COMPLETED);
    when(iterationRepository.findByIterationId("iter-123")).thenReturn(Optional.of(iteration));

    // Act
    Optional<PatchLoopIterationResponse> response = orchestratorService.getIteration("iter-123");

    // Assert
    assertTrue(response.isPresent());
    assertEquals("iter-123", response.get().getIterationId());
    assertEquals("COMPLETED", response.get().getStatus());
    assertEquals("TestScript", response.get().getScriptName());
  }

  @Test
  @DisplayName("Get iteration returns empty if not found")
  void testGetIterationReturnsEmptyIfNotFound() {
    // Arrange
    when(iterationRepository.findByIterationId("nonexistent")).thenReturn(Optional.empty());

    // Act
    Optional<PatchLoopIterationResponse> response = orchestratorService.getIteration("nonexistent");

    // Assert
    assertFalse(response.isPresent());
  }

  @Test
  @DisplayName("List iterations returns all when no filters")
  void testListIterationsReturnsAllWhenNoFilters() {
    // Arrange
    List<PatchLoopIteration> iterations =
        Arrays.asList(
            createTestIteration("iter-1", PatchLoopStatus.COMPLETED),
            createTestIteration("iter-2", PatchLoopStatus.QUEUED));

    when(iterationRepository.findAllByOrderByCreatedAtDesc()).thenReturn(iterations);

    // Act
    List<PatchLoopIterationResponse> response = orchestratorService.listIterations(null, null);

    // Assert
    assertEquals(2, response.size());
    assertEquals("iter-1", response.get(0).getIterationId());
    assertEquals("iter-2", response.get(1).getIterationId());
  }

  @Test
  @DisplayName("List iterations filters by script name")
  void testListIterationsFiltersByScriptName() {
    // Arrange
    List<PatchLoopIteration> iterations =
        Arrays.asList(createTestIteration("iter-1", PatchLoopStatus.COMPLETED));

    when(iterationRepository.findByScriptNameOrderByCreatedAtDesc("TestScript"))
        .thenReturn(iterations);

    // Act
    List<PatchLoopIterationResponse> response =
        orchestratorService.listIterations("TestScript", null);

    // Assert
    assertEquals(1, response.size());
    assertEquals("iter-1", response.get(0).getIterationId());
    verify(iterationRepository, times(1)).findByScriptNameOrderByCreatedAtDesc("TestScript");
  }

  @Test
  @DisplayName("List iterations filters by status")
  void testListIterationsFiltersByStatus() {
    // Arrange
    List<PatchLoopIteration> iterations =
        Arrays.asList(createTestIteration("iter-1", PatchLoopStatus.COMPLETED));

    when(iterationRepository.findByStatusOrderByCreatedAtDesc(PatchLoopStatus.COMPLETED))
        .thenReturn(iterations);

    // Act
    List<PatchLoopIterationResponse> response =
        orchestratorService.listIterations(null, PatchLoopStatus.COMPLETED);

    // Assert
    assertEquals(1, response.size());
    assertEquals("COMPLETED", response.get(0).getStatus());
    verify(iterationRepository, times(1))
        .findByStatusOrderByCreatedAtDesc(PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("List active iterations uses optimized query")
  void testListActiveIterationsUsesOptimizedQuery() {
    // Arrange
    List<PatchLoopIteration> activeIterations =
        Arrays.asList(
            createTestIteration("iter-1", PatchLoopStatus.RUNNING_SCRIPT),
            createTestIteration("iter-2", PatchLoopStatus.ANALYZING));

    when(iterationRepository.findActiveIterations()).thenReturn(activeIterations);

    // Act
    List<PatchLoopIterationResponse> response = orchestratorService.listActiveIterations();

    // Assert
    assertEquals(2, response.size());
    assertEquals("iter-1", response.get(0).getIterationId());
    assertEquals("iter-2", response.get(1).getIterationId());
    assertTrue(response.get(0).getIsActive());
    assertTrue(response.get(1).getIsActive());

    // Verify optimized query was used
    verify(iterationRepository, times(1)).findActiveIterations();
    verify(iterationRepository, never()).findAllByOrderByCreatedAtDesc();
  }

  @Test
  @DisplayName("Iteration response includes isActive and isComplete flags")
  void testIterationResponseIncludesFlags() {
    // Arrange - Active iteration
    PatchLoopIteration activeIteration =
        createTestIteration("iter-active", PatchLoopStatus.RUNNING_SCRIPT);
    when(iterationRepository.findByIterationId("iter-active"))
        .thenReturn(Optional.of(activeIteration));

    // Arrange - Completed iteration
    PatchLoopIteration completedIteration =
        createTestIteration("iter-done", PatchLoopStatus.COMPLETED);
    when(iterationRepository.findByIterationId("iter-done"))
        .thenReturn(Optional.of(completedIteration));

    // Act
    Optional<PatchLoopIterationResponse> activeResponse =
        orchestratorService.getIteration("iter-active");
    Optional<PatchLoopIterationResponse> completedResponse =
        orchestratorService.getIteration("iter-done");

    // Assert - Active iteration
    assertTrue(activeResponse.isPresent());
    assertTrue(activeResponse.get().getIsActive());
    assertFalse(activeResponse.get().getIsComplete());

    // Assert - Completed iteration
    assertTrue(completedResponse.isPresent());
    assertFalse(completedResponse.get().getIsActive());
    assertTrue(completedResponse.get().getIsComplete());
  }

  @Test
  @DisplayName("executeIteration returns consistent iterationId and executionId (E6.5)")
  void testIterationIdConsistency()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException,
          com.chromascape.web.debug.patchloop.graph.GraphExecutionException {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Test prompt");

    PatchLoopIteration savedIteration = new PatchLoopIteration();
    savedIteration.setIterationId("patchloop_test123");
    savedIteration.setStatus(PatchLoopStatus.QUEUED);
    savedIteration.setScriptName("TestScript");

    when(iterationRepository.save(any(PatchLoopIteration.class))).thenReturn(savedIteration);

    // Capture the GraphExecuteRequest to verify iterationId forwarding
    ArgumentCaptor<GraphExecuteRequest> graphRequestCaptor =
        ArgumentCaptor.forClass(GraphExecuteRequest.class);

    // Mock graph orchestrator response
    when(graphOrchestratorService.executeGraph(graphRequestCaptor.capture()))
        .thenAnswer(
            invocation -> {
              GraphExecuteResponse response = new GraphExecuteResponse();
              response.setExecutionId("graph_exec_xyz");
              response.setIterationId("patchloop_test123");
              response.setWsUrl("/ws/debug/patch-loop/graph_exec_xyz");
              response.setApiUrl("/api/debug/patch-loop/patchloop_test123/graph");
              return response;
            });

    // Act
    PatchLoopExecuteResponse response = orchestratorService.executeIteration(request);

    // Assert - response fields are consistent
    assertEquals("patchloop_test123", response.getIterationId());
    assertTrue(response.getApiUrl().contains("patchloop_test123"));

    // Assert - iterationId was correctly forwarded to graph orchestrator (NEW)
    GraphExecuteRequest capturedGraphRequest = graphRequestCaptor.getValue();
    assertEquals(
        "patchloop_test123",
        capturedGraphRequest.getIterationId(),
        "GraphExecuteRequest should contain the same iterationId as the saved iteration");

    // Verify only ONE iteration was saved (no duplicate)
    verify(iterationRepository, times(1)).save(any(PatchLoopIteration.class));
  }

  @Test
  @DisplayName("getIteration includes summary fields when execution exists (E6.5)")
  void testGetIterationIncludesSummaryFields() {
    // Arrange
    String iterationId = "iter_summary";
    String executionId = "exec_summary_123";

    PatchLoopIteration iteration = createTestIteration(iterationId, PatchLoopStatus.COMPLETED);
    iteration.setGraphExecutionId(executionId);

    when(iterationRepository.findByIterationId(iterationId)).thenReturn(Optional.of(iteration));

    // Mock artifact repositories with sample data
    PatchLoopAnalysisArtifact analysis = new PatchLoopAnalysisArtifact();
    analysis.setAnalysisId("analysis_id_1");
    when(analysisArtifactRepository.findByExecutionIdOrderByTimestampAsc(executionId))
        .thenReturn(List.of(analysis));

    // Mock other repositories with empty lists
    when(patchArtifactRepository.findByExecutionIdOrderByCreatedAtAsc(executionId))
        .thenReturn(List.of());
    when(validationArtifactRepository.findByExecutionIdOrderByTimestampAsc(executionId))
        .thenReturn(List.of());
    when(retestArtifactRepository.findByExecutionIdOrderByTimestampAsc(executionId))
        .thenReturn(List.of());
    when(evidenceArtifactRepository.findByExecutionIdOrderByTimestampAsc(executionId))
        .thenReturn(List.of());

    // Act
    Optional<PatchLoopIterationResponse> response = orchestratorService.getIteration(iterationId);

    // Assert
    assertTrue(response.isPresent());
    assertNotNull(response.get().getArtifactSummary());
    assertEquals(1, response.get().getArtifactSummary().getAnalysisCount());
    assertEquals("analysis_id_1", response.get().getArtifactSummary().getLatestAnalysisId());
    assertEquals(0, response.get().getArtifactSummary().getPatchCount());
    assertEquals(0, response.get().getArtifactSummary().getValidationCount());
    assertEquals(0, response.get().getArtifactSummary().getRetestCount());
    assertEquals(0, response.get().getArtifactSummary().getEvidenceCount());

    // Verify retest summary is present with zero values
    assertNotNull(response.get().getRetestSummary());
    assertEquals(0, response.get().getRetestSummary().getTotalRetests());
  }

  @Test
  @DisplayName("getIteration includes empty summaries when no execution exists (E6.5)")
  void testGetIterationIncludesEmptySummariesWhenNoExecution() {
    // Arrange
    String iterationId = "iter_no_exec";

    PatchLoopIteration iteration = createTestIteration(iterationId, PatchLoopStatus.COMPLETED);
    iteration.setGraphExecutionId(null); // No execution ID

    when(iterationRepository.findByIterationId(iterationId)).thenReturn(Optional.of(iteration));

    // Act
    Optional<PatchLoopIterationResponse> response = orchestratorService.getIteration(iterationId);

    // Assert
    assertTrue(response.isPresent());
    // Summaries should still be present (not null) with zero values
    assertNotNull(response.get().getArtifactSummary());
    assertEquals(0, response.get().getArtifactSummary().getAnalysisCount());
    assertEquals(0, response.get().getArtifactSummary().getPatchCount());
    assertEquals(0, response.get().getArtifactSummary().getValidationCount());
    assertEquals(0, response.get().getArtifactSummary().getRetestCount());
    assertEquals(0, response.get().getArtifactSummary().getEvidenceCount());

    assertNotNull(response.get().getRetestSummary());
    assertEquals(0, response.get().getRetestSummary().getTotalRetests());
  }

  // ========== Helper Methods ==========

  private PatchLoopIteration createTestIteration(String iterationId, PatchLoopStatus status) {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId(iterationId);
    iteration.setScriptName("TestScript");
    iteration.setPrompt("Test prompt");
    iteration.setStatus(status);
    iteration.setCreatedAt(LocalDateTime.now());
    iteration.setLastStateAt(LocalDateTime.now());
    return iteration;
  }
}

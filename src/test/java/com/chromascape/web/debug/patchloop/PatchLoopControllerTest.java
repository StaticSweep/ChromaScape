package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.controller.PatchLoopController;
import com.chromascape.web.debug.patchloop.dto.ArtifactsResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopEventResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteRequest;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopIterationResponse;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.service.PatchLoopAnalysisClient;
import com.chromascape.web.debug.patchloop.service.PatchLoopGraphOrchestratorService;
import com.chromascape.web.debug.patchloop.service.PatchLoopOrchestratorService;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpHealthResponse;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportClient;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportException;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Test suite for PatchLoopController. */
@ExtendWith(MockitoExtension.class)
class PatchLoopControllerTest {

  @Mock private PatchLoopOrchestratorService orchestratorService;
  @Mock private PatchLoopGraphOrchestratorService graphOrchestratorService;
  @Mock private PatchLoopAnalysisClient analysisClient;
  @Mock private McpTransportClient mcpTransportClient;
  @Mock private Micro6ExecutorCatalogService micro6ExecutorCatalogService;

  private PatchLoopController controller;

  @BeforeEach
  void setUp() throws Exception {
    controller =
        new PatchLoopController(
            orchestratorService,
            graphOrchestratorService,
            analysisClient,
            mcpTransportClient,
            micro6ExecutorCatalogService);
    setAutofixEnabled(true);
  }

  @Test
  @DisplayName("POST /execute returns 200 OK with iteration ID and E6.5 fields")
  void testExecuteIterationReturns200() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Why failed?");

    PatchLoopExecuteResponse serviceResponse = new PatchLoopExecuteResponse();
    serviceResponse.setIterationId("iter-123");
    serviceResponse.setExecutionId("graph_exec_abc");
    serviceResponse.setStatus("QUEUED");
    serviceResponse.setMessage("Started");
    serviceResponse.setWsUrl("/ws/debug/patch-loop/graph_exec_abc");
    serviceResponse.setApiUrl("/api/debug/patch-loop/iter-123/graph");

    when(orchestratorService.executeIteration(any())).thenReturn(serviceResponse);

    // Act
    ResponseEntity<Object> response = controller.executeIteration(request);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof PatchLoopExecuteResponse);

    PatchLoopExecuteResponse body = (PatchLoopExecuteResponse) response.getBody();
    assertEquals("iter-123", body.getIterationId());
    assertEquals("QUEUED", body.getStatus());
    assertEquals("graph_exec_abc", body.getExecutionId());
    assertEquals("/ws/debug/patch-loop/graph_exec_abc", body.getWsUrl());
    assertEquals("/api/debug/patch-loop/iter-123/graph", body.getApiUrl());

    verify(orchestratorService, times(1)).executeIteration(request);
  }

  @Test
  @DisplayName("POST /execute returns 400 Bad Request for invalid request")
  void testExecuteIterationReturns400ForInvalidRequest() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName(null); // Invalid

    when(orchestratorService.executeIteration(any()))
        .thenThrow(new IllegalArgumentException("Script name is required"));

    // Act
    ResponseEntity<Object> response = controller.executeIteration(request);

    // Assert
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody().toString().contains("Script name"));
  }

  @Test
  @DisplayName("POST /execute rejects autofix_loop when autofix is disabled")
  void testExecuteIterationRejectsAutofixLoopWhenDisabled() throws Exception {
    setAutofixEnabled(false);

    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Fix this");
    request.setExecutionMode("autofix_loop");

    ResponseEntity<Object> response = controller.executeIteration(request);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody() instanceof Map);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("AUTOFIX_DISABLED", body.get("errorCode"));
    verifyNoInteractions(orchestratorService);
  }

  @Test
  @DisplayName("POST /execute-graph rejects full_loop when autofix is disabled")
  void testExecuteGraphRejectsFullLoopWhenDisabled() throws Exception {
    setAutofixEnabled(false);

    com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest request =
        new com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest();
    request.setScriptName("TestScript");
    request.setMermaid("graph TD\nA[Start]");
    request.setExecutionMode("full_loop");

    ResponseEntity<Object> response = controller.executeGraph(request);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody() instanceof Map);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("AUTOFIX_DISABLED", body.get("errorCode"));
    verifyNoInteractions(graphOrchestratorService);
  }

  @Test
  @DisplayName("POST /execute returns 500 Internal Server Error on unexpected exception")
  void testExecuteIterationReturns500OnException() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Test");

    when(orchestratorService.executeIteration(any()))
        .thenThrow(new RuntimeException("Database error"));

    // Act
    ResponseEntity<Object> response = controller.executeIteration(request);

    // Assert
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody().toString().contains("Failed to execute"));
  }

  @Test
  @DisplayName("GET /{iterationId} returns 200 OK with iteration details")
  void testGetIterationReturns200() {
    // Arrange
    PatchLoopIterationResponse iteration = createIterationResponse("iter-123", "COMPLETED");
    when(orchestratorService.getIteration("iter-123")).thenReturn(Optional.of(iteration));

    // Act
    ResponseEntity<Object> response = controller.getIteration("iter-123");

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof PatchLoopIterationResponse);

    PatchLoopIterationResponse body = (PatchLoopIterationResponse) response.getBody();
    assertEquals("iter-123", body.getIterationId());
    assertEquals("COMPLETED", body.getStatus());
  }

  @Test
  @DisplayName("GET /{iterationId} returns 404 Not Found for missing iteration")
  void testGetIterationReturns404ForMissing() {
    // Arrange
    when(orchestratorService.getIteration("nonexistent")).thenReturn(Optional.empty());

    // Act
    ResponseEntity<Object> response = controller.getIteration("nonexistent");

    // Assert
    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  @DisplayName("GET /{iterationId} returns 500 on exception")
  void testGetIterationReturns500OnException() {
    // Arrange
    when(orchestratorService.getIteration("iter-123"))
        .thenThrow(new RuntimeException("Database error"));

    // Act
    ResponseEntity<Object> response = controller.getIteration("iter-123");

    // Assert
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  @DisplayName("GET /{iterationId}/events returns 200 OK with events list")
  void testGetEventsReturns200() {
    // Arrange
    PatchLoopEventResponse event1 = new PatchLoopEventResponse();
    event1.setEventType("ITERATION_CREATED");
    event1.setMessage("Iteration created");

    PatchLoopEventResponse event2 = new PatchLoopEventResponse();
    event2.setEventType("SCRIPT_STARTED");
    event2.setMessage("Script started");

    List<PatchLoopEventResponse> events = Arrays.asList(event1, event2);
    when(orchestratorService.getEvents("iter-123")).thenReturn(events);

    // Act
    ResponseEntity<Object> response = controller.getEvents("iter-123");

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof List);

    @SuppressWarnings("unchecked")
    List<PatchLoopEventResponse> body = (List<PatchLoopEventResponse>) response.getBody();
    assertEquals(2, body.size());
    assertEquals("ITERATION_CREATED", body.get(0).getEventType());
  }

  @Test
  @DisplayName("GET /{iterationId}/events returns 500 on exception")
  void testGetEventsReturns500OnException() {
    // Arrange
    when(orchestratorService.getEvents("iter-123"))
        .thenThrow(new RuntimeException("Database error"));

    // Act
    ResponseEntity<Object> response = controller.getEvents("iter-123");

    // Assert
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  @DisplayName("GET / returns 200 OK with all iterations when no filters")
  void testListIterationsReturns200WithAllIterations() {
    // Arrange
    List<PatchLoopIterationResponse> iterations =
        Arrays.asList(
            createIterationResponse("iter-1", "COMPLETED"),
            createIterationResponse("iter-2", "QUEUED"));

    when(orchestratorService.listIterations(null, null)).thenReturn(iterations);

    // Act
    ResponseEntity<Object> response = controller.listIterations(null, null);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());

    @SuppressWarnings("unchecked")
    List<PatchLoopIterationResponse> body = (List<PatchLoopIterationResponse>) response.getBody();
    assertEquals(2, body.size());
  }

  @Test
  @DisplayName("GET / with scriptName filter calls service correctly")
  void testListIterationsWithScriptNameFilter() {
    // Arrange
    List<PatchLoopIterationResponse> iterations =
        Collections.singletonList(createIterationResponse("iter-1", "COMPLETED"));

    when(orchestratorService.listIterations("TestScript", null)).thenReturn(iterations);

    // Act
    ResponseEntity<Object> response = controller.listIterations("TestScript", null);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(orchestratorService, times(1)).listIterations("TestScript", null);
  }

  @Test
  @DisplayName("GET / with status filter calls service correctly")
  void testListIterationsWithStatusFilter() {
    // Arrange
    List<PatchLoopIterationResponse> iterations =
        Collections.singletonList(createIterationResponse("iter-1", "COMPLETED"));

    when(orchestratorService.listIterations(null, PatchLoopStatus.COMPLETED))
        .thenReturn(iterations);

    // Act
    ResponseEntity<Object> response = controller.listIterations(null, "COMPLETED");

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(orchestratorService, times(1)).listIterations(null, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("GET / with invalid status returns 400 Bad Request")
  void testListIterationsWithInvalidStatusReturns400() {
    // Act
    ResponseEntity<Object> response = controller.listIterations(null, "INVALID_STATUS");

    // Assert
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody().toString().contains("Invalid status"));
  }

  @Test
  @DisplayName("GET / returns 500 on exception")
  void testListIterationsReturns500OnException() {
    // Arrange
    when(orchestratorService.listIterations(null, null))
        .thenThrow(new RuntimeException("Database error"));

    // Act
    ResponseEntity<Object> response = controller.listIterations(null, null);

    // Assert
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  @DisplayName("GET /active returns 200 OK with active iterations only")
  void testGetActiveIterationsReturns200() {
    // Arrange
    PatchLoopIterationResponse active1 = createIterationResponse("iter-1", "RUNNING_SCRIPT");
    active1.setIsActive(true);

    PatchLoopIterationResponse active2 = createIterationResponse("iter-2", "ANALYZING");
    active2.setIsActive(true);

    List<PatchLoopIterationResponse> activeIterations = Arrays.asList(active1, active2);
    when(orchestratorService.listActiveIterations()).thenReturn(activeIterations);

    // Act
    ResponseEntity<Object> response = controller.getActiveIterations();

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());

    @SuppressWarnings("unchecked")
    List<PatchLoopIterationResponse> body = (List<PatchLoopIterationResponse>) response.getBody();
    assertEquals(2, body.size());
    assertTrue(body.get(0).getIsActive());
    assertTrue(body.get(1).getIsActive());

    // Verify optimized method was called
    verify(orchestratorService, times(1)).listActiveIterations();
    verify(orchestratorService, never()).listIterations(any(), any());
  }

  @Test
  @DisplayName("GET /active returns empty list when no active iterations")
  void testGetActiveIterationsReturnsEmptyList() {
    // Arrange
    when(orchestratorService.listActiveIterations()).thenReturn(Collections.emptyList());

    // Act
    ResponseEntity<Object> response = controller.getActiveIterations();

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());

    @SuppressWarnings("unchecked")
    List<PatchLoopIterationResponse> body = (List<PatchLoopIterationResponse>) response.getBody();
    assertTrue(body.isEmpty());
  }

  @Test
  @DisplayName("GET /active returns 500 on exception")
  void testGetActiveIterationsReturns500OnException() {
    // Arrange
    when(orchestratorService.listActiveIterations())
        .thenThrow(new RuntimeException("Database error"));

    // Act
    ResponseEntity<Object> response = controller.getActiveIterations();

    // Assert
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  // ========== Graph-based Endpoint Tests ==========

  @Test
  @DisplayName("POST /compile returns 200 OK with compiled graph info")
  void testCompileGraphReturns200()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException {
    // Arrange
    com.chromascape.web.debug.patchloop.dto.CompileRequest request =
        new com.chromascape.web.debug.patchloop.dto.CompileRequest();
    request.setMermaid("graph TD\n  A[start] --> B[end]");

    com.chromascape.web.debug.patchloop.dto.GraphExecuteResponse.CompiledGraphInfo graphInfo =
        mock(com.chromascape.web.debug.patchloop.dto.GraphExecuteResponse.CompiledGraphInfo.class);
    when(graphOrchestratorService.compileGraph(anyString())).thenReturn(graphInfo);

    // Act
    ResponseEntity<Object> response = controller.compileGraph(request);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    verify(graphOrchestratorService, times(1)).compileGraph(request.getMermaid());
  }

  @Test
  @DisplayName("POST /compile returns 400 Bad Request for invalid Mermaid")
  void testCompileGraphReturns400ForInvalidMermaid()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException {
    // Arrange
    com.chromascape.web.debug.patchloop.dto.CompileRequest request =
        new com.chromascape.web.debug.patchloop.dto.CompileRequest();
    request.setMermaid("invalid mermaid syntax");

    when(graphOrchestratorService.compileGraph(anyString()))
        .thenThrow(
            new com.chromascape.web.debug.patchloop.graph.GraphCompilationException(
                "Invalid syntax"));

    // Act
    ResponseEntity<Object> response = controller.compileGraph(request);

    // Assert
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody().toString().contains("Graph compilation failed"));
  }

  @Test
  @DisplayName("POST /compile returns 500 on unexpected exception")
  void testCompileGraphReturns500OnException()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException {
    // Arrange
    com.chromascape.web.debug.patchloop.dto.CompileRequest request =
        new com.chromascape.web.debug.patchloop.dto.CompileRequest();
    request.setMermaid("graph TD\n  A[start] --> B[end]");

    when(graphOrchestratorService.compileGraph(anyString()))
        .thenThrow(new RuntimeException("Internal error"));

    // Act
    ResponseEntity<Object> response = controller.compileGraph(request);

    // Assert
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  @DisplayName("GET /{iterationId}/artifacts uses iterationId path contract")
  void testGetArtifactsUsesIterationIdPath() {
    // Arrange
    String iterationId = "iter-123";
    when(graphOrchestratorService.getArtifacts(iterationId)).thenReturn(null);

    // Act
    ResponseEntity<Object> response = controller.getArtifacts(iterationId);

    // Assert
    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    verify(graphOrchestratorService, times(1)).getArtifacts(iterationId);
  }

  @Test
  @DisplayName("GET /{iterationId}/artifacts returns scriptRuns when present")
  void testGetArtifactsReturnsScriptRuns() {
    String iterationId = "iter-script-runs";
    ArtifactsResponse artifacts = new ArtifactsResponse();
    ArtifactsResponse.ScriptRunArtifact runArtifact = new ArtifactsResponse.ScriptRunArtifact();
    runArtifact.setNodeId("RUN_SCRIPT");
    runArtifact.setScriptName("MyScript");
    runArtifact.setBackend("micro6_local_executor");
    runArtifact.setTerminalStatus("FAILED");
    runArtifact.setTimedOut(false);
    runArtifact.setSuccess(false);
    runArtifact.setErrorMessage("boom");
    artifacts.setScriptRuns(List.of(runArtifact));

    when(graphOrchestratorService.getArtifacts(iterationId)).thenReturn(artifacts);

    ResponseEntity<Object> response = controller.getArtifacts(iterationId);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof ArtifactsResponse);
    ArtifactsResponse body = (ArtifactsResponse) response.getBody();
    assertNotNull(body.getScriptRuns());
    assertEquals(1, body.getScriptRuns().size());
    assertEquals("RUN_SCRIPT", body.getScriptRuns().get(0).getNodeId());
  }

  @Test
  @DisplayName("POST /execute-graph creates graph execution (E6.5 non-regression)")
  void testExecuteGraphCreatesExecution()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException,
          com.chromascape.web.debug.patchloop.graph.GraphExecutionException {
    // Arrange
    com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest request =
        new com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest();
    request.setMermaid("graph TD; A-->B;");
    request.setScriptName("TestScript");
    request.setGoalPrompt("Test prompt");

    com.chromascape.web.debug.patchloop.dto.GraphExecuteResponse graphResponse =
        new com.chromascape.web.debug.patchloop.dto.GraphExecuteResponse();
    graphResponse.setExecutionId("graph_exec_test");
    graphResponse.setIterationId("patchloop_test");
    graphResponse.setWsUrl("/ws/debug/patch-loop/graph_exec_test");
    graphResponse.setApiUrl("/api/debug/patch-loop/patchloop_test/graph");

    when(graphOrchestratorService.executeGraph(any())).thenReturn(graphResponse);

    // Act
    ResponseEntity<Object> response = controller.executeGraph(request);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    verify(graphOrchestratorService, times(1)).executeGraph(request);
  }

  @Test
  @DisplayName("POST /execute-graph returns 400 when iterationId not found (E6.5)")
  void testExecuteGraphReturns400ForMissingIteration()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException,
          com.chromascape.web.debug.patchloop.graph.GraphExecutionException {
    // Arrange
    com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest request =
        new com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest();
    request.setMermaid("graph TD; A-->B;");
    request.setScriptName("TestScript");
    request.setIterationId("nonexistent_iter");

    when(graphOrchestratorService.executeGraph(any()))
        .thenThrow(new IllegalArgumentException("Iteration not found: nonexistent_iter"));

    // Act
    ResponseEntity<Object> response = controller.executeGraph(request);

    // Assert
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody().toString().contains("Iteration not found"));
  }

  @Test
  @DisplayName("POST /execute-graph returns 400 for blank iterationId (E6.5)")
  void testExecuteGraphReturns400ForBlankIterationId()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException,
          com.chromascape.web.debug.patchloop.graph.GraphExecutionException {
    // Arrange
    com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest request =
        new com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest();
    request.setMermaid("graph TD; A-->B;");
    request.setScriptName("TestScript");
    request.setIterationId("   ");

    when(graphOrchestratorService.executeGraph(any()))
        .thenThrow(new IllegalArgumentException("iterationId must not be blank"));

    // Act
    ResponseEntity<Object> response = controller.executeGraph(request);

    // Assert
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody().toString().contains("iterationId must not be blank"));
  }

  @Test
  @DisplayName("GET /provider/status returns provider status")
  void testGetProviderStatusReturns200() throws Exception {
    // Arrange
    when(analysisClient.getProviderName()).thenReturn("codex-mcp");
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("provider", "codex-cli");
    metadata.put("model", "claude-opus-4-6");
    metadata.put("backend", "mcp");
    when(mcpTransportClient.healthCheck())
        .thenReturn(new McpHealthResponse(true, "2.0.0", metadata));

    // Act
    ResponseEntity<Object> response = controller.getProviderStatus();

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("codex-mcp", body.get("providerName"));
    assertEquals("codex-mcp", body.get("frameworkProvider"));
    assertEquals("codex-cli", body.get("runtimeProvider"));
    assertEquals("claude-opus-4-6", body.get("runtimeModel"));
    assertEquals("mcp", body.get("backend"));
    assertEquals(true, body.get("available"));
    assertEquals("analysis_only", body.get("defaultMode"));
    assertEquals("external", body.get("bridgeMode"));
    assertEquals("provider_ready", body.get("availabilityReason"));

    verify(analysisClient, times(1)).getProviderName();
    verify(analysisClient, never()).isProviderAvailable();
    verify(mcpTransportClient, times(1)).healthCheck();
  }

  @Test
  @DisplayName("GET /provider/status returns claude-api runtime details")
  void testGetProviderStatusReturnsClaudeApiRuntimeDetails() throws Exception {
    // Arrange
    when(analysisClient.getProviderName()).thenReturn("claude-api");
    when(analysisClient.isProviderAvailable()).thenReturn(true);

    // Act
    ResponseEntity<Object> response = controller.getProviderStatus();

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("claude-api", body.get("providerName"));
    assertEquals("claude-api", body.get("frameworkProvider"));
    assertEquals("claude-api", body.get("runtimeProvider"));
    assertEquals("api", body.get("backend"));
    assertEquals(true, body.get("available"));
    assertEquals("managed", body.get("bridgeMode"));
    assertEquals("provider_ready", body.get("availabilityReason"));

    verify(analysisClient, times(1)).getProviderName();
    verify(analysisClient, times(1)).isProviderAvailable();
    verify(mcpTransportClient, never()).healthCheck();
  }

  @Test
  @DisplayName("GET /provider/status returns openai-codex runtime details")
  void testGetProviderStatusReturnsOpenAiCodexRuntimeDetails() throws Exception {
    // Arrange
    when(analysisClient.getProviderName()).thenReturn("openai-codex");
    when(analysisClient.isProviderAvailable()).thenReturn(true);

    // Act
    ResponseEntity<Object> response = controller.getProviderStatus();

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("openai-codex", body.get("providerName"));
    assertEquals("openai-codex", body.get("frameworkProvider"));
    assertEquals("openai-codex", body.get("runtimeProvider"));
    assertEquals("api", body.get("backend"));
    assertEquals(true, body.get("available"));
    assertEquals("managed", body.get("bridgeMode"));
    assertEquals("provider_ready", body.get("availabilityReason"));

    verify(analysisClient, times(1)).getProviderName();
    verify(analysisClient, times(1)).isProviderAvailable();
    verify(mcpTransportClient, never()).healthCheck();
  }

  @Test
  @DisplayName("GET /provider/status keeps codex runtime wrapper when MCP health check throws")
  void testGetProviderStatusFallsBackToFrameworkRuntimeOnMcpException() throws Exception {
    // Arrange
    when(analysisClient.getProviderName()).thenReturn("codex-mcp");
    when(mcpTransportClient.healthCheck())
        .thenThrow(
            new McpTransportException(
                McpTransportException.McpErrorType.CONNECTION_REFUSED,
                "Connection refused",
                false));

    // Act
    ResponseEntity<Object> response = controller.getProviderStatus();

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("codex-mcp", body.get("frameworkProvider"));
    assertEquals("codex-cli", body.get("runtimeProvider"));
    assertEquals("mcp", body.get("backend"));
    assertEquals(false, body.get("available"));
    assertEquals("external", body.get("bridgeMode"));
    assertEquals("mcp_health_check_failed", body.get("availabilityReason"));
  }

  @Test
  @DisplayName("GET /provider/status returns 500 on exception")
  void testGetProviderStatusReturns500OnException() {
    // Arrange
    when(analysisClient.getProviderName()).thenThrow(new RuntimeException("Provider error"));

    // Act
    ResponseEntity<Object> response = controller.getProviderStatus();

    // Assert
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  @DisplayName("GET /catalog returns degraded payload when supervisor is unreachable")
  void testGetCatalogReturnsDegradedPayloadWhenSupervisorUnavailable() throws Exception {
    setPrivateField(controller, "supervisorBaseUrl", "http://127.0.0.1:1");
    when(micro6ExecutorCatalogService.getCatalog(false))
        .thenReturn(new ObjectMapper().readTree("{\"tasks\":[]}"));

    ResponseEntity<Object> response = controller.getCatalog(false);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertTrue(body.containsKey("tasks"));
    assertTrue(body.containsKey("sources"));
    assertTrue(body.containsKey("availableClientTypes"));
    assertTrue(body.containsKey("errors"));
    assertTrue(body.containsKey("catalogUrl"));
    assertEquals("http://127.0.0.1:1/api/dev/workers/catalog", body.get("catalogUrl"));

    @SuppressWarnings("unchecked")
    List<Object> tasks = (List<Object>) body.get("tasks");
    @SuppressWarnings("unchecked")
    List<Object> errors = (List<Object>) body.get("errors");
    assertTrue(tasks.isEmpty());
    assertFalse(errors.isEmpty());
  }

  @Test
  @DisplayName("GET /catalog refresh=true appends refresh query param")
  void testGetCatalogRefreshParamIncludedInCatalogUrl() throws Exception {
    setPrivateField(controller, "supervisorBaseUrl", "http://127.0.0.1:1");
    when(micro6ExecutorCatalogService.getCatalog(true))
        .thenReturn(new ObjectMapper().readTree("{\"tasks\":[]}"));

    ResponseEntity<Object> response = controller.getCatalog(true);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("http://127.0.0.1:1/api/dev/workers/catalog?refresh=true", body.get("catalogUrl"));
  }

  @Test
  @DisplayName("GET /catalog includes normalized micro6 executor tasks and source status")
  void testGetCatalogMergesMicro6ExecutorTasks() throws Exception {
    setPrivateField(controller, "supervisorBaseUrl", "http://127.0.0.1:1");
    when(micro6ExecutorCatalogService.getCatalog(false))
        .thenReturn(
            new ObjectMapper()
                .readTree(
                    """
                    {"catalog_source":"primary","source_client_type":"micro6","tasks":[
                      {"key":"CONFIGURABLE_COOKING","name":"Configurable Cooking","enabled":true,"category":"skilling","tags":["cooking"]},
                      {"task_key":"DISABLED_TASK","display_name":"Disabled Task","enabled":false}
                    ]}
                    """));

    ResponseEntity<Object> response = controller.getCatalog(false);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tasks = (List<Map<String, Object>>) body.get("tasks");
    assertEquals(1, tasks.size());
    assertEquals("micro6", tasks.get(0).get("client_type"));
    assertEquals("CONFIGURABLE_COOKING", tasks.get(0).get("task_key"));
    assertEquals("queue", tasks.get(0).get("execution_mode"));
    assertEquals(true, tasks.get(0).get("queueable"));

    @SuppressWarnings("unchecked")
    Map<String, Object> sources = (Map<String, Object>) body.get("sources");
    assertTrue(sources.containsKey("micro6_executor"));
    @SuppressWarnings("unchecked")
    Map<String, Object> micro6Source = (Map<String, Object>) sources.get("micro6_executor");
    assertEquals(true, micro6Source.get("ok"));
    assertEquals(1, micro6Source.get("task_count"));
    assertEquals("primary", micro6Source.get("catalog_source"));
    assertEquals("micro6", micro6Source.get("source_client_type"));
  }

  @Test
  @DisplayName("GET /catalog falls back gracefully when micro6 executor catalog fails")
  void testGetCatalogHandlesMicro6ExecutorFailure() throws Exception {
    setPrivateField(controller, "supervisorBaseUrl", "http://127.0.0.1:1");
    when(micro6ExecutorCatalogService.getCatalog(false))
        .thenThrow(new RuntimeException("micro6 executor offline"));

    ResponseEntity<Object> response = controller.getCatalog(false);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    @SuppressWarnings("unchecked")
    List<String> errors = (List<String>) body.get("errors");
    assertTrue(errors.stream().anyMatch(msg -> msg.contains("micro6_executor")));
    @SuppressWarnings("unchecked")
    Map<String, Object> sources = (Map<String, Object>) body.get("sources");
    @SuppressWarnings("unchecked")
    Map<String, Object> micro6Source = (Map<String, Object>) sources.get("micro6_executor");
    assertEquals(false, micro6Source.get("ok"));
  }

  // ========== Helper Methods ==========

  private void setPrivateField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private void setAutofixEnabled(boolean value) throws Exception {
    Field field = PatchLoopController.class.getDeclaredField("patchLoopAutofixEnabled");
    field.setAccessible(true);
    field.set(controller, value);
  }

  private PatchLoopIterationResponse createIterationResponse(String iterationId, String status) {
    PatchLoopIterationResponse response = new PatchLoopIterationResponse();
    response.setIterationId(iterationId);
    response.setStatus(status);
    response.setScriptName("TestScript");
    response.setPrompt("Test prompt");
    response.setCreatedAt(LocalDateTime.now());
    response.setIsActive(!status.equals("COMPLETED") && !status.equals("FAILED"));
    response.setIsComplete(
        status.equals("COMPLETED")
            || status.equals("FAILED")
            || status.equals("INSUFFICIENT_EVIDENCE"));
    return response;
  }
}

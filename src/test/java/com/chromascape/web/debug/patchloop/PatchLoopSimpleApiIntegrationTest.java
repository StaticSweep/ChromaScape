package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.controller.PatchLoopController;
import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.dto.SessionContextDto;
import com.chromascape.web.debug.patchloop.service.PatchLoopGraphOrchestratorService;
import com.chromascape.web.debug.patchloop.service.PatchLoopOrchestratorService;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Integration tests for simple API wrapper path.
 *
 * <p>Tests that /execute endpoint correctly delegates to graph orchestrator and returns properly
 * formatted responses with all E6.5 fields.
 *
 * @since 2.1.0
 */
@ExtendWith(MockitoExtension.class)
class PatchLoopSimpleApiIntegrationTest {

  @Mock private PatchLoopOrchestratorService orchestratorService;
  @Mock private PatchLoopGraphOrchestratorService graphOrchestratorService;

  private PatchLoopController controller;

  @BeforeEach
  void setUp() throws Exception {
    controller =
        new PatchLoopController(orchestratorService, graphOrchestratorService, null, null, null);
    setAutofixEnabled(true);
  }

  @Test
  @DisplayName("POST /execute delegates to graph orchestrator and returns execution details (E6.5)")
  void testExecuteDelegatesToGraphOrchestrator() throws Exception {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Why did it fail?");
    request.setExecutionMode("analysis_only");
    request.setMaxAttempts(3);

    when(orchestratorService.executeIteration(any()))
        .thenAnswer(
            invocation -> {
              PatchLoopExecuteResponse response = new PatchLoopExecuteResponse();
              response.setIterationId("patchloop_test123");
              response.setExecutionId("graph_exec_abc456");
              response.setStatus("QUEUED");
              response.setMessage("Iteration started, processing asynchronously");
              response.setWsUrl("/ws/debug/patch-loop/graph_exec_abc456");
              response.setApiUrl("/api/debug/patch-loop/patchloop_test123/graph");
              return response;
            });

    // Act
    ResponseEntity<Object> response = controller.executeIteration(request);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody() instanceof PatchLoopExecuteResponse);

    PatchLoopExecuteResponse body = (PatchLoopExecuteResponse) response.getBody();
    assertEquals("patchloop_test123", body.getIterationId());
    assertEquals("graph_exec_abc456", body.getExecutionId());
    assertEquals("/ws/debug/patch-loop/graph_exec_abc456", body.getWsUrl());
    assertEquals("/api/debug/patch-loop/patchloop_test123/graph", body.getApiUrl());

    verify(orchestratorService, times(1)).executeIteration(request);
  }

  @Test
  @DisplayName("POST /execute persists all new E6.5 request fields")
  void testExecutePersistsE65Fields() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Test prompt");
    request.setContext("Additional context");
    request.setExecutionMode("autofix_loop");
    request.setMaxAttempts(5);
    request.setPromptType(PromptType.FEATURE);

    SessionContextDto sessionContext = new SessionContextDto();
    sessionContext.setDebugSessionId("debug_session_123");
    request.setSessionContext(sessionContext);

    when(orchestratorService.executeIteration(any()))
        .thenAnswer(
            invocation -> {
              PatchLoopExecuteRequest req = invocation.getArgument(0);
              // Verify fields were passed through
              assertEquals("autofix_loop", req.getExecutionMode());
              assertEquals(5, req.getMaxAttempts());
              assertEquals(PromptType.FEATURE, req.getPromptType());
              assertNotNull(req.getSessionContext());
              assertEquals("debug_session_123", req.getSessionContext().getDebugSessionId());

              PatchLoopExecuteResponse response = new PatchLoopExecuteResponse();
              response.setIterationId("patchloop_test");
              response.setExecutionId("graph_exec_test");
              response.setStatus("QUEUED");
              response.setMessage("Started");
              return response;
            });

    // Act
    ResponseEntity<Object> response = controller.executeIteration(request);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(orchestratorService, times(1)).executeIteration(request);
  }

  @Test
  @DisplayName("POST /execute with autofix_loop mode enables retesting")
  void testExecuteWithAutofixLoopMode() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Fix the issue");
    request.setExecutionMode("autofix_loop");
    request.setMaxAttempts(3);

    when(orchestratorService.executeIteration(any()))
        .thenAnswer(
            invocation -> {
              PatchLoopExecuteRequest req = invocation.getArgument(0);
              assertEquals("autofix_loop", req.getExecutionMode());
              assertEquals(3, req.getMaxAttempts());

              PatchLoopExecuteResponse response = new PatchLoopExecuteResponse();
              response.setIterationId("patchloop_autofix");
              response.setExecutionId("graph_exec_autofix");
              response.setStatus("QUEUED");
              response.setMessage("Autofix loop started");
              return response;
            });

    // Act
    ResponseEntity<Object> response = controller.executeIteration(request);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    PatchLoopExecuteResponse body = (PatchLoopExecuteResponse) response.getBody();
    assertNotNull(body);
    assertEquals("patchloop_autofix", body.getIterationId());
  }

  @Test
  @DisplayName("POST /execute rejects autofix_loop when autofix is disabled")
  void testExecuteRejectsAutofixLoopWhenDisabled() throws Exception {
    setAutofixEnabled(false);

    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Fix the issue");
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
  @DisplayName("POST /execute rejects legacy full_loop when autofix is disabled")
  void testExecuteRejectsFullLoopWhenDisabled() throws Exception {
    setAutofixEnabled(false);

    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Fix the issue");
    request.setExecutionMode("full_loop");

    ResponseEntity<Object> response = controller.executeIteration(request);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody() instanceof Map);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("AUTOFIX_DISABLED", body.get("errorCode"));
    verifyNoInteractions(orchestratorService);
  }

  @Test
  @DisplayName("POST /execute returns 400 for invalid execution mode")
  void testExecuteReturns400ForInvalidExecutionMode() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Test");
    request.setExecutionMode("invalid_mode");

    when(orchestratorService.executeIteration(any()))
        .thenThrow(new IllegalArgumentException("Invalid execution mode"));

    // Act
    ResponseEntity<Object> response = controller.executeIteration(request);

    // Assert
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  @DisplayName("POST /execute returns 400 for missing required fields")
  void testExecuteReturns400ForMissingFields() {
    // Arrange - Missing script name
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setPrompt("Test prompt");

    when(orchestratorService.executeIteration(any()))
        .thenThrow(new IllegalArgumentException("Script name is required"));

    // Act
    ResponseEntity<Object> response = controller.executeIteration(request);

    // Assert
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());

    // Arrange - Missing prompt
    PatchLoopExecuteRequest request2 = new PatchLoopExecuteRequest();
    request2.setScriptName("TestScript");

    when(orchestratorService.executeIteration(any()))
        .thenThrow(new IllegalArgumentException("Prompt is required"));

    // Act
    ResponseEntity<Object> response2 = controller.executeIteration(request2);

    // Assert
    assertEquals(HttpStatus.BAD_REQUEST, response2.getStatusCode());
  }

  @Test
  @DisplayName("POST /execute handles graph execution failure")
  void testExecuteHandlesGraphExecutionFailure() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Test prompt");

    when(orchestratorService.executeIteration(any()))
        .thenThrow(new RuntimeException("Failed to execute patch loop"));

    // Act
    ResponseEntity<Object> response = controller.executeIteration(request);

    // Assert
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  @DisplayName("POST /execute with evidence config passes through correctly")
  void testExecuteWithEvidenceConfig() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Test prompt");

    EvidenceConfigDto evidenceConfig = new EvidenceConfigDto();
    evidenceConfig.setMaxLogEntries(1000);
    evidenceConfig.setMaxTickLogs(500);
    request.setEvidenceConfig(evidenceConfig);

    when(orchestratorService.executeIteration(any()))
        .thenAnswer(
            invocation -> {
              PatchLoopExecuteRequest req = invocation.getArgument(0);
              assertNotNull(req.getEvidenceConfig());
              assertEquals(1000, req.getEvidenceConfig().getMaxLogEntries());
              assertEquals(500, req.getEvidenceConfig().getMaxTickLogs());

              PatchLoopExecuteResponse response = new PatchLoopExecuteResponse();
              response.setIterationId("patchloop_evidence");
              response.setExecutionId("graph_exec_evidence");
              response.setStatus("QUEUED");
              return response;
            });

    // Act
    ResponseEntity<Object> response = controller.executeIteration(request);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  // ========== Non-Regression Tests for /execute-graph ==========

  @Test
  @DisplayName("POST /execute-graph without iterationId creates new execution (non-regression)")
  void testExecuteGraphWithoutIterationId()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException,
          com.chromascape.web.debug.patchloop.graph.GraphExecutionException {
    // Arrange
    GraphExecuteRequest request = new GraphExecuteRequest();
    request.setMermaid("graph TD; A-->B;");
    request.setScriptName("TestScript");
    request.setGoalPrompt("Test prompt");

    when(graphOrchestratorService.executeGraph(any()))
        .thenAnswer(
            invocation -> {
              GraphExecuteResponse response = new GraphExecuteResponse();
              response.setExecutionId("graph_direct_123");
              response.setIterationId("patchloop_auto_456");
              response.setWsUrl("/ws/debug/patch-loop/graph_direct_123");
              response.setApiUrl("/api/debug/patch-loop/patchloop_auto_456/graph");
              return response;
            });

    // Act
    ResponseEntity<Object> response = controller.executeGraph(request);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(graphOrchestratorService, times(1)).executeGraph(request);
  }

  @Test
  @DisplayName("POST /execute-graph with custom mermaid preserves graph (non-regression)")
  void testExecuteGraphWithCustomMermaid()
      throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException,
          com.chromascape.web.debug.patchloop.graph.GraphExecutionException {
    // Arrange
    GraphExecuteRequest request = new GraphExecuteRequest();
    request.setMermaid("graph TD\n  Analyze-->GeneratePatch\n  GeneratePatch-->Validate");
    request.setScriptName("TestScript");
    request.setGoalPrompt("Custom graph test");

    when(graphOrchestratorService.executeGraph(any()))
        .thenAnswer(
            invocation -> {
              GraphExecuteRequest req = invocation.getArgument(0);
              // Verify custom mermaid was passed through
              assertTrue(req.getMermaid().contains("Analyze-->GeneratePatch"));

              GraphExecuteResponse response = new GraphExecuteResponse();
              response.setExecutionId("graph_custom_789");
              response.setIterationId("patchloop_custom_789");
              response.setWsUrl("/ws/debug/patch-loop/graph_custom_789");
              response.setApiUrl("/api/debug/patch-loop/patchloop_custom_789/graph");
              return response;
            });

    // Act
    ResponseEntity<Object> response = controller.executeGraph(request);

    // Assert
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(graphOrchestratorService, times(1)).executeGraph(request);
  }

  private void setAutofixEnabled(boolean value) throws Exception {
    Field field = PatchLoopController.class.getDeclaredField("patchLoopAutofixEnabled");
    field.setAccessible(true);
    field.set(controller, value);
  }
}

package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.controller.PatchLoopController;
import com.chromascape.web.debug.patchloop.dto.PatchLoopEventResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteRequest;
import com.chromascape.web.debug.patchloop.dto.PatchLoopExecuteResponse;
import com.chromascape.web.debug.patchloop.dto.PatchLoopIterationResponse;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.service.PatchLoopGraphOrchestratorService;
import com.chromascape.web.debug.patchloop.service.PatchLoopOrchestratorService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

  private PatchLoopController controller;

  @BeforeEach
  void setUp() {
    controller = new PatchLoopController(orchestratorService, graphOrchestratorService);
  }

  @Test
  @DisplayName("POST /execute returns 200 OK with iteration ID")
  void testExecuteIterationReturns200() {
    // Arrange
    PatchLoopExecuteRequest request = new PatchLoopExecuteRequest();
    request.setScriptName("TestScript");
    request.setPrompt("Why failed?");

    PatchLoopExecuteResponse serviceResponse =
        new PatchLoopExecuteResponse("iter-123", "QUEUED", "Started");

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
  void testCompileGraphReturns200() throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException {
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
  void testCompileGraphReturns400ForInvalidMermaid() throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException {
    // Arrange
    com.chromascape.web.debug.patchloop.dto.CompileRequest request =
        new com.chromascape.web.debug.patchloop.dto.CompileRequest();
    request.setMermaid("invalid mermaid syntax");

    when(graphOrchestratorService.compileGraph(anyString()))
        .thenThrow(new com.chromascape.web.debug.patchloop.graph.GraphCompilationException("Invalid syntax"));

    // Act
    ResponseEntity<Object> response = controller.compileGraph(request);

    // Assert
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody().toString().contains("Graph compilation failed"));
  }

  @Test
  @DisplayName("POST /compile returns 500 on unexpected exception")
  void testCompileGraphReturns500OnException() throws com.chromascape.web.debug.patchloop.graph.GraphCompilationException {
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

  // ========== Helper Methods ==========

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

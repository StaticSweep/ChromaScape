package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.patchloop.dto.AnalysisStatus;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Error;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEventRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopFailureCode;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.graph.PatchLoopGraphExecutor;
import com.chromascape.web.debug.patchloop.service.PatchLoopAsyncWorker;
import com.chromascape.web.debug.patchloop.service.PatchLoopEvidenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopPersistenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopSessionResolverService;
import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import com.chromascape.web.debug.patchloop.websocket.PatchLoopWebSocketHandler;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.instance.ScriptControl;
import com.chromascape.web.instance.ScriptInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Test suite for PatchLoopAsyncWorker.
 *
 * <p>Note: These tests verify the worker's orchestration logic. Detailed persistence behavior is
 * tested in {@link PatchLoopPersistenceServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class PatchLoopAsyncWorkerTest {

  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private PatchLoopEventRepository eventRepository;
  @Mock private PatchLoopEvidenceService evidenceService;
  @Mock private AnalysisProvider analysisProvider;
  @Mock private DebugSessionRepository debugSessionRepository;
  @Mock private ScriptControl scriptControl;
  @Mock private PatchLoopPersistenceService persistenceService;
  @Mock private PatchLoopGraphExecutor graphExecutor;
  @Mock private PatchLoopWebSocketHandler webSocketHandler;
  @Mock private PatchLoopSessionResolverService sessionResolverService;

  private PatchLoopAsyncWorker asyncWorker;
  private ObjectMapper objectMapper;
  private ScriptInstance mockScriptInstance;

  /**
   * Test subclass that provides a mock ScriptInstance.
   *
   * <p>This allows tests to bypass the static ScriptInstanceManager singleton and provide a
   * controllable mock instance for testing the full analysis flow.
   */
  private class TestableAsyncWorker extends PatchLoopAsyncWorker {
    public TestableAsyncWorker(
        PatchLoopIterationRepository iterationRepository,
        PatchLoopEventRepository eventRepository,
        PatchLoopEvidenceService evidenceService,
        AnalysisProvider analysisProvider,
        DebugSessionRepository debugSessionRepository,
        ScriptControl scriptControl,
        ObjectMapper objectMapper,
        PatchLoopPersistenceService persistenceService,
        PatchLoopGraphExecutor graphExecutor,
        PatchLoopWebSocketHandler webSocketHandler,
        PatchLoopSessionResolverService sessionResolverService) {
      super(
          iterationRepository,
          eventRepository,
          evidenceService,
          analysisProvider,
          debugSessionRepository,
          scriptControl,
          objectMapper,
          persistenceService,
          graphExecutor,
          webSocketHandler,
          sessionResolverService);
    }

    @Override
    protected ScriptInstance getScriptInstance() {
      return mockScriptInstance;
    }
  }

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();

    // Setup mock provider to return a name
    lenient().when(analysisProvider.getProviderName()).thenReturn("mock-provider");

    // Setup evidence validation to pass by default (lenient for tests that don't reach this point)
    lenient().when(evidenceService.validateEvidenceSufficiency(any())).thenReturn(true);
    lenient().when(sessionResolverService.resolveForRetest(any())).thenReturn(Optional.empty());

    // Setup script control mocks for successful run (lenient for tests that don't reach this point)
    lenient()
        .when(scriptControl.getRunConfig(any()))
        .thenReturn(org.springframework.http.ResponseEntity.ok().build());

    // Create mock ScriptInstance that reports as not running (completed successfully)
    mockScriptInstance = org.mockito.Mockito.mock(ScriptInstance.class);
    lenient().when(mockScriptInstance.isRunning()).thenReturn(false);

    asyncWorker =
        new TestableAsyncWorker(
            iterationRepository,
            eventRepository,
            evidenceService,
            analysisProvider,
            debugSessionRepository,
            scriptControl,
            objectMapper,
            persistenceService,
            graphExecutor,
            webSocketHandler,
            sessionResolverService);
  }

  /**
   * Sets up a deterministic flow that allows tests to reach the analysis stage.
   *
   * <p>This helper configures:
   *
   * <ul>
   *   <li>Script run succeeds
   *   <li>Evidence collection returns valid bundle
   *   <li>Evidence validation passes
   * </ul>
   *
   * @param iteration the iteration being tested
   */
  private void setupAnalysisPath(PatchLoopIteration iteration) {
    // 1. Iteration lookup succeeds
    when(iterationRepository.findByIterationId(iteration.getIterationId()))
        .thenReturn(Optional.of(iteration));

    // 2. Script run succeeds - mock debug session
    DebugSession session = new DebugSession();
    session.setSessionId("test-session-id");
    session.setScriptName(iteration.getScriptName());
    session.setFailureCode(null); // Indicates success
    when(debugSessionRepository.findFirstByScriptNameOrderByStartTimeDesc(
            iteration.getScriptName()))
        .thenReturn(Optional.of(session));

    // 3. Evidence collection returns valid bundle
    AnalysisV1EvidenceBundle evidence = new AnalysisV1EvidenceBundle();
    evidence.setScriptName(iteration.getScriptName());
    // Initialize empty lists (constructor does this, but being explicit for clarity)
    evidence.setLogs(new java.util.ArrayList<>());
    evidence.setTickLogs(new java.util.ArrayList<>());
    evidence.setDecisions(new java.util.ArrayList<>());
    when(evidenceService.collectEvidence(eq(iteration), any())).thenReturn(evidence);

    // 4. Evidence validation passes (already set in setUp, but re-stating for strict mocking)
    when(evidenceService.validateEvidenceSufficiency(evidence)).thenReturn(true);

    // 5. Script control returns success
    when(scriptControl.getRunConfig(any()))
        .thenReturn(org.springframework.http.ResponseEntity.ok().build());
  }

  @Test
  @DisplayName(
      "Worker successfully constructed with all dependencies including persistence service")
  void testWorkerConstruction() {
    assertNotNull(asyncWorker, "Worker should be constructed successfully");
  }

  // ========== Failure Code Mapping Tests (Behavioral) ==========

  @Test
  @DisplayName("API_TIMEOUT provider code maps to ANALYSIS_API_TIMEOUT")
  void testApiTimeoutProviderCodeMapsToAnalysisApiTimeout() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse =
        createFailureResponse("API_TIMEOUT", "API timeout occurred");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), reasonCaptor.capture(), any());

    assertEquals(PatchLoopFailureCode.ANALYSIS_API_TIMEOUT, codeCaptor.getValue());
    assertTrue(
        reasonCaptor.getValue().contains("API timeout occurred"),
        "Failure reason should contain provider message");

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("API_RATE_LIMIT provider code maps to ANALYSIS_API_RATE_LIMIT")
  void testApiRateLimitProviderCodeMapsToAnalysisApiRateLimit() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse =
        createFailureResponse("API_RATE_LIMIT", "Rate limit exceeded");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.ANALYSIS_API_RATE_LIMIT, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("API_AUTH_FAILED provider code maps to ANALYSIS_API_AUTH_FAILED")
  void testApiAuthFailedProviderCodeMapsToAnalysisApiAuthFailed() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse =
        createFailureResponse("API_AUTH_FAILED", "Authentication failed");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.ANALYSIS_API_AUTH_FAILED, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("PARSE_ERROR provider code maps to ANALYSIS_PARSE_ERROR")
  void testParseErrorProviderCodeMapsToAnalysisParseError() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse =
        createFailureResponse("PARSE_ERROR", "Failed to parse response");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.ANALYSIS_PARSE_ERROR, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("INVALID_REQUEST provider code maps to ANALYSIS_VALIDATION_FAILED")
  void testInvalidRequestProviderCodeMapsToAnalysisValidationFailed() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse =
        createFailureResponse("INVALID_REQUEST", "Request validation failed");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.ANALYSIS_VALIDATION_FAILED, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("INSUFFICIENT_EVIDENCE provider code maps to INSUFFICIENT_EVIDENCE")
  void testInsufficientEvidenceProviderCodeMapsToInsufficientEvidence() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse =
        createFailureResponse("INSUFFICIENT_EVIDENCE", "Not enough evidence");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.INSUFFICIENT_EVIDENCE, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("INVALID_TIME_WINDOW provider code maps to INVALID_TIME_WINDOW")
  void testInvalidTimeWindowProviderCodeMapsToInvalidTimeWindow() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse =
        createFailureResponse("INVALID_TIME_WINDOW", "Invalid time window");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.INVALID_TIME_WINDOW, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("INTERNAL_ERROR provider code maps to INTERNAL_ERROR")
  void testInternalErrorProviderCodeMapsToInternalError() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse =
        createFailureResponse("INTERNAL_ERROR", "Internal server error");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.INTERNAL_ERROR, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("Unknown provider code maps to ANALYSIS_API_ERROR")
  void testUnknownProviderCodeMapsToAnalysisApiError() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse =
        createFailureResponse("UNKNOWN_ERROR", "Unknown error occurred");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.ANALYSIS_API_ERROR, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("Null provider code maps to ANALYSIS_API_ERROR")
  void testNullProviderCodeMapsToAnalysisApiError() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse = createFailureResponse(null, "Error occurred");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.ANALYSIS_API_ERROR, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("Empty provider code maps to ANALYSIS_API_ERROR")
  void testEmptyProviderCodeMapsToAnalysisApiError() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse = createFailureResponse("", "Error occurred");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.ANALYSIS_API_ERROR, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("Null error object maps to ANALYSIS_API_ERROR")
  void testNullErrorObjectMapsToAnalysisApiError() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse = new AnalysisV2Response();
    failureResponse.setStatus(AnalysisStatus.FAILURE);
    failureResponse.setSchemaVersion("2.0");
    failureResponse.setError(null);

    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify correct failure code mapping
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    verify(persistenceService)
        .markIterationFailed(eq(iteration), codeCaptor.capture(), anyString(), any());

    assertEquals(PatchLoopFailureCode.ANALYSIS_API_ERROR, codeCaptor.getValue());

    // Assert - Verify no success transition
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName(
      "Failed analysis with FAILURE status does not transition to COMPLETED (terminal guard)")
  void testFailedAnalysisDoesNotSetCompletedStatus() throws Exception {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupAnalysisPath(iteration);

    AnalysisV2Response failureResponse =
        createFailureResponse("API_TIMEOUT", "API timeout occurred");
    when(analysisProvider.analyzeEvidence(any())).thenReturn(failureResponse);

    // Act
    asyncWorker.processIterationAsync("test-id");

    // Assert - Verify analysis was called and iteration was marked as failed
    verify(analysisProvider).analyzeEvidence(any());

    // Assert - Verify terminal state was set (FAILED) and never transitioned to COMPLETED
    ArgumentCaptor<PatchLoopStatus> statusCaptor = ArgumentCaptor.forClass(PatchLoopStatus.class);
    verify(persistenceService).updateIterationStatus(eq(iteration), statusCaptor.capture());

    // The last status update should be FAILED, not COMPLETED
    // (terminal guard prevents COMPLETED transition after failure)
    java.util.List<PatchLoopStatus> capturedStatuses = statusCaptor.getAllValues();
    assertTrue(
        capturedStatuses.contains(PatchLoopStatus.FAILED),
        "FAILED status should be set when analysis fails");

    // Assert - Verify COMPLETED was never called after terminal state
    verify(persistenceService, never()).updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
  }

  // ========== Helper Methods ==========

  /**
   * Creates a test iteration for use in tests.
   *
   * @return a configured PatchLoopIteration
   */
  private PatchLoopIteration createTestIteration() {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("test-id");
    iteration.setScriptName("TestScript");
    iteration.setPrompt("Fix the bug");
    iteration.setContext("Test context");
    iteration.setStatus(PatchLoopStatus.ANALYZING);
    iteration.setCreatedAt(LocalDateTime.now());
    return iteration;
  }

  /**
   * Creates a failure response with the given error code and message.
   *
   * @param code the provider error code
   * @param message the error message
   * @return a configured AnalysisV2Response with FAILURE status
   */
  private AnalysisV2Response createFailureResponse(String code, String message) {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.FAILURE);
    response.setSchemaVersion("2.0");

    AnalysisV1Error error = new AnalysisV1Error();
    error.setCode(code);
    error.setMessage(message);
    response.setError(error);

    return response;
  }
}

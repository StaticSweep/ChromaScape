package com.chromascape.web.debug.patchloop.service.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.constants.AnalysisV2ErrorCode;
import com.chromascape.web.debug.patchloop.dto.AnalysisStatus;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Diagnosis;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2EvidenceSelector;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2PatchDraft;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2RetestPlan;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Target;
import com.chromascape.web.debug.patchloop.dto.ConfidenceLevel;
import com.chromascape.web.debug.patchloop.dto.EvidenceType;
import com.chromascape.web.debug.patchloop.dto.PromptType;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpAnalyzeRequest;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpAnalyzeResponse;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpConfigProperties;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpHealthResponse;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportClient;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Test suite for {@link ClaudeMcpAnalysisProvider}.
 *
 * <p>Verifies that the MCP provider correctly:
 *
 * <ul>
 *   <li>Builds MCP requests from V2 requests
 *   <li>Calls the transport with proper configuration
 *   <li>Maps transport errors to V2 error codes
 *   <li>Reports availability based on health check
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ClaudeMcpAnalysisProviderTest {

  @Mock private McpTransportClient transport;

  @Mock private McpConfigProperties config;

  private ClaudeMcpAnalysisProvider provider;

  @BeforeEach
  void setUp() {
    // Setup default config mock behavior
    lenient().when(config.getServerUrl()).thenReturn("http://localhost:8765");
    lenient().when(config.getTimeoutSeconds()).thenReturn(120);
    lenient().when(config.getMaxRetries()).thenReturn(3);

    // Create provider with mocked dependencies
    provider = new ClaudeMcpAnalysisProvider(transport, config);
  }

  @Test
  @DisplayName("getProviderName() returns 'claude-mcp'")
  void testGetProviderName() {
    assertEquals("claude-mcp", provider.getProviderName(), "Provider name should be 'claude-mcp'");
  }

  @Test
  @DisplayName("isAvailable() returns true when MCP server is healthy")
  void testIsAvailableReturnsTrueWhenServerHealthy() throws Exception {
    // Arrange: transport returns healthy response
    McpHealthResponse healthyResponse = new McpHealthResponse(true, "1.0.0", null);
    when(transport.healthCheck()).thenReturn(healthyResponse);

    // Act
    boolean result = provider.isAvailable();

    // Assert
    assertTrue(result, "Provider should be available when MCP server is healthy");
    verify(transport).healthCheck();
  }

  @Test
  @DisplayName("isAvailable() returns false when MCP server is unhealthy")
  void testIsAvailableReturnsFalseWhenServerUnhealthy() throws Exception {
    // Arrange: transport returns unhealthy response
    McpHealthResponse unhealthyResponse = new McpHealthResponse(false, null, null);
    when(transport.healthCheck()).thenReturn(unhealthyResponse);

    // Act
    boolean result = provider.isAvailable();

    // Assert
    assertFalse(result, "Provider should be unavailable when MCP server is unhealthy");
    verify(transport).healthCheck();
  }

  @Test
  @DisplayName("isAvailable() returns false when transport throws CONNECTION_REFUSED")
  void testIsAvailableReturnsFalseOnConnectionRefused() throws Exception {
    // Arrange: transport throws connection refused exception
    McpTransportException connectionRefused =
        new McpTransportException(
            McpTransportException.McpErrorType.CONNECTION_REFUSED,
            "Cannot connect to MCP server",
            false);
    when(transport.healthCheck()).thenThrow(connectionRefused);

    // Act
    boolean result = provider.isAvailable();

    // Assert
    assertFalse(result, "Provider should be unavailable when MCP server connection is refused");
    verify(transport).healthCheck();
  }

  @Test
  @DisplayName("isAvailable() returns false when transport throws TIMEOUT")
  void testIsAvailableReturnsFalseOnTimeout() throws Exception {
    // Arrange: transport throws timeout exception
    McpTransportException timeoutException =
        new McpTransportException(
            McpTransportException.McpErrorType.TIMEOUT, "MCP server request timed out", true);
    when(transport.healthCheck()).thenThrow(timeoutException);

    // Act
    boolean result = provider.isAvailable();

    // Assert
    assertFalse(result, "Provider should be unavailable when MCP server times out");
    verify(transport).healthCheck();
  }

  @Test
  @DisplayName("isAvailable() returns false when transport throws HTTP_ERROR")
  void testIsAvailableReturnsFalseOnHttpError() throws Exception {
    // Arrange: transport throws HTTP error exception
    McpTransportException httpException =
        new McpTransportException(
            McpTransportException.McpErrorType.HTTP_ERROR, "MCP server returned 500", true);
    when(transport.healthCheck()).thenThrow(httpException);

    // Act
    boolean result = provider.isAvailable();

    // Assert
    assertFalse(result, "Provider should be unavailable on HTTP errors");
    verify(transport).healthCheck();
  }

  @Test
  @DisplayName("analyzeEvidence() returns V2 response on successful analysis")
  void testAnalyzeEvidenceReturnsValidResponse() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    AnalysisV2Response v2Response = createValidV2SuccessResponse();

    McpAnalyzeResponse mcpResponse = new McpAnalyzeResponse(v2Response, null, null);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenReturn(mcpResponse);

    // Act
    AnalysisV2Response result = provider.analyzeEvidence(v2Request);

    // Assert
    assertNotNull(result, "Should return non-null response");
    assertEquals("test-analysis-123", result.getAnalysisId());
    assertEquals("2.0.0", result.getSchemaVersion());

    // Verify the transport was called with proper request structure
    ArgumentCaptor<McpAnalyzeRequest> requestCaptor =
        ArgumentCaptor.forClass(McpAnalyzeRequest.class);
    verify(transport).analyze(requestCaptor.capture());

    McpAnalyzeRequest capturedRequest = requestCaptor.getValue();
    assertEquals(v2Request, capturedRequest.getAnalysisRequest());
    assertNotNull(capturedRequest.getOptions());
    assertEquals(120, capturedRequest.getOptions().getTimeoutSeconds());
  }

  @Test
  @DisplayName("analyzeEvidence() maps TIMEOUT to API_TIMEOUT")
  void testAnalyzeEvidenceMapsTimeoutToApiTimeout() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    McpTransportException timeoutException =
        new McpTransportException(
            McpTransportException.McpErrorType.TIMEOUT, "Request timed out", true);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenThrow(timeoutException);

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.API_TIMEOUT, exception.getErrorCode());
    assertTrue(exception.getMessage().contains("MCP analysis failed"));
  }

  @Test
  @DisplayName("analyzeEvidence() maps AUTH_FAILED to API_AUTH_FAILED")
  void testAnalyzeEvidenceMapsAuthFailedToApiAuthFailed() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    McpTransportException authException =
        new McpTransportException(
            McpTransportException.McpErrorType.AUTH_FAILED, "Authentication failed", false);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenThrow(authException);

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.API_AUTH_FAILED, exception.getErrorCode());
  }

  @Test
  @DisplayName("analyzeEvidence() maps HTTP_ERROR to API_ERROR")
  void testAnalyzeEvidenceMapsHttpErrorToApiError() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    McpTransportException httpException =
        new McpTransportException(
            McpTransportException.McpErrorType.HTTP_ERROR, "HTTP 500 error", true);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenThrow(httpException);

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.API_ERROR, exception.getErrorCode());
  }

  @Test
  @DisplayName("analyzeEvidence() maps INVALID_RESPONSE to PARSE_ERROR")
  void testAnalyzeEvidenceMapsInvalidResponseToParseError() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    McpTransportException invalidResponse =
        new McpTransportException(
            McpTransportException.McpErrorType.INVALID_RESPONSE, "Malformed JSON", false);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenThrow(invalidResponse);

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.PARSE_ERROR, exception.getErrorCode());
  }

  @Test
  @DisplayName("analyzeEvidence() maps SERIALIZATION_ERROR to PARSE_ERROR")
  void testAnalyzeEvidenceMapsSerializationErrorToParseError() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    McpTransportException serializationError =
        new McpTransportException(
            McpTransportException.McpErrorType.SERIALIZATION_ERROR,
            "Failed to serialize request",
            false);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenThrow(serializationError);

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.PARSE_ERROR, exception.getErrorCode());
  }

  @Test
  @DisplayName("analyzeEvidence() maps CONFIGURATION_ERROR to INVALID_REQUEST")
  void testAnalyzeEvidenceMapsConfigurationErrorToInvalidRequest() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    McpTransportException configError =
        new McpTransportException(
            McpTransportException.McpErrorType.CONFIGURATION_ERROR, "Invalid configuration", false);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenThrow(configError);

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.INVALID_REQUEST, exception.getErrorCode());
  }

  @Test
  @DisplayName("analyzeEvidence() maps CONNECTION_REFUSED to API_ERROR")
  void testAnalyzeEvidenceMapsConnectionRefusedToApiError() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    McpTransportException connectionRefused =
        new McpTransportException(
            McpTransportException.McpErrorType.CONNECTION_REFUSED, "Connection refused", false);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenThrow(connectionRefused);

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.API_ERROR, exception.getErrorCode());
  }

  @Test
  @DisplayName("analyzeEvidence() maps UNKNOWN to INTERNAL_ERROR")
  void testAnalyzeEvidenceMapsUnknownToInternalError() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    McpTransportException unknown =
        new McpTransportException(
            McpTransportException.McpErrorType.UNKNOWN, "Unknown error", false);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenThrow(unknown);

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.INTERNAL_ERROR, exception.getErrorCode());
  }

  @Test
  @DisplayName("analyzeEvidence() throws INVALID_RESPONSE_SCHEMA when response is null")
  void testAnalyzeEvidenceThrowsOnNullAnalysisResponse() throws Exception {
    // Arrange: MCP response with null analysisResponse
    McpAnalyzeResponse mcpResponse = new McpAnalyzeResponse(null, null, null);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenReturn(mcpResponse);

    AnalysisV2Request v2Request = createMinimalV2Request();

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.INVALID_RESPONSE_SCHEMA, exception.getErrorCode());
    assertTrue(exception.getMessage().contains("null analysisResponse"));
  }

  @Test
  @DisplayName("analyzeEvidence() throws API_ERROR when response contains error field")
  void testAnalyzeEvidenceThrowsOnErrorInResponse() throws Exception {
    // Arrange: MCP response with error field populated
    McpAnalyzeResponse mcpResponse =
        new McpAnalyzeResponse(null, null, "MCP server internal error");
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenReturn(mcpResponse);

    AnalysisV2Request v2Request = createMinimalV2Request();

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.API_ERROR, exception.getErrorCode());
    assertTrue(exception.getMessage().contains("MCP server error: MCP server internal error"));
  }

  @Test
  @DisplayName("analyzeEvidence() includes timeout in request options")
  void testAnalyzeEvidenceIncludesTimeoutInOptions() throws Exception {
    // Arrange
    when(config.getTimeoutSeconds()).thenReturn(60);

    AnalysisV2Request v2Request = createMinimalV2Request();
    AnalysisV2Response v2Response = createValidV2SuccessResponse();
    McpAnalyzeResponse mcpResponse = new McpAnalyzeResponse(v2Response, null, null);

    when(transport.analyze(any(McpAnalyzeRequest.class))).thenReturn(mcpResponse);

    // Recreate provider with updated config
    provider = new ClaudeMcpAnalysisProvider(transport, config);

    // Act
    provider.analyzeEvidence(v2Request);

    // Assert: verify timeout was included in request options
    ArgumentCaptor<McpAnalyzeRequest> requestCaptor =
        ArgumentCaptor.forClass(McpAnalyzeRequest.class);
    verify(transport).analyze(requestCaptor.capture());

    McpAnalyzeRequest capturedRequest = requestCaptor.getValue();
    assertEquals(60, capturedRequest.getOptions().getTimeoutSeconds());
  }

  @Test
  @DisplayName("analyzeEvidence() preserves V2 request in MCP wrapper")
  void testAnalyzeEvidencePreservesV2Request() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    v2Request.setPrompt("Test custom prompt");
    v2Request.setPromptType(PromptType.FEATURE);

    AnalysisV2Response v2Response = createValidV2SuccessResponse();
    McpAnalyzeResponse mcpResponse = new McpAnalyzeResponse(v2Response, null, null);

    when(transport.analyze(any(McpAnalyzeRequest.class))).thenReturn(mcpResponse);

    // Act
    provider.analyzeEvidence(v2Request);

    // Assert: verify V2 request was preserved
    ArgumentCaptor<McpAnalyzeRequest> requestCaptor =
        ArgumentCaptor.forClass(McpAnalyzeRequest.class);
    verify(transport).analyze(requestCaptor.capture());

    McpAnalyzeRequest capturedRequest = requestCaptor.getValue();
    assertEquals(v2Request, capturedRequest.getAnalysisRequest());
    assertEquals("Test custom prompt", capturedRequest.getAnalysisRequest().getPrompt());
    assertEquals(PromptType.FEATURE, capturedRequest.getAnalysisRequest().getPromptType());
  }

  @Test
  @DisplayName("analyzeEvidence() validates request before calling transport")
  void testAnalyzeEvidenceValidatesRequest() throws McpTransportException {
    // Arrange: Create invalid request (missing required fields)
    AnalysisV2Request invalidRequest = new AnalysisV2Request();
    // Missing: schemaVersion, promptType, target, prompt, evidenceSelectors

    // Act & Assert
    assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(invalidRequest));

    // Verify transport was never called (validation failed first)
    verify(transport, never()).analyze(any());
  }

  @Test
  @DisplayName("analyzeEvidence() validates response from MCP server")
  void testAnalyzeEvidenceValidatesResponse() throws Exception {
    // Arrange: Create SUCCESS response missing required fields
    AnalysisV2Response incompleteResponse = new AnalysisV2Response();
    incompleteResponse.setSchemaVersion("2.0.0");
    incompleteResponse.setStatus(AnalysisStatus.SUCCESS);
    incompleteResponse.setDiagnosis(null); // Missing required field

    McpAnalyzeResponse mcpResponse = new McpAnalyzeResponse(incompleteResponse, null, null);
    when(transport.analyze(any())).thenReturn(mcpResponse);

    // Act & Assert
    assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(createValidV2Request()));
  }

  @Test
  @DisplayName("analyzeEvidence() maps RATE_LIMIT to API_RATE_LIMIT")
  void testAnalyzeEvidenceMapsRateLimitToApiRateLimit() throws Exception {
    // Arrange
    AnalysisV2Request v2Request = createMinimalV2Request();
    McpTransportException rateLimitException =
        new McpTransportException(
            McpTransportException.McpErrorType.RATE_LIMIT, "Rate limit exceeded", true);
    when(transport.analyze(any(McpAnalyzeRequest.class))).thenThrow(rateLimitException);

    // Act & Assert
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(v2Request));

    assertEquals(AnalysisV2ErrorCode.API_RATE_LIMIT, exception.getErrorCode());
  }

  // Helper methods

  private AnalysisV2Request createMinimalV2Request() {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setSchemaVersion("2.0.0");
    request.setPrompt("Test prompt");
    request.setPromptType(PromptType.ISSUE);
    request.setTarget(new AnalysisV2Target("test-script", null, null));
    request.setEvidenceSelectors(
        List.of(new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS)));
    return request;
  }

  private AnalysisV2Request createValidV2Request() {
    return createMinimalV2Request();
  }

  private AnalysisV2Response createValidV2SuccessResponse() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setSchemaVersion("2.0.0");
    response.setAnalysisId("test-analysis-123");
    response.setStatus(AnalysisStatus.SUCCESS);

    // Required for SUCCESS status
    response.setDiagnosis(createMockDiagnosis());
    response.setImpactedModules(List.of("ModuleA", "ModuleB"));
    response.setPatchDraft(createMockPatchDraft());
    response.setRetestPlan(createMockRetestPlan());
    response.setEvidenceReferences(List.of("log:123", "tick:456"));

    return response;
  }

  private AnalysisV2Diagnosis createMockDiagnosis() {
    AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
    diagnosis.setSummary("Test summary");
    diagnosis.setRootCause("Test root cause");
    diagnosis.setConfidence(ConfidenceLevel.HIGH);
    return diagnosis;
  }

  private AnalysisV2PatchDraft createMockPatchDraft() {
    AnalysisV2PatchDraft patch = new AnalysisV2PatchDraft();
    patch.setPatchId("test-patch-123");
    patch.setDescription("Test patch");
    patch.setAffectedFiles(List.of("com/example/Test.java"));
    patch.setChanges(
        List.of(
            new AnalysisV2PatchDraft.PatchChange("com/example/Test.java", "old code", "new code")));
    return patch;
  }

  private AnalysisV2RetestPlan createMockRetestPlan() {
    AnalysisV2RetestPlan plan = new AnalysisV2RetestPlan();
    plan.setTestSteps(
        List.of(
            new AnalysisV2RetestPlan.TestStep(
                1, "Run the script", AnalysisV2RetestPlan.ActionType.RUN_SCRIPT)));
    plan.setSuccessCriteria(List.of("Script runs successfully", "No errors in logs"));
    return plan;
  }
}

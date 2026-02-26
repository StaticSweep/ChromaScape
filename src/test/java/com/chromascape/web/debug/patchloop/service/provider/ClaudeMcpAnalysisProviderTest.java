package com.chromascape.web.debug.patchloop.service.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.constants.AnalysisV1ErrorCode;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Request;
import com.chromascape.web.debug.patchloop.dto.TimeWindow;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpConfigProperties;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpHealthResponse;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportClient;
import com.chromascape.web.debug.patchloop.service.provider.mcp.McpTransportException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Test suite for {@link ClaudeMcpAnalysisProvider}.
 *
 * <p>Verifies that the MCP provider correctly reports availability based on MCP server health check
 * and that analyzeEvidence() throws a clear error about pending implementation (#516).
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
  @DisplayName("isAvailable() returns false when transport throws INVALID_RESPONSE")
  void testIsAvailableReturnsFalseOnInvalidResponse() throws Exception {
    // Arrange: transport throws invalid response exception
    McpTransportException invalidResponse =
        new McpTransportException(
            McpTransportException.McpErrorType.INVALID_RESPONSE,
            "Failed to parse MCP health response",
            false);
    when(transport.healthCheck()).thenThrow(invalidResponse);

    // Act
    boolean result = provider.isAvailable();

    // Assert
    assertFalse(result, "Provider should be unavailable on invalid response");
    verify(transport).healthCheck();
  }

  @Test
  @DisplayName("analyzeEvidence() throws AnalysisException with INTERNAL_ERROR code")
  void testAnalyzeEvidenceThrowsException() throws Exception {
    // Arrange: Create minimal valid request
    AnalysisV1Request request = createMinimalRequest();

    // Act & Verify exception
    AnalysisException exception =
        assertThrows(
            AnalysisException.class,
            () -> provider.analyzeEvidence(request),
            "analyzeEvidence() should throw AnalysisException");

    assertEquals(
        AnalysisV1ErrorCode.INTERNAL_ERROR,
        exception.getErrorCode(),
        "Error code should be INTERNAL_ERROR");
  }

  @Test
  @DisplayName("analyzeEvidence() exception message clearly states pending implementation (#516)")
  void testAnalyzeEvidenceExceptionMessage() throws Exception {
    // Arrange: Create minimal valid request
    AnalysisV1Request request = createMinimalRequest();

    // Act & Verify exception message
    AnalysisException exception =
        assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(request));

    String message = exception.getMessage();
    assertNotNull(message, "Exception message should not be null");
    assertTrue(
        message.contains("pending implementation"),
        "Exception message should mention pending implementation");
    assertTrue(message.contains("516"), "Exception message should reference issue #516");
    assertTrue(
        message.contains("health check"),
        "Exception message should mention health check is available");
  }

  @Test
  @DisplayName("analyzeEvidence() fails immediately without attempting MCP call")
  void testAnalyzeEvidenceFailsFast() throws Exception {
    // Arrange: Create minimal valid request
    AnalysisV1Request request = createMinimalRequest();

    // Note: No stubbing needed since transport should never be called

    // Measure execution time to verify it fails fast (should be < 100ms)
    long startTime = System.currentTimeMillis();

    assertThrows(AnalysisException.class, () -> provider.analyzeEvidence(request));

    long executionTime = System.currentTimeMillis() - startTime;

    assertTrue(
        executionTime < 100,
        "Should fail fast without network calls (actual: " + executionTime + "ms)");
    verify(transport, never()).healthCheck();
  }

  @Test
  @DisplayName("analyzeEvidence() throws exception immediately without transport dependency")
  void testAnalyzeEvidenceDoesNotRequireTransport() {
    // Arrange: Create minimal valid request, null transport (simulating unavailable)
    ClaudeMcpAnalysisProvider providerWithNullTransport =
        new ClaudeMcpAnalysisProvider(null, config);
    AnalysisV1Request request = createMinimalRequest();

    // Act & Assert: Should still throw AnalysisException immediately
    assertThrows(
        AnalysisException.class,
        () -> providerWithNullTransport.analyzeEvidence(request),
        "Should throw AnalysisException even with null transport");
  }

  // Helper method to create minimal valid request for testing
  private AnalysisV1Request createMinimalRequest() {
    AnalysisV1Request request = new AnalysisV1Request();
    request.setPrompt("Test prompt");
    request.setContext("Test context");

    AnalysisV1EvidenceBundle evidence = new AnalysisV1EvidenceBundle();
    evidence.setScriptName("TestScript");

    TimeWindow timeWindow = new TimeWindow();
    timeWindow.setStart(LocalDateTime.now().minusMinutes(1));
    timeWindow.setEnd(LocalDateTime.now());
    evidence.setTimeWindow(timeWindow);

    evidence.setLogs(List.of());
    evidence.setTickLogs(List.of());
    evidence.setDecisions(List.of());

    request.setEvidence(evidence);

    return request;
  }
}

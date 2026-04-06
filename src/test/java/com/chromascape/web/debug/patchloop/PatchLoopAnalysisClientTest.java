package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.service.PatchLoopAnalysisClient;
import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import java.time.LocalDateTime;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Test suite for PatchLoopAnalysisClient (V2 contract). */
@ExtendWith(MockitoExtension.class)
class PatchLoopAnalysisClientTest {

  @Mock private AnalysisProvider mockAnalysisProvider;

  private PatchLoopAnalysisClient analysisClient;

  @BeforeEach
  void setUp() {
    analysisClient = new PatchLoopAnalysisClient(mockAnalysisProvider);
  }

  // ========== V2 Delegation Tests ==========

  @Test
  @DisplayName("analyzeEvidence delegates to provider")
  void testDelegatesToProvider() throws AnalysisException {
    // Arrange
    AnalysisV2Request request = createValidRequest();
    AnalysisV2Response expectedResponse = createMockResponse();

    when(mockAnalysisProvider.analyzeEvidence(request)).thenReturn(expectedResponse);

    // Act
    AnalysisV2Response actualResponse = analysisClient.analyze(request);

    // Assert
    assertEquals(expectedResponse, actualResponse);
    verify(mockAnalysisProvider).analyzeEvidence(request);
  }

  @Test
  @DisplayName("analyzeEvidence propagates AnalysisException")
  void testPropagatesAnalysisException() throws AnalysisException {
    // Arrange
    AnalysisV2Request request = createValidRequest();

    when(mockAnalysisProvider.analyzeEvidence(request))
        .thenThrow(new AnalysisException("Provider error", "API_ERROR"));

    // Act & Assert
    assertThrows(AnalysisException.class, () -> analysisClient.analyze(request));
  }

  @Test
  @DisplayName("getProviderName delegates to provider")
  void testGetProviderName() {
    // Arrange
    when(mockAnalysisProvider.getProviderName()).thenReturn("claude-mcp");

    // Act
    String providerName = analysisClient.getProviderName();

    // Assert
    assertEquals("claude-mcp", providerName);
  }

  @Test
  @DisplayName("isProviderAvailable delegates to provider")
  void testIsProviderAvailable() {
    // Arrange
    when(mockAnalysisProvider.isAvailable()).thenReturn(true);

    // Act
    boolean available = analysisClient.isProviderAvailable();

    // Assert
    assertTrue(available);
  }

  @Test
  @DisplayName("isProviderAvailable returns false when provider unavailable")
  void testIsProviderUnavailable() {
    // Arrange
    when(mockAnalysisProvider.isAvailable()).thenReturn(false);

    // Act
    boolean available = analysisClient.isProviderAvailable();

    // Assert
    assertFalse(available);
  }

  @Test
  @DisplayName("analyzeEvidence returns response with SUCCESS status")
  void testSuccessResponse() throws AnalysisException {
    // Arrange
    AnalysisV2Request request = createValidRequest();
    AnalysisV2Response response = createMockResponse();
    response.setStatus(AnalysisStatus.SUCCESS);

    when(mockAnalysisProvider.analyzeEvidence(request)).thenReturn(response);

    // Act
    AnalysisV2Response actualResponse = analysisClient.analyze(request);

    // Assert
    assertEquals(AnalysisStatus.SUCCESS, actualResponse.getStatus());
    assertNotNull(actualResponse.getDiagnosis());
    assertEquals("Test diagnosis", actualResponse.getDiagnosis().getSummary());
  }

  @Test
  @DisplayName("analyzeEvidence returns response with PARTIAL_SUCCESS status")
  void testPartialSuccessResponse() throws AnalysisException {
    // Arrange
    AnalysisV2Request request = createValidRequest();
    AnalysisV2Response response = createMockResponse();
    response.setStatus(AnalysisStatus.PARTIAL_SUCCESS);

    when(mockAnalysisProvider.analyzeEvidence(request)).thenReturn(response);

    // Act
    AnalysisV2Response actualResponse = analysisClient.analyze(request);

    // Assert
    assertEquals(AnalysisStatus.PARTIAL_SUCCESS, actualResponse.getStatus());
    assertNotNull(actualResponse.getDiagnosis());
  }

  @Test
  @DisplayName("analyzeEvidence returns response with FAILURE status")
  void testFailureResponse() throws AnalysisException {
    // Arrange
    AnalysisV2Request request = createValidRequest();
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.FAILURE);
    response.setError(new AnalysisV1Error("API_TIMEOUT", "Request timed out"));

    when(mockAnalysisProvider.analyzeEvidence(request)).thenReturn(response);

    // Act
    AnalysisV2Response actualResponse = analysisClient.analyze(request);

    // Assert
    assertEquals(AnalysisStatus.FAILURE, actualResponse.getStatus());
    assertNotNull(actualResponse.getError());
    assertEquals("API_TIMEOUT", actualResponse.getError().getCode());
  }

  // ========== Helper Methods ==========

  /**
   * Creates a valid V2 analysis request for testing.
   *
   * @return a valid AnalysisV2Request
   */
  private AnalysisV2Request createValidRequest() {
    AnalysisV2Request request = new AnalysisV2Request();

    request.setPromptType(PromptType.ISSUE);

    AnalysisV2Target target = new AnalysisV2Target();
    target.setScriptName("TestScript");

    AnalysisV2Target.SessionBounds bounds = new AnalysisV2Target.SessionBounds();
    LocalDateTime now = LocalDateTime.now();
    bounds.setStart(now.minusMinutes(5));
    bounds.setEnd(now);
    target.setSessionBounds(bounds);

    request.setTarget(target);
    request.setPrompt("Why did the bot fail?");
    request.setContext("Testing V2 contract");

    AnalysisV2EvidenceSelector logSelector =
        new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS);
    logSelector.setMaxCount(100);
    logSelector.setTimeWindow(new TimeWindow(bounds.getStart(), bounds.getEnd()));

    request.setEvidenceSelectors(Collections.singletonList(logSelector));

    return request;
  }

  /**
   * Creates a mock V2 analysis response with SUCCESS status.
   *
   * @return a mock AnalysisV2Response
   */
  private AnalysisV2Response createMockResponse() {
    AnalysisV2Response response = new AnalysisV2Response();
    response.setStatus(AnalysisStatus.SUCCESS);
    response.setAnalysisId("test-analysis-id");

    AnalysisV2Diagnosis diagnosis = new AnalysisV2Diagnosis();
    diagnosis.setSummary("Test diagnosis");
    diagnosis.setRootCause("Test root cause");
    diagnosis.setConfidence(ConfidenceLevel.HIGH);
    diagnosis.setRecommendations(Collections.emptyList());

    response.setDiagnosis(diagnosis);
    response.setImpactedModules(Collections.singletonList("TestScript"));
    response.setEvidenceReferences(Collections.singletonList("log-entry-1"));

    return response;
  }
}

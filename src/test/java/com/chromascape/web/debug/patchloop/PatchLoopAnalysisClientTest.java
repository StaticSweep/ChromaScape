package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.constants.AnalysisV1ErrorCode;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Request;
import com.chromascape.web.debug.patchloop.dto.TimeWindow;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.service.PatchLoopAnalysisClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Test suite for PatchLoopAnalysisClient. */
@ExtendWith(MockitoExtension.class)
class PatchLoopAnalysisClientTest {

  @Mock private HttpClient mockHttpClient;

  private PatchLoopAnalysisClient analysisClient;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper = new ObjectMapper();
    analysisClient =
        new PatchLoopAnalysisClient(
            objectMapper,
            "test-api-key",
            "https://api.anthropic.com/v1/messages",
            "claude-opus-4-6",
            120,
            4096);

    // Use reflection to inject mock HttpClient
    Field httpClientField = PatchLoopAnalysisClient.class.getDeclaredField("httpClient");
    httpClientField.setAccessible(true);
    httpClientField.set(analysisClient, mockHttpClient);
  }

  @Test
  @DisplayName("Fix #4: InterruptedException restores interrupt flag before throwing")
  void testInterruptedExceptionRestoresInterruptFlag() throws Exception {
    // Arrange
    AnalysisV1Request request = createValidRequest();

    // Mock HttpClient to throw InterruptedException
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new InterruptedException("Request interrupted"));

    // Clear interrupt flag before test
    Thread.interrupted();

    // Act & Assert
    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> analysisClient.analyzeEvidence(request));

    // Verify exception details
    assertEquals(AnalysisV1ErrorCode.API_ERROR, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("interrupted"), "Error message should mention interrupt");

    // Verify interrupt flag was restored
    assertTrue(Thread.interrupted(), "Thread interrupt flag should have been restored");
  }

  @Test
  @DisplayName("Fix #4: InterruptedException throws AnalysisException with API_ERROR code")
  void testInterruptedExceptionThrowsAnalysisException() throws Exception {
    // Arrange
    AnalysisV1Request request = createValidRequest();

    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new InterruptedException("Interrupted during send"));

    // Clear interrupt flag
    Thread.interrupted();

    // Act & Assert
    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> analysisClient.analyzeEvidence(request));

    assertEquals(AnalysisV1ErrorCode.API_ERROR, ex.getErrorCode());
    assertNotNull(ex.getCause());
    assertTrue(ex.getCause() instanceof InterruptedException);

    // Clean up interrupt flag
    Thread.interrupted();
  }

  @Test
  @DisplayName("IOException does not affect interrupt flag")
  void testIOExceptionDoesNotAffectInterruptFlag() throws Exception {
    // Arrange
    AnalysisV1Request request = createValidRequest();

    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("Network error"));

    // Clear interrupt flag
    Thread.interrupted();

    // Act & Assert
    AnalysisException ex =
        assertThrows(AnalysisException.class, () -> analysisClient.analyzeEvidence(request));

    assertEquals(AnalysisV1ErrorCode.API_ERROR, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("failed"), "Error message should indicate failure");

    // Verify interrupt flag was NOT set (IOException doesn't set it)
    assertFalse(Thread.interrupted(), "Thread interrupt flag should NOT be set for IOException");
  }

  @Test
  @DisplayName("Separate catch blocks for InterruptedException and IOException")
  void testSeparateCatchBlocksExist() throws Exception {
    // This test verifies the fix by checking behavior difference

    AnalysisV1Request request = createValidRequest();

    // Test InterruptedException path
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new InterruptedException("Interrupted"));

    Thread.interrupted(); // Clear flag
    assertThrows(AnalysisException.class, () -> analysisClient.analyzeEvidence(request));
    assertTrue(
        Thread.interrupted(),
        "InterruptedException path should restore interrupt flag"); // Clears it again

    // Test IOException path
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("IO error"));

    assertThrows(AnalysisException.class, () -> analysisClient.analyzeEvidence(request));
    assertFalse(Thread.interrupted(), "IOException path should NOT set interrupt flag");
  }

  // ========== Helper Methods ==========

  private AnalysisV1Request createValidRequest() {
    AnalysisV1Request request = new AnalysisV1Request();
    request.setPrompt("Why did the bot fail?");

    AnalysisV1EvidenceBundle evidence = new AnalysisV1EvidenceBundle();
    evidence.setScriptName("TestScript");
    LocalDateTime now = LocalDateTime.now();
    evidence.setTimeWindow(new TimeWindow(now.minusMinutes(5), now));
    evidence.setLogs(
        Collections.singletonList(
            new AnalysisV1EvidenceBundle.LogEvidence(now, "ERROR", "Test error", "TestScript")));

    request.setEvidence(evidence);
    return request;
  }
}

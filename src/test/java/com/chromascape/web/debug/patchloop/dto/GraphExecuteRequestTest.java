package com.chromascape.web.debug.patchloop.dto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Test suite for GraphExecuteRequest. */
class GraphExecuteRequestTest {

  @Test
  @DisplayName("getNormalizedExecutionMode returns autofix_loop for full_loop legacy value")
  void testNormalizeFullLoopToAutofixLoop() {
    // Arrange
    GraphExecuteRequest request = new GraphExecuteRequest();
    request.setExecutionMode("full_loop");

    // Act
    String normalized = request.getNormalizedExecutionMode();

    // Assert
    assertEquals("autofix_loop", normalized);
  }

  @Test
  @DisplayName("getNormalizedExecutionMode returns analysis_only for null value")
  void testNormalizeNullToAnalysisOnly() {
    // Arrange
    GraphExecuteRequest request = new GraphExecuteRequest();
    request.setExecutionMode(null);

    // Act
    String normalized = request.getNormalizedExecutionMode();

    // Assert
    assertEquals("analysis_only", normalized);
  }

  @Test
  @DisplayName("getNormalizedExecutionMode preserves autofix_loop value")
  void testNormalizePreservesAutofixLoop() {
    // Arrange
    GraphExecuteRequest request = new GraphExecuteRequest();
    request.setExecutionMode("autofix_loop");

    // Act
    String normalized = request.getNormalizedExecutionMode();

    // Assert
    assertEquals("autofix_loop", normalized);
  }

  @Test
  @DisplayName("getNormalizedExecutionMode preserves analysis_only value")
  void testNormalizePreservesAnalysisOnly() {
    // Arrange
    GraphExecuteRequest request = new GraphExecuteRequest();
    request.setExecutionMode("analysis_only");

    // Act
    String normalized = request.getNormalizedExecutionMode();

    // Assert
    assertEquals("analysis_only", normalized);
  }

  @Test
  @DisplayName("getNormalizedExecutionMode returns original value for unknown modes")
  void testNormalizePreservesUnknownModes() {
    // Arrange
    GraphExecuteRequest request = new GraphExecuteRequest();
    request.setExecutionMode("unknown_mode");

    // Act
    String normalized = request.getNormalizedExecutionMode();

    // Assert
    assertEquals("unknown_mode", normalized);
  }

  @Test
  @DisplayName("getExecutionMode returns original value without normalization")
  void testGetExecutionModeReturnsOriginal() {
    // Arrange
    GraphExecuteRequest request = new GraphExecuteRequest();
    request.setExecutionMode("full_loop");

    // Act
    String original = request.getExecutionMode();

    // Assert
    assertEquals("full_loop", original);
  }
}

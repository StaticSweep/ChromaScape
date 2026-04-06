package com.chromascape.web.debug.patchloop.graph;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Test suite for PatchLoopGraphCompiler. */
class PatchLoopGraphCompilerTest {

  private PatchLoopGraphCompiler compiler;

  @BeforeEach
  void setUp() {
    compiler = new PatchLoopGraphCompiler();
  }

  @Test
  @DisplayName("partial_success edge label is parsed correctly")
  void testPartialSuccessLabelParsed() throws GraphCompilationException {
    // Arrange
    String mermaid =
        """
        graph TD;
          A[analyze] -.partial_success.-> B[generate_patch];
          A --> C[end];
        """;

    // Act
    LoopGraph graph = compiler.compile(mermaid);

    // Assert
    assertNotNull(graph);
    var edges = graph.getOutgoingEdges("A");
    assertFalse(edges.isEmpty(), "Should have outgoing edges from A");

    boolean hasPartialSuccessEdge =
        edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.PARTIAL_SUCCESS);
    assertTrue(hasPartialSuccessEdge, "Should have a PARTIAL_SUCCESS edge");
  }

  @Test
  @DisplayName("partial edge label is parsed correctly as PARTIAL_SUCCESS")
  void testPartialLabelParsed() throws GraphCompilationException {
    // Arrange
    String mermaid =
        """
        graph TD;
          A[analyze] -.partial.-> B[generate_patch];
        """;

    // Act
    LoopGraph graph = compiler.compile(mermaid);

    // Assert
    assertNotNull(graph);
    var edges = graph.getOutgoingEdges("A");
    assertFalse(edges.isEmpty(), "Should have outgoing edges from A");

    boolean hasPartialSuccessEdge =
        edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.PARTIAL_SUCCESS);
    assertTrue(hasPartialSuccessEdge, "Should have a PARTIAL_SUCCESS edge");
  }

  @Test
  @DisplayName("partial-success edge label is parsed correctly as PARTIAL_SUCCESS")
  void testPartialDashSuccessLabelParsed() throws GraphCompilationException {
    // Arrange
    String mermaid =
        """
        graph TD;
          A[analyze] -.partial-success.-> B[generate_patch];
        """;

    // Act
    LoopGraph graph = compiler.compile(mermaid);

    // Assert
    assertNotNull(graph);
    var edges = graph.getOutgoingEdges("A");
    assertFalse(edges.isEmpty(), "Should have outgoing edges from A");

    boolean hasPartialSuccessEdge =
        edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.PARTIAL_SUCCESS);
    assertTrue(hasPartialSuccessEdge, "Should have a PARTIAL_SUCCESS edge");
  }

  @Test
  @DisplayName("failure edge label is parsed correctly")
  void testFailureLabelParsed() throws GraphCompilationException {
    // Arrange
    String mermaid = """
        graph TD;
          A[analyze] -.failure.-> B[end];
        """;

    // Act
    LoopGraph graph = compiler.compile(mermaid);

    // Assert
    assertNotNull(graph);
    var edges = graph.getOutgoingEdges("A");
    assertFalse(edges.isEmpty(), "Should have outgoing edges from A");

    boolean hasFailureEdge =
        edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.FAILURE);
    assertTrue(hasFailureEdge, "Should have a FAILURE edge");
  }

  @Test
  @DisplayName("retry edge label is parsed correctly")
  void testRetryLabelParsed() throws GraphCompilationException {
    // Arrange
    String mermaid = """
        graph TD;
          A[analyze] -.retry.-> A;
        """;

    // Act
    LoopGraph graph = compiler.compile(mermaid);

    // Assert
    assertNotNull(graph);
    var edges = graph.getOutgoingEdges("A");
    assertFalse(edges.isEmpty(), "Should have outgoing edges from A");

    boolean hasRetryEdge = edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.RETRY);
    assertTrue(hasRetryEdge, "Should have a RETRY edge");
  }

  @Test
  @DisplayName("timeout edge label is parsed correctly")
  void testTimeoutLabelParsed() throws GraphCompilationException {
    // Arrange
    String mermaid = """
        graph TD;
          A[analyze] -.timeout.-> B[end];
        """;

    // Act
    LoopGraph graph = compiler.compile(mermaid);

    // Assert
    assertNotNull(graph);
    var edges = graph.getOutgoingEdges("A");
    assertFalse(edges.isEmpty(), "Should have outgoing edges from A");

    boolean hasTimeoutEdge =
        edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.TIMEOUT);
    assertTrue(hasTimeoutEdge, "Should have a TIMEOUT edge");
  }

  @Test
  @DisplayName("success edge label is parsed correctly")
  void testSuccessLabelParsed() throws GraphCompilationException {
    // Arrange
    String mermaid = """
        graph TD;
          A[analyze] --> B[generate_patch];
        """;

    // Act
    LoopGraph graph = compiler.compile(mermaid);

    // Assert
    assertNotNull(graph);
    var edges = graph.getOutgoingEdges("A");
    assertFalse(edges.isEmpty(), "Should have outgoing edges from A");

    boolean hasSuccessEdge =
        edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.SUCCESS);
    assertTrue(hasSuccessEdge, "Should have a SUCCESS edge");
  }

  @Test
  @DisplayName("Unknown edge label defaults to SUCCESS with warning")
  void testUnknownLabelDefaultsToSuccess() throws GraphCompilationException {
    // Arrange
    String mermaid =
        """
        graph TD;
          A[analyze] -.unknown_label.-> B[generate_patch];
        """;

    // Act
    LoopGraph graph = compiler.compile(mermaid);

    // Assert
    assertNotNull(graph);
    var edges = graph.getOutgoingEdges("A");
    assertFalse(edges.isEmpty(), "Should have outgoing edges from A");

    boolean hasSuccessEdge =
        edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.SUCCESS);
    assertTrue(hasSuccessEdge, "Unknown label should default to SUCCESS");
  }

  @Test
  @DisplayName("Mixed edge conditions are parsed correctly")
  void testMixedEdgeConditions() throws GraphCompilationException {
    // Arrange
    String mermaid =
        """
        graph TD;
          A[analyze] --> B[generate_patch];
          A -.failure.-> C[end];
          A -.partial_success.-> D[validate];
          A -.retry.-> A;
        """;

    // Act
    LoopGraph graph = compiler.compile(mermaid);

    // Assert
    assertNotNull(graph);
    var edges = graph.getOutgoingEdges("A");
    assertEquals(4, edges.size(), "Should have 4 outgoing edges");

    boolean hasSuccess = edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.SUCCESS);
    boolean hasFailure = edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.FAILURE);
    boolean hasPartialSuccess =
        edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.PARTIAL_SUCCESS);
    boolean hasRetry = edges.stream().anyMatch(edge -> edge.condition() == EdgeCondition.RETRY);

    assertTrue(hasSuccess, "Should have SUCCESS edge");
    assertTrue(hasFailure, "Should have FAILURE edge");
    assertTrue(hasPartialSuccess, "Should have PARTIAL_SUCCESS edge");
    assertTrue(hasRetry, "Should have RETRY edge");
  }
}

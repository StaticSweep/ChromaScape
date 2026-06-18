package com.chromascape.web.debug.patchloop.graph;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.debug.patchloop.dto.GraphExecuteResponse;
import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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

  @Test
  @DisplayName("Inline node declarations keep declared node types")
  void testInlineNodeDeclarationsPreserveNodeTypes() throws GraphCompilationException {
    String mermaid =
        """
        graph TD;
          A[start] --> B[run_script]
          B --> C[collect_evidence]
        """;

    LoopGraph graph = compiler.compile(mermaid);

    assertNotNull(graph);
    assertEquals(NodeType.START, graph.getNode("A").type());
    assertEquals(NodeType.RUN_SCRIPT, graph.getNode("B").type());
    assertEquals(NodeType.COLLECT_EVIDENCE, graph.getNode("C").type());
  }

  /** Asserts that a run-only graph contains no ANALYZE nodes. */
  @Test
  @DisplayName("Run-only graph has no ANALYZE node type")
  void testRunOnlyGraphHasNoAnalysisNodes() throws GraphCompilationException {
    String mermaid =
        """
        graph TD;
          A[start] --> B[run_script]
          B --> C[collect_evidence]
          C --> D[end]
        """;

    LoopGraph graph = compiler.compile(mermaid);

    boolean hasAnalysis =
        graph.getNodes().values().stream().anyMatch(node -> node.type() == NodeType.ANALYZE);
    assertFalse(hasAnalysis, "Run-only graph should not contain ANALYZE nodes");
  }

  /** Asserts that a full analysis graph contains an ANALYZE node. */
  @Test
  @DisplayName("Full analysis graph contains ANALYZE node type")
  void testAnalysisGraphContainsAnalyzeNode() throws GraphCompilationException {
    String mermaid =
        """
        graph TD;
          A[start] --> B[run_script]
          B --> C[collect_evidence]
          C --> D[analyze]
          D --> E[generate_patch]
          E --> F[end]
        """;

    LoopGraph graph = compiler.compile(mermaid);

    boolean hasAnalysis =
        graph.getNodes().values().stream().anyMatch(node -> node.type() == NodeType.ANALYZE);
    assertTrue(hasAnalysis, "Analysis graph should contain ANALYZE node");
  }

  /**
   * Locks the contract that node type names in compile response match the NodeType enum. The
   * frontend derives requirements by comparing node.type against the string "ANALYZE".
   */
  @Test
  @DisplayName("NodeType.ANALYZE name matches frontend gating string")
  void testAnalyzeNodeTypeNameMatchesFrontendExpectation() {
    assertEquals("ANALYZE", NodeType.ANALYZE.name());
  }

  // ========== Compile Response Shape Contract Tests ==========

  /**
   * End-to-end test: compile a run-only graph, build the DTO, serialize to JSON, and verify the
   * shape matches what the frontend's deriveGraphRequirements() function expects.
   *
   * <p>This catches contract drift between backend enum names and frontend gating strings. If
   * NodeType.ANALYZE is renamed or the DTO serialization changes, this test breaks.
   */
  @Test
  @DisplayName("Compile response for run-only graph has no ANALYZE type in serialized JSON")
  void testRunOnlyGraphResponseShapeMatchesFrontendExpectation() throws GraphCompilationException {
    String mermaid =
        """
        graph TD;
          A[start] --> B[run_script]
          B --> C[collect_evidence]
          C --> D[end]
        """;

    LoopGraph graph = compiler.compile(mermaid);
    GraphExecuteResponse.CompiledGraphInfo dto = buildDto(graph);
    JsonNode json = new ObjectMapper().valueToTree(dto);

    // Verify top-level shape
    assertTrue(json.has("nodes"), "Response must have 'nodes' array");
    assertTrue(json.get("nodes").isArray(), "'nodes' must be a JSON array");

    // Verify each node has 'type' field and none is "ANALYZE"
    boolean hasAnalyze = false;
    for (JsonNode node : json.get("nodes")) {
      assertTrue(node.has("type"), "Each node must have 'type' field");
      String type = node.get("type").asText();
      assertFalse(type.isEmpty(), "Node type must not be empty");
      if ("ANALYZE".equals(type)) {
        hasAnalyze = true;
      }
    }
    assertFalse(hasAnalyze, "Run-only graph must not produce 'ANALYZE' type in response JSON");
  }

  /**
   * End-to-end test: compile an analysis graph, serialize, and verify "ANALYZE" appears as a node
   * type string exactly matching what the frontend checks.
   */
  @Test
  @DisplayName("Compile response for analysis graph contains ANALYZE type string")
  void testAnalysisGraphResponseShapeContainsAnalyzeType() throws GraphCompilationException {
    String mermaid =
        """
        graph TD;
          A[start] --> B[run_script]
          B --> C[collect_evidence]
          C --> D[analyze]
          D --> E[generate_patch]
          E --> F[end]
        """;

    LoopGraph graph = compiler.compile(mermaid);
    GraphExecuteResponse.CompiledGraphInfo dto = buildDto(graph);
    JsonNode json = new ObjectMapper().valueToTree(dto);

    assertTrue(json.has("nodes"), "Response must have 'nodes' array");

    boolean hasAnalyze = false;
    for (JsonNode node : json.get("nodes")) {
      assertTrue(node.has("type"), "Each node must have 'type' field");
      if ("ANALYZE".equals(node.get("type").asText())) {
        hasAnalyze = true;
      }
    }
    assertTrue(hasAnalyze, "Analysis graph must produce 'ANALYZE' type in response JSON");
  }

  /**
   * Mirrors PatchLoopGraphOrchestratorService.buildCompiledGraphInfo() to test the real DTO mapping
   * without requiring Spring context or database.
   */
  private static GraphExecuteResponse.CompiledGraphInfo buildDto(LoopGraph graph) {
    ObjectMapper mapper = new ObjectMapper();
    GraphExecuteResponse.CompiledGraphInfo info = new GraphExecuteResponse.CompiledGraphInfo();
    info.setNodeCount(graph.getNodes().size());
    info.setEdgeCount(graph.getEdges().values().stream().mapToInt(List::size).sum());
    info.setEntryNodeId(graph.getEntryNodeId());
    info.setTerminalNodeIds(new ArrayList<>(graph.getTerminalNodeIds()));

    List<GraphExecuteResponse.GraphNodeInfo> nodes = new ArrayList<>();
    for (GraphNode node : graph.getNodes().values()) {
      GraphExecuteResponse.GraphNodeInfo nodeInfo = new GraphExecuteResponse.GraphNodeInfo();
      nodeInfo.setId(node.id());
      nodeInfo.setType(node.type().name());
      nodeInfo.setLabel(node.label());
      nodeInfo.setConfig(mapper.valueToTree(node.config()));
      nodes.add(nodeInfo);
    }
    info.setNodes(nodes);
    return info;
  }
}

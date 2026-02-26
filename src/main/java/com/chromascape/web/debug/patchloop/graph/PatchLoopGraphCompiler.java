package com.chromascape.web.debug.patchloop.graph;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Compiler for Mermaid-based patch loop graphs.
 *
 * <p>Parses a constrained subset of Mermaid syntax into a {@link LoopGraph} IR.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>graph TD; - declares a top-down graph</li>
 *   <li>A[run_script] - declares a node with type</li>
 *   <li>A --> B - declares a success edge</li>
 *   <li>A -.failure.-> B - declares a failure edge</li>
 *   <li>A -.retry.-> A - declares a retry edge</li>
 * </ul>
 *
 * <p>Supported node types: run_script, collect_evidence, analyze, generate_patch, apply_patch,
 * validate, retest, evaluate_success, end, start
 */
@Component
public class PatchLoopGraphCompiler {

  private static final Logger logger = LoggerFactory.getLogger(PatchLoopGraphCompiler.class);

  /** Pattern for matching a node declaration: A[run_script] or A[run_script config] */
  private static final Pattern NODE_PATTERN =
      Pattern.compile(
          "\\b([A-Za-z0-9_]+)\\[([A-Za-z0-9_]+)(?:\\s+(.+?))?\\]");

  /** Pattern for matching an edge: A --> B or A -.label.-> B */
  private static final Pattern EDGE_PATTERN =
      Pattern.compile("\\b([A-Za-z0-9_]+)\\s*(\\.-([^\\-]+)\\.->|-->|->)\\s*([A-Za-z0-9_]+)");

  /** Pattern for matching the graph declaration */
  private static final Pattern GRAPH_DECLARATION = Pattern.compile("graph\\s+(TD|LR);?");

  /**
   * Compiles a Mermaid graph source into a LoopGraph IR.
   *
   * @param mermaidSource the Mermaid source code
   * @return the compiled LoopGraph
   * @throws GraphCompilationException if compilation fails
   */
  public LoopGraph compile(String mermaidSource) throws GraphCompilationException {
    if (mermaidSource == null || mermaidSource.isBlank()) {
      throw new GraphCompilationException("Mermaid source cannot be null or blank");
    }

    logger.debug("Compiling Mermaid graph:\n{}", mermaidSource);

    LoopGraph graph = new LoopGraph();
    graph.setMermaidSource(mermaidSource);
    Set<String> nodeIds = new HashSet<>();
    List<String> warnings = new ArrayList<>();

    // Parse the Mermaid source
    List<MermaidStatement> statements = parseStatements(mermaidSource);

    // First pass: collect all nodes
    for (MermaidStatement stmt : statements) {
      if (stmt.type() == StatementType.NODE) {
        parseAndAddNode(stmt, graph, nodeIds, warnings);
      }
    }

    // Second pass: collect all edges
    for (MermaidStatement stmt : statements) {
      if (stmt.type() == StatementType.EDGE) {
        parseAndAddEdge(stmt, graph, nodeIds, warnings);
      }
    }

    // Determine entry point (first node declared, or a node named START)
    String entryNodeId = determineEntryPoint(graph, nodeIds);
    graph.setEntryNodeId(entryNodeId);

    // Mark terminal nodes (END type or nodes with no outgoing edges)
    markTerminalNodes(graph, nodeIds);

    // Update metadata
    graph.getMetadata().setNodeCount(graph.getNodes().size());
    graph.getMetadata().setEdgeCount(
        graph.getEdges().values().stream().mapToInt(List::size).sum());
    graph.getMetadata().setCompiledAt(
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    // Validate the graph
    try {
      graph.validate();
    } catch (IllegalStateException e) {
      throw new GraphCompilationException("Graph validation failed: " + e.getMessage(), e);
    }

    logger.info(
        "Compiled graph: {} nodes, {} edges, entry: {}",
        graph.getNodes().size(),
        graph.getMetadata().getEdgeCount(),
        entryNodeId);

    if (!warnings.isEmpty()) {
      logger.warn("Compilation warnings: {}", warnings);
    }

    return graph;
  }

  /**
   * Parses Mermaid source into a list of statements.
   *
   * @param source the Mermaid source
   * @return list of parsed statements
   */
  private List<MermaidStatement> parseStatements(String source) {
    List<MermaidStatement> statements = new ArrayList<>();

    // Normalize the source
    String normalized = source.replaceAll("\\r\\n", "\n").trim();

    // Split into lines and parse each line
    for (String line : normalized.split("\n")) {
      line = line.trim();

      // Skip empty lines and comments
      if (line.isEmpty() || line.startsWith("%%")) {
        continue;
      }

      // Skip the graph declaration line
      if (GRAPH_DECLARATION.matcher(line).matches()) {
        continue;
      }

      // Try to parse as node
      Matcher nodeMatcher = NODE_PATTERN.matcher(line);
      if (nodeMatcher.find()) {
        statements.add(
            new MermaidStatement(StatementType.NODE, nodeMatcher.group(0), nodeMatcher));
        continue;
      }

      // Try to parse as edge
      Matcher edgeMatcher = EDGE_PATTERN.matcher(line);
      if (edgeMatcher.find()) {
        statements.add(
            new MermaidStatement(StatementType.EDGE, edgeMatcher.group(0), edgeMatcher));
        continue;
      }

      logger.warn("Unrecognized statement: {}", line);
    }

    return statements;
  }

  /**
   * Parses a node statement and adds it to the graph.
   */
  private void parseAndAddNode(
      MermaidStatement stmt,
      LoopGraph graph,
      Set<String> nodeIds,
      List<String> warnings) {
    Matcher matcher = stmt.matcher();
    String nodeId = matcher.group(1);
    String nodeTypeLabel = matcher.group(2);
    String config = matcher.group(3);

    // Skip duplicates
    if (nodeIds.contains(nodeId)) {
      warnings.add("Duplicate node ID: " + nodeId);
      return;
    }

    NodeType nodeType = parseNodeType(nodeTypeLabel);
    if (nodeType == null) {
      warnings.add("Unknown node type: " + nodeTypeLabel + ", using RUN_SCRIPT");
      nodeType = NodeType.RUN_SCRIPT;
    }

    GraphNode node = GraphNode.of(nodeId, nodeType, nodeTypeLabel);
    graph.addNode(node);
    nodeIds.add(nodeId);
  }

  /**
   * Parses an edge statement and adds it to the graph.
   */
  private void parseAndAddEdge(
      MermaidStatement stmt,
      LoopGraph graph,
      Set<String> nodeIds,
      List<String> warnings) {
    Matcher matcher = stmt.matcher();
    String from = matcher.group(1);
    String edgeSyntax = matcher.group(2);
    String edgeLabel = matcher.group(3); // May be null for -->
    String to = matcher.group(4);

    // Auto-declare nodes if they don't exist
    if (!nodeIds.contains(from)) {
      warnings.add("Auto-declaring node: " + from);
      graph.addNode(GraphNode.of(from, NodeType.RUN_SCRIPT));
      nodeIds.add(from);
    }
    if (!nodeIds.contains(to)) {
      warnings.add("Auto-declaring node: " + to);
      graph.addNode(GraphNode.of(to, NodeType.RUN_SCRIPT));
      nodeIds.add(to);
    }

    // Determine edge condition
    EdgeCondition condition = EdgeCondition.SUCCESS;
    if (edgeLabel != null) {
      String label = edgeLabel.trim().toLowerCase();
      condition = switch (label) {
        case "failure", "fail" -> EdgeCondition.FAILURE;
        case "retry" -> EdgeCondition.RETRY;
        case "timeout" -> EdgeCondition.TIMEOUT;
        default -> {
          warnings.add("Unknown edge label: " + edgeLabel + ", using SUCCESS");
          yield EdgeCondition.SUCCESS;
        }
      };
    }

    GraphEdge edge = new GraphEdge(from, to, condition);
    graph.addEdge(edge);
  }

  /**
   * Parses a node type label into a NodeType enum.
   *
   * @param label the type label (e.g., "run_script")
   * @return the NodeType, or null if not recognized
   */
  private NodeType parseNodeType(String label) {
    return switch (label.toLowerCase()) {
      case "run_script" -> NodeType.RUN_SCRIPT;
      case "collect_evidence" -> NodeType.COLLECT_EVIDENCE;
      case "analyze" -> NodeType.ANALYZE;
      case "generate_patch" -> NodeType.GENERATE_PATCH;
      case "apply_patch" -> NodeType.APPLY_PATCH;
      case "validate" -> NodeType.VALIDATE;
      case "retest" -> NodeType.RETEST;
      case "evaluate_success" -> NodeType.EVALUATE_SUCCESS;
      case "end" -> NodeType.END;
      case "start" -> NodeType.START;
      default -> null;
    };
  }

  /**
   * Determines the entry point for the graph.
   *
   * <p>Priorities: 1) Node named START, 2) First node declared
   */
  private String determineEntryPoint(LoopGraph graph, Set<String> nodeIds) {
    // Look for a node named START
    for (String nodeId : nodeIds) {
      GraphNode node = graph.getNode(nodeId);
      if (node.type() == NodeType.START) {
        return nodeId;
      }
    }

    // Use the first declared node
    return nodeIds.stream().findFirst().orElse(null);
  }

  /**
   * Marks terminal nodes in the graph.
   *
   * <p>A node is terminal if: 1) It's type END, 2) It has no outgoing edges
   */
  private void markTerminalNodes(LoopGraph graph, Set<String> nodeIds) {
    for (String nodeId : nodeIds) {
      GraphNode node = graph.getNode(nodeId);

      // Mark END nodes as terminal
      if (node.type() == NodeType.END) {
        graph.markTerminal(nodeId);
        continue;
      }

      // Mark nodes with no outgoing edges as terminal
      if (graph.getOutgoingEdges(nodeId).isEmpty()) {
        graph.markTerminal(nodeId);
      }
    }
  }

  /** Type of statement parsed from Mermaid source. */
  private enum StatementType {
    NODE,
    EDGE
  }

  /** A parsed Mermaid statement. */
  private record MermaidStatement(
      StatementType type, String source, Matcher matcher) {}
}

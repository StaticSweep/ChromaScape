package com.chromascape.web.debug.patchloop.graph;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Internal representation (IR) of a compiled patch loop graph.
 *
 * <p>Contains nodes, edges, entry point, and terminal nodes for graph execution.
 */
public class LoopGraph {

  /** All nodes in the graph, keyed by node ID. */
  @JsonProperty("nodes")
  private final Map<String, GraphNode> nodes;

  /** Outgoing edges from each node, keyed by source node ID. */
  @JsonProperty("edges")
  private final Map<String, List<GraphEdge>> edges;

  /** The entry point node ID. */
  @JsonProperty("entryNodeId")
  private String entryNodeId;

  /** Terminal node IDs (END, success terminals). */
  @JsonProperty("terminalNodeIds")
  private final Set<String> terminalNodeIds;

  /** Original Mermaid source (for reference). */
  @JsonProperty("mermaidSource")
  private String mermaidSource;

  /** Metadata about the compiled graph. */
  @JsonProperty("metadata")
  private GraphMetadata metadata;

  public LoopGraph() {
    this.nodes = new HashMap<>();
    this.edges = new HashMap<>();
    this.terminalNodeIds = new HashSet<>();
    this.metadata = new GraphMetadata();
  }

  /**
   * Adds a node to the graph.
   *
   * @param node the node to add
   */
  public void addNode(GraphNode node) {
    nodes.put(node.id(), node);
  }

  /**
   * Adds an edge to the graph.
   *
   * @param edge the edge to add
   */
  public void addEdge(GraphEdge edge) {
    edges.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
  }

  /**
   * Gets all outgoing edges from a node.
   *
   * @param nodeId the source node ID
   * @return list of outgoing edges (empty if none)
   */
  public List<GraphEdge> getOutgoingEdges(String nodeId) {
    return edges.getOrDefault(nodeId, Collections.emptyList());
  }

  /**
   * Gets outgoing edges from a node that match a specific condition.
   *
   * @param nodeId the source node ID
   * @param condition the edge condition to match
   * @return list of matching edges (empty if none)
   */
  public List<GraphEdge> getOutgoingEdges(String nodeId, EdgeCondition condition) {
    return getOutgoingEdges(nodeId).stream()
        .filter(e -> e.condition() == condition)
        .toList();
  }

  /**
   * Gets a node by ID.
   *
   * @param nodeId the node ID
   * @return the node, or null if not found
   */
  public GraphNode getNode(String nodeId) {
    return nodes.get(nodeId);
  }

  /**
   * Returns true if the node ID exists in the graph.
   *
   * @param nodeId the node ID to check
   * @return true if the node exists
   */
  public boolean hasNode(String nodeId) {
    return nodes.containsKey(nodeId);
  }

  /**
   * Returns true if the node is a terminal node.
   *
   * @param nodeId the node ID to check
   * @return true if terminal
   */
  public boolean isTerminalNode(String nodeId) {
    return terminalNodeIds.contains(nodeId);
  }

  /**
   * Marks a node as terminal.
   *
   * @param nodeId the node ID to mark as terminal
   */
  public void markTerminal(String nodeId) {
    terminalNodeIds.add(nodeId);
  }

  /**
   * Validates the graph structure.
   *
   * @throws IllegalStateException if the graph is invalid
   */
  public void validate() {
    if (entryNodeId == null || !nodes.containsKey(entryNodeId)) {
      throw new IllegalStateException("Invalid entry node: " + entryNodeId);
    }

    // Check for cycles (excluding explicit retry edges)
    detectCycles();

    // Check that all terminal nodes have no outgoing edges
    for (String terminalId : terminalNodeIds) {
      if (edges.containsKey(terminalId) && !edges.get(terminalId).isEmpty()) {
        throw new IllegalStateException("Terminal node has outgoing edges: " + terminalId);
      }
    }

    // Check that all edge targets exist
    for (List<GraphEdge> edgeList : edges.values()) {
      for (GraphEdge edge : edgeList) {
        if (!nodes.containsKey(edge.to())) {
          throw new IllegalStateException("Edge target not found: " + edge.to());
        }
      }
    }
  }

  /**
   * Detects cycles in the graph (excluding retry edges).
   *
   * @throws IllegalStateException if a cycle is detected
   */
  private void detectCycles() {
    Set<String> visited = new HashSet<>();
    Set<String> recStack = new HashSet<>();

    for (String nodeId : nodes.keySet()) {
      if (detectCycle(nodeId, visited, recStack)) {
        throw new IllegalStateException("Cycle detected in graph at node: " + nodeId);
      }
    }
  }

  private boolean detectCycle(String nodeId, Set<String> visited, Set<String> recStack) {
    if (recStack.contains(nodeId)) {
      return true;
    }
    if (visited.contains(nodeId)) {
      return false;
    }

    visited.add(nodeId);
    recStack.add(nodeId);

    for (GraphEdge edge : getOutgoingEdges(nodeId)) {
      // Skip retry edges (self-loops are allowed)
      if (edge.condition() != EdgeCondition.RETRY) {
        if (detectCycle(edge.to(), visited, recStack)) {
          return true;
        }
      }
    }

    recStack.remove(nodeId);
    return false;
  }

  // Getters and setters

  public Map<String, GraphNode> getNodes() {
    return Collections.unmodifiableMap(nodes);
  }

  public Map<String, List<GraphEdge>> getEdges() {
    return Collections.unmodifiableMap(edges);
  }

  public String getEntryNodeId() {
    return entryNodeId;
  }

  public void setEntryNodeId(String entryNodeId) {
    this.entryNodeId = entryNodeId;
  }

  public Set<String> getTerminalNodeIds() {
    return Collections.unmodifiableSet(terminalNodeIds);
  }

  public String getMermaidSource() {
    return mermaidSource;
  }

  public void setMermaidSource(String mermaidSource) {
    this.mermaidSource = mermaidSource;
  }

  public GraphMetadata getMetadata() {
    return metadata;
  }

  public void setMetadata(GraphMetadata metadata) {
    this.metadata = metadata;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    LoopGraph loopGraph = (LoopGraph) o;
    return Objects.equals(nodes.keySet(), loopGraph.nodes.keySet())
        && Objects.equals(entryNodeId, loopGraph.entryNodeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nodes.keySet(), entryNodeId);
  }

  @Override
  public String toString() {
    return "LoopGraph{"
        + "nodeCount="
        + nodes.size()
        + ", edgeCount="
        + edges.values().stream().mapToInt(List::size).sum()
        + ", entryNodeId='"
        + entryNodeId
        + '\''
        + ", terminalCount="
        + terminalNodeIds.size()
        + '}';
  }

  /** Metadata about the compiled graph. */
  public static class GraphMetadata {

    @JsonProperty("nodeCount")
    private int nodeCount;

    @JsonProperty("edgeCount")
    private int edgeCount;

    @JsonProperty("compiledAt")
    private String compiledAt;

    @JsonProperty("compilerVersion")
    private String compilerVersion = "1.0.0";

    public int getNodeCount() {
      return nodeCount;
    }

    public void setNodeCount(int nodeCount) {
      this.nodeCount = nodeCount;
    }

    public int getEdgeCount() {
      return edgeCount;
    }

    public void setEdgeCount(int edgeCount) {
      this.edgeCount = edgeCount;
    }

    public String getCompiledAt() {
      return compiledAt;
    }

    public void setCompiledAt(String compiledAt) {
      this.compiledAt = compiledAt;
    }

    public String getCompilerVersion() {
      return compilerVersion;
    }

    public void setCompilerVersion(String compilerVersion) {
      this.compilerVersion = compilerVersion;
    }
  }
}

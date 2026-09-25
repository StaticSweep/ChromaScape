package com.chromascape.web.debug.patchloop.graph;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * An edge in the patch loop graph.
 *
 * @param from the source node ID
 * @param to the target node ID
 * @param condition the edge condition for traversal
 */
public record GraphEdge(
    @JsonProperty("from") String from,
    @JsonProperty("to") String to,
    @JsonProperty("condition") EdgeCondition condition) {

  /**
   * Creates a new edge with SUCCESS condition.
   *
   * @param from the source node ID
   * @param to the target node ID
   * @return a new GraphEdge
   */
  public static GraphEdge success(String from, String to) {
    return new GraphEdge(from, to, EdgeCondition.SUCCESS);
  }

  /**
   * Creates a new edge with FAILURE condition.
   *
   * @param from the source node ID
   * @param to the target node ID
   * @return a new GraphEdge
   */
  public static GraphEdge failure(String from, String to) {
    return new GraphEdge(from, to, EdgeCondition.FAILURE);
  }

  /**
   * Creates a new edge with RETRY condition (self-loop).
   *
   * @param nodeId the node ID to retry
   * @return a new GraphEdge
   */
  public static GraphEdge retry(String nodeId) {
    return new GraphEdge(nodeId, nodeId, EdgeCondition.RETRY);
  }

  /**
   * Creates a new edge with TIMEOUT condition.
   *
   * @param from the source node ID
   * @param to the target node ID
   * @return a new GraphEdge
   */
  public static GraphEdge timeout(String from, String to) {
    return new GraphEdge(from, to, EdgeCondition.TIMEOUT);
  }

  public GraphEdge {
    if (from == null || from.isBlank()) {
      throw new IllegalArgumentException("Edge 'from' cannot be null or blank");
    }
    if (to == null || to.isBlank()) {
      throw new IllegalArgumentException("Edge 'to' cannot be null or blank");
    }
    if (condition == null) {
      condition = EdgeCondition.SUCCESS;
    }
  }

  /**
   * Returns true if this is a retry edge (self-loop).
   *
   * @return true if retry edge
   */
  public boolean isRetry() {
    return condition == EdgeCondition.RETRY || from.equals(to);
  }

  /**
   * Returns true if this is a self-loop edge.
   *
   * @return true if self-loop
   */
  public boolean isSelfLoop() {
    return from.equals(to);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GraphEdge graphEdge = (GraphEdge) o;
    return Objects.equals(from, graphEdge.from)
        && Objects.equals(to, graphEdge.to)
        && condition == graphEdge.condition;
  }

  @Override
  public int hashCode() {
    return Objects.hash(from, to, condition);
  }

  @Override
  public String toString() {
    return "GraphEdge{"
        + "from='"
        + from
        + '\''
        + ", to='"
        + to
        + '\''
        + ", condition="
        + condition
        + '}';
  }
}

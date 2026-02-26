package com.chromascape.web.debug.patchloop.graph;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Result of executing a graph node.
 *
 * @param nodeId the node ID that was executed
 * @param success true if execution succeeded
 * @param retry true if the node should be retried
 * @param timedOut true if execution timed out
 * @param outputSummary brief summary of output
 * @param outputData full output data
 */
public record NodeExecutionResult(
    String nodeId,
    boolean success,
    boolean retry,
    boolean timedOut,
    String outputSummary,
    JsonNode outputData) {

  /**
   * Creates a successful result.
   */
  public static NodeExecutionResult success(String nodeId) {
    return new NodeExecutionResult(nodeId, true, false, false, null, null);
  }

  /**
   * Creates a successful result with output.
   */
  public static NodeExecutionResult success(String nodeId, String outputSummary, JsonNode outputData) {
    return new NodeExecutionResult(nodeId, true, false, false, outputSummary, outputData);
  }

  /**
   * Creates a failure result.
   */
  public static NodeExecutionResult failure(String nodeId, String errorMessage) {
    return new NodeExecutionResult(nodeId, false, false, false, errorMessage, null);
  }

  /**
   * Creates a failure result with retry.
   */
  public static NodeExecutionResult failureWithRetry(String nodeId, String errorMessage) {
    return new NodeExecutionResult(nodeId, false, true, false, errorMessage, null);
  }

  /**
   * Creates a timeout result.
   */
  public static NodeExecutionResult timeout(String nodeId) {
    return new NodeExecutionResult(nodeId, false, false, true, "Execution timed out", null);
  }

  /**
   * Creates a retry result.
   */
  public static NodeExecutionResult retry(String nodeId, String reason) {
    return new NodeExecutionResult(nodeId, false, true, false, reason, null);
  }

  /**
   * Returns a result with output summary added.
   */
  public NodeExecutionResult withOutput(String outputSummary) {
    return new NodeExecutionResult(nodeId, success, retry, timedOut, outputSummary, outputData);
  }

  /**
   * Returns a result with output data added.
   */
  public NodeExecutionResult withOutputData(JsonNode outputData) {
    return new NodeExecutionResult(nodeId, success, retry, timedOut, outputSummary, outputData);
  }

  /**
   * Returns a result with retry flag changed.
   */
  public NodeExecutionResult withRetry(boolean retry) {
    return new NodeExecutionResult(nodeId, success, retry, timedOut, outputSummary, outputData);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    NodeExecutionResult that = (NodeExecutionResult) o;
    return Objects.equals(nodeId, that.nodeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nodeId);
  }

  @Override
  public String toString() {
    return "NodeExecutionResult{"
        + "nodeId='"
        + nodeId
        + '\''
        + ", success="
        + success
        + ", retry="
        + retry
        + ", timedOut="
        + timedOut
        + '}';
  }
}

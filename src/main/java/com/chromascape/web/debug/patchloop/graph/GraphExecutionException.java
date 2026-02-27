package com.chromascape.web.debug.patchloop.graph;

/** Exception thrown when graph execution fails. */
public class GraphExecutionException extends Exception {

  private final String executionId;
  private final String nodeId;

  public GraphExecutionException(String message) {
    super(message);
    this.executionId = null;
    this.nodeId = null;
  }

  public GraphExecutionException(String message, Throwable cause) {
    super(message, cause);
    this.executionId = null;
    this.nodeId = null;
  }

  public GraphExecutionException(String message, String executionId) {
    super(message);
    this.executionId = executionId;
    this.nodeId = null;
  }

  public GraphExecutionException(String message, String executionId, String nodeId) {
    super(message);
    this.executionId = executionId;
    this.nodeId = nodeId;
  }

  public GraphExecutionException(
      String message, String executionId, String nodeId, Throwable cause) {
    super(message, cause);
    this.executionId = executionId;
    this.nodeId = nodeId;
  }

  public String getExecutionId() {
    return executionId;
  }

  public String getNodeId() {
    return nodeId;
  }
}

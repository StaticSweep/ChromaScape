package com.chromascape.web.debug.patchloop.graph;

/**
 * Condition for traversing a graph edge.
 *
 * <p>Determines which outgoing edge to follow based on the result of node execution.
 */
public enum EdgeCondition {
  /** Node executed successfully */
  SUCCESS,

  /** Node executed with partial success - continue with warnings */
  PARTIAL_SUCCESS,

  /** Node execution failed */
  FAILURE,

  /** Retry the current node */
  RETRY,

  /** Node execution timed out */
  TIMEOUT,

  /** Always follow (unconditional) */
  ALWAYS,

  /** Conditional predicate evaluated */
  CONDITIONAL
}

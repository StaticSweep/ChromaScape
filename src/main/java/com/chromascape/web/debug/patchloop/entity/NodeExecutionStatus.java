package com.chromascape.web.debug.patchloop.entity;

/**
 * Execution status of a graph node.
 */
public enum NodeExecutionStatus {
  /** Node is waiting to be executed */
  PENDING,

  /** Node is currently executing */
  RUNNING,

  /** Node completed successfully */
  COMPLETED,

  /** Node failed execution */
  FAILED,

  /** Node was skipped */
  SKIPPED,

  /** Node execution timed out */
  TIMEOUT;
}

package com.chromascape.web.debug.patchloop.entity;

/**
 * Lifecycle states for a graph execution.
 *
 * <p>Represents the current state of a Mermaid-defined patch loop graph execution.
 */
public enum GraphExecutionStatus {
  /** Graph created and compiled, waiting to start */
  CREATED,

  /** Graph is currently executing */
  RUNNING,

  /** Execution paused by user request */
  PAUSED,

  /** Execution completed successfully */
  COMPLETED,

  /** Execution failed */
  FAILED,

  /** Execution cancelled by user */
  CANCELLED,

  /** Execution timed out */
  TIMEOUT,

  /** Execution stopped due to policy violation */
  POLICY_VIOLATION;

  /** Returns true if this is a terminal state (no further transitions). */
  public boolean isTerminal() {
    return this == COMPLETED
        || this == FAILED
        || this == CANCELLED
        || this == TIMEOUT
        || this == POLICY_VIOLATION;
  }

  /** Returns true if this is an active (non-terminal) state. */
  public boolean isActive() {
    return !isTerminal();
  }
}

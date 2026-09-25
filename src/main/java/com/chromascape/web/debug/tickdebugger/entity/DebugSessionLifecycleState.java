package com.chromascape.web.debug.tickdebugger.entity;

/**
 * Lifecycle states for debug sessions.
 *
 * <p>Tracks the progression of a session from creation through completion or failure.
 */
public enum DebugSessionLifecycleState {
  /** Session is being initialized, recording may be starting. */
  STARTING,

  /** Session is actively recording/collecting data. */
  RECORDING,

  /** Session completed successfully and is finalized. */
  FINALIZED,

  /** Session encountered a failure and was terminated abnormally. */
  FAILED
}

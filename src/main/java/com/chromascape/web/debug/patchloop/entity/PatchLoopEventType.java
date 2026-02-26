package com.chromascape.web.debug.patchloop.entity;

/**
 * Types of events that occur during a patch loop iteration.
 *
 * <p>Used for event logging and lifecycle tracking.
 */
public enum PatchLoopEventType {
  // Lifecycle events
  ITERATION_CREATED,
  ITERATION_STARTED,
  ITERATION_COMPLETED,
  ITERATION_FAILED,

  // Script run events
  SCRIPT_STARTED,
  SCRIPT_COMPLETED,
  SCRIPT_FAILED,
  SCRIPT_TIMEOUT,

  // Evidence collection events
  EVIDENCE_COLLECTION_STARTED,
  EVIDENCE_COLLECTED,
  EVIDENCE_INSUFFICIENT,
  EVIDENCE_COLLECTION_FAILED,

  // Analysis events
  ANALYSIS_STARTED,
  ANALYSIS_COMPLETED,
  ANALYSIS_FAILED,
  ANALYSIS_TIMEOUT,

  // State transition events
  STATE_TRANSITION,

  // Error events
  ERROR,
  WARNING
}

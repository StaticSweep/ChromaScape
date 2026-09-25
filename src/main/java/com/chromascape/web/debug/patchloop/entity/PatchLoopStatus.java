package com.chromascape.web.debug.patchloop.entity;

/**
 * Lifecycle states for a patch loop iteration.
 *
 * <p>Represents the current state of the BMAD (Bot Metric Analysis & Diagnostics) analysis
 * iteration.
 *
 * @since 2.1.0
 */
public enum PatchLoopStatus {
  /** Iteration created, waiting to start */
  QUEUED,

  /** Script is currently running */
  RUNNING_SCRIPT,

  /** Script run completed successfully */
  RUN_COMPLETED,

  /** Script run failed (exception, timeout, etc.) */
  RUN_FAILED,

  /** Collecting evidence from logs, tick logs, decisions */
  COLLECTING_EVIDENCE,

  /** Evidence collected successfully */
  EVIDENCE_COLLECTED,

  /** Insufficient evidence found (empty logs, no decisions, etc.) */
  INSUFFICIENT_EVIDENCE,

  /** Sending evidence to analysis provider */
  ANALYZING,

  /** Analysis completed with diagnosis */
  ANALYZED,

  /** Running retest to validate fix */
  RETESTING,

  /** Retest completed */
  RETESTED,

  /** Iteration completed successfully (terminal state) */
  COMPLETED,

  /** Iteration failed at some stage (terminal state) */
  FAILED,

  /** Iteration was cancelled by user (terminal state) */
  CANCELLED;

  /** Returns true if this is a terminal state (no further transitions). */
  public boolean isTerminal() {
    return this == COMPLETED
        || this == FAILED
        || this == INSUFFICIENT_EVIDENCE
        || this == CANCELLED;
  }

  /** Returns true if this is an active (non-terminal) state. */
  public boolean isActive() {
    return !isTerminal();
  }
}

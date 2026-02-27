package com.chromascape.web.debug.patchloop.entity;

/**
 * Lifecycle states for a patch loop iteration (MVP simplified).
 *
 * <p>Represents the current state of the analysis iteration. Removed RETESTING states for MVP.
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

  /** Sending evidence to Claude API for analysis */
  ANALYZING,

  /** Analysis completed with diagnosis */
  ANALYZED,

  /** Iteration completed successfully (terminal state) */
  COMPLETED,

  /** Iteration failed at some stage (terminal state) */
  FAILED;

  /** Returns true if this is a terminal state (no further transitions). */
  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == INSUFFICIENT_EVIDENCE;
  }

  /** Returns true if this is an active (non-terminal) state. */
  public boolean isActive() {
    return !isTerminal();
  }
}

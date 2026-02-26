package com.chromascape.web.debug.patchloop.entity;

/**
 * Outcome of a patch loop attempt.
 */
public enum AttemptOutcome {
  /** Script is still running */
  RUNNING,

  /** Script completed successfully and met success criteria */
  SUCCESS,

  /** Script failed or did not meet success criteria */
  FAILURE,

  /** Script timed out */
  TIMEOUT,

  /** Script crashed or was terminated unexpectedly */
  CRASH,

  /** Attempt was cancelled by user */
  CANCELLED,

  /** Attempt stopped due to policy violation */
  POLICY_VIOLATION;
}

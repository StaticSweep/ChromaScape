package com.chromascape.web.debug.buildergoal.recovery;

import java.util.Set;

/**
 * Incident state machine for Builder Goal compile recovery. States are monotonic within a proposal
 * or validation attempt; terminal records are immutable and retries create child attempt records.
 */
public final class RecoveryStates {

  public static final String INCIDENT_CAPTURED = "INCIDENT_CAPTURED";
  public static final String EVIDENCE_READY = "EVIDENCE_READY";
  public static final String ANALYSIS_QUEUED = "ANALYSIS_QUEUED";
  public static final String ANALYZING = "ANALYZING";
  public static final String ANALYSIS_FAILED_RETRYABLE = "ANALYSIS_FAILED_RETRYABLE";
  public static final String ANALYSIS_FAILED_TERMINAL = "ANALYSIS_FAILED_TERMINAL";
  public static final String AWAITING_REVIEW = "AWAITING_REVIEW";
  public static final String APPROVED = "APPROVED";
  public static final String REJECTED = "REJECTED";
  public static final String CHANGES_REQUESTED = "CHANGES_REQUESTED";
  public static final String VALIDATION_QUEUED = "VALIDATION_QUEUED";
  public static final String VALIDATING = "VALIDATING";
  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
  public static final String VALIDATION_CANCELLED = "VALIDATION_CANCELLED";
  public static final String VALIDATION_UNCERTAIN = "VALIDATION_UNCERTAIN";
  public static final String BUILD_VALIDATED = "BUILD_VALIDATED";
  public static final String RERUN_REQUESTED = "RERUN_REQUESTED";
  public static final String ACTIVATING_CANDIDATE = "ACTIVATING_CANDIDATE";
  public static final String STARTING_CHILD = "STARTING_CHILD";
  public static final String BOUND = "BOUND";
  public static final String NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION";

  /** Incident states that may legally follow the given state. */
  private static final Set<String> ALLOWED =
      Set.of(
          INCIDENT_CAPTURED + ">" + EVIDENCE_READY,
          EVIDENCE_READY + ">" + ANALYSIS_QUEUED,
          EVIDENCE_READY + ">" + ANALYZING,
          ANALYSIS_QUEUED + ">" + ANALYZING,
          ANALYZING + ">" + AWAITING_REVIEW,
          ANALYZING + ">" + ANALYSIS_FAILED_RETRYABLE,
          ANALYZING + ">" + ANALYSIS_FAILED_TERMINAL,
          ANALYZING + ">" + REJECTED,
          AWAITING_REVIEW + ">" + APPROVED,
          AWAITING_REVIEW + ">" + ANALYSIS_QUEUED,
          APPROVED + ">" + VALIDATION_QUEUED,
          VALIDATION_QUEUED + ">" + VALIDATING,
          VALIDATION_QUEUED + ">" + VALIDATION_FAILED,
          VALIDATING + ">" + VALIDATION_FAILED,
          VALIDATION_QUEUED + ">" + VALIDATION_CANCELLED,
          VALIDATING + ">" + VALIDATION_CANCELLED,
          VALIDATING + ">" + VALIDATION_UNCERTAIN,
          VALIDATING + ">" + BUILD_VALIDATED,
          VALIDATION_FAILED + ">" + ANALYZING,
          BUILD_VALIDATED + ">" + RERUN_REQUESTED,
          RERUN_REQUESTED + ">" + ACTIVATING_CANDIDATE,
          ACTIVATING_CANDIDATE + ">" + STARTING_CHILD,
          STARTING_CHILD + ">" + BOUND,
          RERUN_REQUESTED + ">" + NEEDS_RECONCILIATION,
          ACTIVATING_CANDIDATE + ">" + NEEDS_RECONCILIATION,
          STARTING_CHILD + ">" + NEEDS_RECONCILIATION,
          NEEDS_RECONCILIATION + ">" + BOUND,
          ANALYZING + ">" + ANALYZING,
          VALIDATING + ">" + VALIDATING);

  /** States that stop all polling and mutation. */
  public static final Set<String> TERMINAL =
      Set.of(REJECTED, ANALYSIS_FAILED_TERMINAL, VALIDATION_FAILED, VALIDATION_CANCELLED, BOUND);

  public static boolean canTransition(String from, String to) {
    if (from == null || to == null) {
      return false;
    }
    return ALLOWED.contains(from + ">" + to);
  }

  public static boolean isTerminal(String state) {
    return state != null && TERMINAL.contains(state);
  }

  private RecoveryStates() {}
}

package com.chromascape.web.debug.patchloop.entity;

/**
 * Failure codes for patch loop iterations.
 *
 * <p>These codes indicate why an iteration failed. Stored in {@link
 * PatchLoopIteration#failureCode}.
 */
public final class PatchLoopFailureCode {

  // Script run failures
  public static final String SCRIPT_START_FAILED = "SCRIPT_START_FAILED";
  public static final String SCRIPT_RUN_TIMEOUT = "SCRIPT_RUN_TIMEOUT";
  public static final String SCRIPT_EXCEPTION = "SCRIPT_EXCEPTION";
  public static final String SCRIPT_NOT_FOUND = "SCRIPT_NOT_FOUND";

  // Evidence collection failures
  public static final String EVIDENCE_COLLECTION_FAILED = "EVIDENCE_COLLECTION_FAILED";
  public static final String INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";
  public static final String INVALID_TIME_WINDOW = "INVALID_TIME_WINDOW";
  public static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";

  // Analysis failures
  public static final String ANALYSIS_API_ERROR = "ANALYSIS_API_ERROR";
  public static final String ANALYSIS_API_TIMEOUT = "ANALYSIS_API_TIMEOUT";
  public static final String ANALYSIS_API_RATE_LIMIT = "ANALYSIS_API_RATE_LIMIT";
  public static final String ANALYSIS_API_AUTH_FAILED = "ANALYSIS_API_AUTH_FAILED";
  public static final String ANALYSIS_PARSE_ERROR = "ANALYSIS_PARSE_ERROR";
  public static final String ANALYSIS_VALIDATION_FAILED = "ANALYSIS_VALIDATION_FAILED";

  // General failures
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
  public static final String INVALID_REQUEST = "INVALID_REQUEST";
  public static final String STATE_TRANSITION_FAILED = "STATE_TRANSITION_FAILED";

  private PatchLoopFailureCode() {
    // Prevent instantiation
  }
}

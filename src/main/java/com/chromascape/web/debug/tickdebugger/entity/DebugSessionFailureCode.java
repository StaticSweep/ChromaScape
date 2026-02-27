package com.chromascape.web.debug.tickdebugger.entity;

/**
 * Standardized failure codes for debug session failures.
 *
 * <p>These codes provide consistent categorization of failure reasons for troubleshooting.
 */
public final class DebugSessionFailureCode {

  /** Recording failed to start. */
  public static final String REC_START_FAILED = "REC_START_FAILED";

  /** Recording failed to stop cleanly. */
  public static final String REC_STOP_FAILED = "REC_STOP_FAILED";

  /** Recording metadata update failed. */
  public static final String REC_METADATA_UPDATE_FAILED = "REC_METADATA_UPDATE_FAILED";

  /** Session stop operation failed. */
  public static final String SESSION_STOP_FAILED = "SESSION_STOP_FAILED";

  /** Session finalization operation failed. */
  public static final String SESSION_FINALIZE_FAILED = "SESSION_FINALIZE_FAILED";

  private DebugSessionFailureCode() {
    // Utility class
  }
}

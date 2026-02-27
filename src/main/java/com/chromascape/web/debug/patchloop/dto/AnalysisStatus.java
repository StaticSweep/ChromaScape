package com.chromascape.web.debug.patchloop.dto;

/**
 * Status of an analysis operation.
 *
 * <p>Indicates whether the analysis completed successfully, partially, or failed.
 */
public enum AnalysisStatus {
  /** Analysis completed successfully with full diagnosis and recommendations */
  SUCCESS,

  /**
   * Analysis completed but with limitations (e.g., some evidence types missing, incomplete
   * diagnosis)
   */
  PARTIAL_SUCCESS,

  /** Analysis failed due to errors (API failure, parsing errors, validation failure) */
  FAILURE
}

package com.chromascape.web.debug.patchloop.constants;

/**
 * Error codes for Analysis V1 contract failures.
 *
 * <p>These codes are returned in {@link com.chromascape.web.debug.patchloop.dto.AnalysisV1Error}
 * when analysis fails.
 */
public final class AnalysisV1ErrorCode {

  // Validation errors (4xx-equivalent)
  public static final String INVALID_REQUEST = "INVALID_REQUEST";
  public static final String MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD";
  public static final String INVALID_TIME_WINDOW = "INVALID_TIME_WINDOW";
  public static final String INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";

  // API errors (5xx-equivalent)
  public static final String API_ERROR = "API_ERROR";
  public static final String API_TIMEOUT = "API_TIMEOUT";
  public static final String API_RATE_LIMIT = "API_RATE_LIMIT";
  public static final String API_AUTH_FAILED = "API_AUTH_FAILED";

  // Processing errors
  public static final String PARSE_ERROR = "PARSE_ERROR";
  public static final String EVIDENCE_COLLECTION_FAILED = "EVIDENCE_COLLECTION_FAILED";
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  private AnalysisV1ErrorCode() {
    // Prevent instantiation
  }
}

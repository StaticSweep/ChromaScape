package com.chromascape.web.debug.patchloop.constants;

/**
 * Error codes for Analysis V2 contract failures.
 *
 * <p>Extends V1 codes with V2-specific validation errors for schemaVersion, required fields, and
 * parse failures.
 *
 * @since 2.0.0
 */
public final class AnalysisV2ErrorCode {

  // ===== V1 Reuse =====
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

  // ===== V2-Specific =====
  /** Schema version is not "2.x.x" */
  public static final String INVALID_SCHEMA_VERSION = "INVALID_SCHEMA_VERSION";

  /** Response status is SUCCESS but missing required V2 fields (patchDraft, retestPlan, etc.) */
  public static final String MISSING_SUCCESS_FIELDS = "MISSING_SUCCESS_FIELDS";

  /** Response status is FAILURE but error field is missing */
  public static final String MISSING_ERROR_ON_FAILURE = "MISSING_ERROR_ON_FAILURE";

  /** Response structure validation failed */
  public static final String INVALID_RESPONSE_SCHEMA = "INVALID_RESPONSE_SCHEMA";

  private AnalysisV2ErrorCode() {
    // Prevent instantiation
  }
}

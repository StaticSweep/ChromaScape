package com.chromascape.web.debug.patchloop.exception;

/**
 * Exception thrown when analysis operations fail.
 *
 * <p>This exception wraps all errors that occur during analysis request validation, evidence
 * collection, API calls, and response parsing.
 */
public class AnalysisException extends Exception {

  private final String errorCode;

  public AnalysisException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public AnalysisException(String errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public String getErrorCode() {
    return errorCode;
  }

  @Override
  public String toString() {
    return "AnalysisException{"
        + "errorCode='"
        + errorCode
        + '\''
        + ", message='"
        + getMessage()
        + '\''
        + '}';
  }
}

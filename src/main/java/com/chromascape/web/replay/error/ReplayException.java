package com.chromascape.web.replay.error;

import org.springframework.http.HttpStatus;

/**
 * Structured replay exception. Never exposes raw exception messages, process output, paths, or SQL
 * errors to the browser.
 */
public class ReplayException extends RuntimeException {

  private final ReplayErrorCode errorCode;
  private final HttpStatus httpStatus;
  private final boolean retryable;
  private final String safeMessage;
  private final String remediation;
  private final String details;

  public ReplayException(
      ReplayErrorCode errorCode,
      HttpStatus httpStatus,
      boolean retryable,
      String safeMessage,
      String remediation) {
    this(errorCode, httpStatus, retryable, safeMessage, remediation, null, null);
  }

  public ReplayException(
      ReplayErrorCode errorCode,
      HttpStatus httpStatus,
      boolean retryable,
      String safeMessage,
      String remediation,
      String details,
      Throwable cause) {
    super(safeMessage, cause);
    this.errorCode = errorCode;
    this.httpStatus = httpStatus;
    this.retryable = retryable;
    this.safeMessage = safeMessage;
    this.remediation = remediation;
    this.details = details;
  }

  public ReplayErrorCode getErrorCode() {
    return errorCode;
  }

  public HttpStatus getHttpStatus() {
    return httpStatus;
  }

  public boolean isRetryable() {
    return retryable;
  }

  public String getSafeMessage() {
    return safeMessage;
  }

  public String getRemediation() {
    return remediation;
  }

  public String getDetails() {
    return details;
  }

  // --- Static factory methods ---

  public static ReplayException notFound(ReplayErrorCode code, String message) {
    return new ReplayException(code, HttpStatus.NOT_FOUND, false, message, null);
  }

  public static ReplayException conflict(ReplayErrorCode code, String message) {
    return new ReplayException(code, HttpStatus.CONFLICT, false, message, null);
  }

  public static ReplayException invalid(ReplayErrorCode code, String message) {
    return new ReplayException(code, HttpStatus.UNPROCESSABLE_ENTITY, false, message, null);
  }

  public static ReplayException unavailable(
      ReplayErrorCode code, String message, String remediation) {
    return new ReplayException(code, HttpStatus.SERVICE_UNAVAILABLE, true, message, remediation);
  }

  public static ReplayException badRequest(ReplayErrorCode code, String message) {
    return new ReplayException(code, HttpStatus.BAD_REQUEST, false, message, null);
  }
}

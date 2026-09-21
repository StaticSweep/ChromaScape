package com.chromascape.web.debug.buildergoal.recovery;

import org.springframework.http.HttpStatus;

/**
 * Typed compile recovery failure mapped to the stable error envelope. Messages are credential-free
 * and safe to render; correlation IDs are carried separately.
 */
public class RecoveryException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final boolean retryable;

  public RecoveryException(HttpStatus status, String code, String message, boolean retryable) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryable = retryable;
  }

  public static RecoveryException conflict(String code, String message) {
    return new RecoveryException(HttpStatus.CONFLICT, code, message, false);
  }

  public static RecoveryException unprocessable(String code, String message) {
    return new RecoveryException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, false);
  }

  public static RecoveryException notFound(String code, String message) {
    return new RecoveryException(HttpStatus.NOT_FOUND, code, message, false);
  }

  public static RecoveryException forbidden(String code, String message) {
    return new RecoveryException(HttpStatus.FORBIDDEN, code, message, false);
  }

  public static RecoveryException badRequest(String code, String message) {
    return new RecoveryException(HttpStatus.BAD_REQUEST, code, message, false);
  }

  public static RecoveryException unavailable(String code, String message) {
    return new RecoveryException(HttpStatus.SERVICE_UNAVAILABLE, code, message, true);
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}

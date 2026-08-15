package com.chromascape.web.accounttask.service;

/**
 * Raised when a verified report bundle conflicts with already-stored content for the same
 * run/attempt. Mapped to HTTP 409 by the controller, distinct from tampering/bad-request (400).
 */
public class StabilizationConflictException extends IllegalArgumentException {

  public StabilizationConflictException(String message) {
    super(message);
  }
}

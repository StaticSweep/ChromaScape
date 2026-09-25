package com.chromascape.web.debug.tickdebugger.service;

/**
 * Thrown when a session-start compensation (rollback) cannot complete, so a caller can detect that
 * retry ownership must be retained and exactly one guarded retry remains available.
 */
public class SessionStartRollbackException extends RuntimeException {

  public SessionStartRollbackException(String message) {
    super(message);
  }

  public SessionStartRollbackException(String message, Throwable cause) {
    super(message, cause);
  }
}

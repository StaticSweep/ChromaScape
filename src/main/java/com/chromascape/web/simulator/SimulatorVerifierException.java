package com.chromascape.web.simulator;

/**
 * Exception thrown when simulator verification operations fail.
 *
 * <p>Used for validation errors, missing dependencies, and execution failures.
 */
public class SimulatorVerifierException extends Exception {

  private final String userMessage;

  public SimulatorVerifierException(String message) {
    super(message);
    this.userMessage = message;
  }

  public SimulatorVerifierException(String message, Throwable cause) {
    super(message, cause);
    this.userMessage = message;
  }

  public SimulatorVerifierException(String message, String userMessage) {
    super(message);
    this.userMessage = userMessage;
  }

  /**
   * Gets a user-friendly message suitable for display in the UI.
   *
   * @return User-friendly error message
   */
  public String getUserMessage() {
    return userMessage;
  }
}

package com.chromascape.utils.core.runtime.exception;

/**
 * This error is thrown when the API key used to authenticate with the Dax API does not work.
 * Contact a developer to inquire about the service's availability and or a change of Public key.
 */
public class DaxAuthException extends DaxException {

  /** Constructs a new DaxAuthException with a default message. */
  public DaxAuthException() {
    super("Invalid credentials");
  }
}

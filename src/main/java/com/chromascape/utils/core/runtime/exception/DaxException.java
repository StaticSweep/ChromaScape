package com.chromascape.utils.core.runtime.exception;

/**
 * This is the parent of all the Dax API related exceptions. If this is the only error thrown, it
 * signifies that it was an unexpected error that is unaccounted for, contact a developer. If this
 * error is the parent of an error thrown, that error will contain more details.
 */
public class DaxException extends RuntimeException {

  /** Constructs a new DaxException with a default message. */
  public DaxException(String message) {
    super(message);
  }
}

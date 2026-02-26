package com.chromascape.utils.core.runtime;

/**
 * Exception used to indicate that a running script has been requested to stop.
 *
 * <p>This unchecked exception is thrown internally to signal that the script execution should be
 * terminated gracefully. It can be caught by the script runner to halt execution without treating
 * the stop as an error.
 *
 * <p>Typically, this exception is thrown by calling {@code stop()} methods in the script lifecycle
 * to immediately exit the current execution cycle.
 */
public class ScriptStoppedException extends RuntimeException {

  /** Constructs a new ScriptStoppedException with a default message. */
  public ScriptStoppedException() {
    super("Script stopped");
  }
}

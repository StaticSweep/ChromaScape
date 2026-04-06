package com.chromascape.web.config;

/**
 * Exception thrown when the MCP bridge configuration is invalid or incomplete.
 *
 * <p>This exception indicates a configuration problem such as missing required environment
 * variables, invalid directory paths, or unsupported provider configurations.
 */
public class BridgeConfigException extends RuntimeException {

  /**
   * Creates a new bridge configuration exception with the specified message.
   *
   * @param message the error message
   */
  public BridgeConfigException(String message) {
    super(message);
  }

  /**
   * Creates a new bridge configuration exception with the specified message and cause.
   *
   * @param message the error message
   * @param cause the underlying cause
   */
  public BridgeConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}

package com.chromascape.web.config;

/**
 * Exception thrown when the MCP bridge fails to start during application initialization.
 *
 * <p>This is a fatal exception that indicates the MCP bridge process could not be launched or did
 * not become healthy within the startup timeout period.
 */
public class BridgeStartupException extends RuntimeException {

  /**
   * Creates a new bridge startup exception with the specified message.
   *
   * @param message the error message
   */
  public BridgeStartupException(String message) {
    super(message);
  }

  /**
   * Creates a new bridge startup exception with the specified message and cause.
   *
   * @param message the error message
   * @param cause the underlying cause
   */
  public BridgeStartupException(String message, Throwable cause) {
    super(message, cause);
  }
}

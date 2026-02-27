package com.chromascape.web.debug.patchloop.service.provider.mcp;

/**
 * Exception thrown when MCP transport operations fail.
 *
 * <p>This exception wraps all errors that occur during HTTP communication with the MCP server,
 * including connection failures, timeouts, and invalid responses.
 */
public class McpTransportException extends Exception {

  private final McpErrorType errorType;
  private final boolean isRetryable;

  /**
   * Creates a new MCP transport exception.
   *
   * @param errorType the type of error that occurred
   * @param message the error message
   * @param isRetryable whether the error is retryable
   */
  public McpTransportException(McpErrorType errorType, String message, boolean isRetryable) {
    super(message);
    this.errorType = errorType;
    this.isRetryable = isRetryable;
  }

  /**
   * Creates a new MCP transport exception with a cause.
   *
   * @param errorType the type of error that occurred
   * @param message the error message
   * @param cause the underlying cause
   * @param isRetryable whether the error is retryable
   */
  public McpTransportException(
      McpErrorType errorType, String message, Throwable cause, boolean isRetryable) {
    super(message, cause);
    this.errorType = errorType;
    this.isRetryable = isRetryable;
  }

  /**
   * Returns the type of error that occurred.
   *
   * @return the error type
   */
  public McpErrorType getErrorType() {
    return errorType;
  }

  /**
   * Returns whether this error is retryable.
   *
   * @return true if the error is retryable
   */
  public boolean isRetryable() {
    return isRetryable;
  }

  @Override
  public String toString() {
    return "McpTransportException{"
        + "errorType="
        + errorType
        + ", isRetryable="
        + isRetryable
        + ", message='"
        + getMessage()
        + '\''
        + '}';
  }

  /** Types of MCP transport errors. */
  public enum McpErrorType {
    /** Connection could not be established (server down, wrong host, etc.). */
    CONNECTION_REFUSED,

    /** Connection timed out before server responded. */
    TIMEOUT,

    /** Server returned an invalid or unexpected response. */
    INVALID_RESPONSE,

    /** Server returned an error status code (4xx or 5xx). */
    HTTP_ERROR,

    /** Request body could not be serialized or response could not be parsed. */
    SERIALIZATION_ERROR,

    /** Configuration error (invalid URL, timeout, etc.). */
    CONFIGURATION_ERROR,

    /** Unknown or uncategorized error. */
    UNKNOWN
  }
}

package com.chromascape.utils.core.runtime.exception;

/**
 * This exception is thrown when the Publicly available endpoint for the Dax API is overloaded. When
 * the service exceeds a certain threshold in a given timeframe, they stop taking more requests. The
 * user experiencing this exception should implement a fallback to wait for the API, or do something
 * else.
 */
public class DaxRateLimitException extends DaxException {

  /** Constructs a new DaxRateLimitException with a default message. */
  public DaxRateLimitException() {
    super("Rate limit exceeded");
  }
}

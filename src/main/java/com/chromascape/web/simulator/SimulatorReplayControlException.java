package com.chromascape.web.simulator;

/**
 * Exception thrown when the replay control proxy encounters an error communicating with the
 * OpenRune ReplayControlService.
 *
 * <p>When the upstream server returns a non-2xx HTTP response, the status code is preserved in
 * {@link #getUpstreamStatus()} so the controller can propagate it instead of collapsing to 502.
 */
public class SimulatorReplayControlException extends RuntimeException {

  private final Integer upstreamStatus;

  public SimulatorReplayControlException(String message) {
    super(message);
    this.upstreamStatus = null;
  }

  public SimulatorReplayControlException(String message, Throwable cause) {
    super(message, cause);
    this.upstreamStatus = null;
  }

  public SimulatorReplayControlException(String message, int upstreamStatus) {
    super(message);
    this.upstreamStatus = upstreamStatus;
  }

  /**
   * Returns the upstream HTTP status code if this exception was caused by an upstream non-2xx
   * response, or {@code null} for transport/parse failures.
   */
  public Integer getUpstreamStatus() {
    return upstreamStatus;
  }
}

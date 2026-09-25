package com.chromascape.web.recording.exception;

/** Exception thrown when failing to capture the initial frame from the game window. */
public class FrameCaptureException extends RecordingException {
  /**
   * Constructs a new FrameCaptureException.
   *
   * @param maxAttempts The number of capture attempts that were made
   */
  public FrameCaptureException(int maxAttempts) {
    super(
        "Failed to capture frame after " + maxAttempts + " attempts",
        "Failed to capture the game window. Please ensure the game window is visible and not minimized, and that the correct window is selected, then try again.");
  }

  /**
   * Constructs a new FrameCaptureException with additional detail.
   *
   * @param maxAttempts The number of capture attempts that were made
   * @param detail The last error detail encountered during capture
   */
  public FrameCaptureException(int maxAttempts, String detail) {
    super(
        "Failed to capture frame after "
            + maxAttempts
            + " attempts"
            + (detail == null || detail.isBlank() ? "" : ": " + detail),
        "Failed to capture the game window. "
            + (detail == null || detail.isBlank()
                ? "Please ensure the game window is visible and not minimized, and that the correct window is selected, then try again."
                : detail));
  }

  /**
   * Constructs a new FrameCaptureException describing a bounded readiness-wait outcome.
   *
   * @param attempts number of capture attempts made
   * @param timeoutMs configured readiness timeout
   * @param elapsedMs actual elapsed time when the deadline expired
   * @param lastError the last capture error detail, or {@code null}
   * @param interrupted whether startup was aborted because the thread was interrupted
   */
  public FrameCaptureException(
      int attempts, long timeoutMs, long elapsedMs, String lastError, boolean interrupted) {
    super(
        buildTechnicalMessage(attempts, timeoutMs, elapsedMs, lastError, interrupted),
        interrupted
            ? "Recording startup was cancelled before a game frame could be captured."
            : "The RuneLite game window was not capturable before the deadline. Ensure it is visible, restored, and correctly selected, then try again.");
  }

  private static String buildTechnicalMessage(
      int attempts, long timeoutMs, long elapsedMs, String lastError, boolean interrupted) {
    String cause = lastError == null || lastError.isBlank() ? "no frame captured" : lastError;
    if (interrupted) {
      return String.format(
          "Failed to capture initial frame: interrupted after %d attempts over %d ms",
          attempts, elapsedMs);
    }
    return String.format(
        "Failed to capture initial frame after %d attempts over %d ms: %s",
        attempts, timeoutMs, cause);
  }
}

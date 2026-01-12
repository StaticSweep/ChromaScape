package com.chromascape.web.recording.exception;

/**
 * Exception thrown when failing to capture the initial frame from the game window.
 */
public class FrameCaptureException extends RecordingException {
  /**
   * Constructs a new FrameCaptureException.
   *
   * @param maxAttempts The number of capture attempts that were made
   */
  public FrameCaptureException(int maxAttempts) {
    super(
        "Failed to capture frame after " + maxAttempts + " attempts",
        "Failed to capture the game window. Please ensure the game window is visible and not minimized, then try again.");
  }
}

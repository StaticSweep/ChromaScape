package com.chromascape.web.recording.exception;

/**
 * Base exception class for recording-related errors. Provides both technical messages for logging
 * and user-friendly messages for display.
 */
public class RecordingException extends RuntimeException {
  private final String userMessage;

  /**
   * Constructs a new RecordingException.
   *
   * @param message Technical message for logging
   * @param userMessage User-friendly message for display
   */
  public RecordingException(String message, String userMessage) {
    super(message);
    this.userMessage = userMessage;
  }

  /**
   * Gets the user-friendly error message.
   *
   * @return User-friendly error message
   */
  public String getUserMessage() {
    return userMessage;
  }
}

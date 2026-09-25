package com.chromascape.web.recording.exception;

/** Exception thrown when attempting to start a recording while one is already in progress. */
public class RecordingAlreadyInProgressException extends RecordingException {
  /**
   * Constructs a new RecordingAlreadyInProgressException.
   *
   * @param recordingId The ID of the recording that is currently in progress
   */
  public RecordingAlreadyInProgressException(String recordingId) {
    super(
        "Recording already in progress: " + recordingId,
        "A recording is already in progress. Please stop the current recording before starting a new one.");
  }
}

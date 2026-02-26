package com.chromascape.web.recording.exception;

/**
 * Exception thrown when attempting to start a recording while recording is disabled in
 * configuration.
 */
public class RecordingDisabledException extends RecordingException {
  /** Constructs a new RecordingDisabledException. */
  public RecordingDisabledException() {
    super(
        "Recording is disabled in configuration",
        "Recording is currently disabled. Please enable it in application settings (recording.enabled=true).");
  }
}

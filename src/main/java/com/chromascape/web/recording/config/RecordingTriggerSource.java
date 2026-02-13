package com.chromascape.web.recording.config;

/** Describes what triggered the current (or most recent) recording session. */
public enum RecordingTriggerSource {
  /** Recording started manually via the UI or REST API. */
  MANUAL,
  /** Recording started automatically by task-detection logic. */
  TASK_AUTO,
  /** Recording started because Micro6 sent {@code recordingEnabled=true} in SESSION_START. */
  MICRO6_AUTO
}

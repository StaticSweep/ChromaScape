package com.chromascape.web.recording.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Additive typed manual recording body; supplied hashes are never authoritative. */
public record RecordingStartRequest(
    String accountName,
    String scriptName,
    String windowTitle,
    String taskKey,
    JsonNode taskArgs,
    JsonNode runContext) {
  public RecordingStartRequest(
      String accountName,
      String scriptName,
      String windowTitle,
      String taskKey,
      JsonNode taskArgs) {
    this(accountName, scriptName, windowTitle, taskKey, taskArgs, null);
  }
}

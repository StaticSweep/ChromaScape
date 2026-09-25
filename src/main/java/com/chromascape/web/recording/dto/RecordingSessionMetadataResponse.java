package com.chromascape.web.recording.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Response DTO for recording session metadata operations. */
public class RecordingSessionMetadataResponse {

  private final String recordingId;
  private final String replayName;
  private final List<String> replayTags;
  private final String displayName;
  private final LocalDateTime updatedAt;

  /** Construct a metadata response. */
  public RecordingSessionMetadataResponse(
      String recordingId,
      String replayName,
      List<String> replayTags,
      String displayName,
      LocalDateTime updatedAt) {
    this.recordingId = recordingId;
    this.replayName = replayName;
    this.replayTags = replayTags;
    this.displayName = displayName;
    this.updatedAt = updatedAt;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public String getReplayName() {
    return replayName;
  }

  public List<String> getReplayTags() {
    return replayTags;
  }

  public String getDisplayName() {
    return displayName;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}

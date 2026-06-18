package com.chromascape.web.recording.dto;

import java.util.List;

/** Request DTO for updating recording session replay metadata. */
public class RecordingSessionMetadataUpdateRequest {

  /** Custom replay name (nullable; blank clears). */
  private String replayName;

  /** Tags list (nullable; omitted keeps existing, provided replaces). */
  private List<String> replayTags;

  /** Whether replayName was explicitly provided in the request. */
  private boolean replayNameSet;

  /** Whether replayTags was explicitly provided in the request. */
  private boolean replayTagsSet;

  public String getReplayName() {
    return replayName;
  }

  public void setReplayName(String replayName) {
    this.replayName = replayName;
    this.replayNameSet = true;
  }

  public List<String> getReplayTags() {
    return replayTags;
  }

  public void setReplayTags(List<String> replayTags) {
    this.replayTags = replayTags;
    this.replayTagsSet = true;
  }

  public boolean isReplayNameSet() {
    return replayNameSet;
  }

  public boolean isReplayTagsSet() {
    return replayTagsSet;
  }
}

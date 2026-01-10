package com.chromascape.web.debug.tickdebugger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response DTO for session start requests. */
public class SessionStartResponse {

  @JsonProperty("sessionId")
  private String sessionId;

  @JsonProperty("recordingEnabled")
  private Boolean recordingEnabled;

  @JsonProperty("recordingId")
  private String recordingId;

  @JsonProperty("startTick")
  private Long startTick;

  public SessionStartResponse() {}

  public SessionStartResponse(
      String sessionId, Boolean recordingEnabled, String recordingId, Long startTick) {
    this.sessionId = sessionId;
    this.recordingEnabled = recordingEnabled;
    this.recordingId = recordingId;
    this.startTick = startTick;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Boolean getRecordingEnabled() {
    return recordingEnabled;
  }

  public void setRecordingEnabled(Boolean recordingEnabled) {
    this.recordingEnabled = recordingEnabled;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
  }

  public Long getStartTick() {
    return startTick;
  }

  public void setStartTick(Long startTick) {
    this.startTick = startTick;
  }
}

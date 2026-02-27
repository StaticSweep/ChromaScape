package com.chromascape.web.debug.tickdebugger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for session start requests.
 *
 * <p>Note: {@code recordingEnabled} can be {@code false} for two reasons:
 *
 * <ul>
 *   <li>Recording was not requested by the client
 *   <li>Recording was requested but failed to start (check session lifecycle via {@code
 *       /api/debug/replay/session-info/{sessionId}} for FAILED state)
 * </ul>
 */
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

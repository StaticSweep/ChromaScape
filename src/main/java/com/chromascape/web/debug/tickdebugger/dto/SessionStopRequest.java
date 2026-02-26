package com.chromascape.web.debug.tickdebugger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request DTO for stopping a debug session. */
public class SessionStopRequest {

  @JsonProperty("sessionId")
  private String sessionId;

  @JsonProperty("endTick")
  private Long endTick;

  public SessionStopRequest() {}

  public SessionStopRequest(String sessionId, Long endTick) {
    this.sessionId = sessionId;
    this.endTick = endTick;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Long getEndTick() {
    return endTick;
  }

  public void setEndTick(Long endTick) {
    this.endTick = endTick;
  }
}

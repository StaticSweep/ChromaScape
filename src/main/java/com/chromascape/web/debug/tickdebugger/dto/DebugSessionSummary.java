package com.chromascape.web.debug.tickdebugger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/** Summary DTO for debug sessions in list responses. */
public class DebugSessionSummary {

  @JsonProperty("sessionId")
  private String sessionId;

  @JsonProperty("accountName")
  private String accountName;

  @JsonProperty("scriptName")
  private String scriptName;

  @JsonProperty("startTime")
  private LocalDateTime startTime;

  @JsonProperty("endTime")
  private LocalDateTime endTime;

  @JsonProperty("startTick")
  private Long startTick;

  @JsonProperty("endTick")
  private Long endTick;

  @JsonProperty("recordingId")
  private String recordingId;

  @JsonProperty("isActive")
  private Boolean isActive;

  @JsonProperty("tickCount")
  private Long tickCount;

  public DebugSessionSummary() {}

  public DebugSessionSummary(
      String sessionId,
      String accountName,
      String scriptName,
      LocalDateTime startTime,
      LocalDateTime endTime,
      Long startTick,
      Long endTick,
      String recordingId,
      Boolean isActive,
      Long tickCount) {
    this.sessionId = sessionId;
    this.accountName = accountName;
    this.scriptName = scriptName;
    this.startTime = startTime;
    this.endTime = endTime;
    this.startTick = startTick;
    this.endTick = endTick;
    this.recordingId = recordingId;
    this.isActive = isActive;
    this.tickCount = tickCount;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getAccountName() {
    return accountName;
  }

  public void setAccountName(String accountName) {
    this.accountName = accountName;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public LocalDateTime getStartTime() {
    return startTime;
  }

  public void setStartTime(LocalDateTime startTime) {
    this.startTime = startTime;
  }

  public LocalDateTime getEndTime() {
    return endTime;
  }

  public void setEndTime(LocalDateTime endTime) {
    this.endTime = endTime;
  }

  public Long getStartTick() {
    return startTick;
  }

  public void setStartTick(Long startTick) {
    this.startTick = startTick;
  }

  public Long getEndTick() {
    return endTick;
  }

  public void setEndTick(Long endTick) {
    this.endTick = endTick;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
  }

  public Boolean getIsActive() {
    return isActive;
  }

  public void setIsActive(Boolean isActive) {
    this.isActive = isActive;
  }

  public Long getTickCount() {
    return tickCount;
  }

  public void setTickCount(Long tickCount) {
    this.tickCount = tickCount;
  }
}

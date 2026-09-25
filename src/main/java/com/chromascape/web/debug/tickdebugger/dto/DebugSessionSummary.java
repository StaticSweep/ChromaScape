package com.chromascape.web.debug.tickdebugger.dto;

import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
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

  @JsonProperty("eventCount")
  private Long eventCount;

  @JsonProperty("failureMarkerCount")
  private Long failureMarkerCount;

  @JsonProperty("hasEvents")
  private Boolean hasEvents;

  @JsonProperty("lifecycleState")
  private String lifecycleState;

  @JsonProperty("failureCode")
  private String failureCode;

  @JsonProperty("failureReason")
  private String failureReason;

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
      Long tickCount,
      Long eventCount,
      Long failureMarkerCount,
      Boolean hasEvents,
      DebugSessionLifecycleState lifecycleState,
      String failureCode,
      String failureReason) {
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
    this.eventCount = eventCount;
    this.failureMarkerCount = failureMarkerCount;
    this.hasEvents = hasEvents;
    this.lifecycleState = lifecycleState != null ? lifecycleState.name() : null;
    this.failureCode = failureCode;
    this.failureReason = failureReason;
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

  public Long getEventCount() {
    return eventCount;
  }

  public void setEventCount(Long eventCount) {
    this.eventCount = eventCount;
  }

  public Long getFailureMarkerCount() {
    return failureMarkerCount;
  }

  public void setFailureMarkerCount(Long failureMarkerCount) {
    this.failureMarkerCount = failureMarkerCount;
  }

  public Boolean getHasEvents() {
    return hasEvents;
  }

  public void setHasEvents(Boolean hasEvents) {
    this.hasEvents = hasEvents;
  }

  @JsonProperty("hasFailureMarkers")
  public Boolean getHasFailureMarkers() {
    return failureMarkerCount != null && failureMarkerCount > 0;
  }

  public String getLifecycleState() {
    return lifecycleState;
  }

  public void setLifecycleState(String lifecycleState) {
    this.lifecycleState = lifecycleState;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public void setFailureCode(String failureCode) {
    this.failureCode = failureCode;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public void setFailureReason(String failureReason) {
    this.failureReason = failureReason;
  }

  @JsonProperty("hasFailure")
  public Boolean getHasFailure() {
    return failureCode != null && !failureCode.isEmpty();
  }
}

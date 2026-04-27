package com.chromascape.web.simulator.dto;

import java.time.LocalDateTime;

/**
 * A single entry in the replay catalog listing available recordings with tick data.
 *
 * <p>Each entry represents a recording that has associated tick snapshots that can be replayed on
 * the OpenRune simulator.
 */
public class ReplayCatalogEntry {

  private String recordingId;
  private String sessionId;
  private String accountName;
  private String scriptName;
  private LocalDateTime startTime;
  private LocalDateTime endTime;
  private Long startTick;
  private Long endTick;
  private long tickCount;

  public ReplayCatalogEntry() {}

  public ReplayCatalogEntry(
      String recordingId,
      String sessionId,
      String accountName,
      String scriptName,
      LocalDateTime startTime,
      LocalDateTime endTime,
      Long startTick,
      Long endTick,
      long tickCount) {
    this.recordingId = recordingId;
    this.sessionId = sessionId;
    this.accountName = accountName;
    this.scriptName = scriptName;
    this.startTime = startTime;
    this.endTime = endTime;
    this.startTick = startTick;
    this.endTick = endTick;
    this.tickCount = tickCount;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
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

  public long getTickCount() {
    return tickCount;
  }

  public void setTickCount(long tickCount) {
    this.tickCount = tickCount;
  }
}

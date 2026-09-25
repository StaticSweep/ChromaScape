package com.chromascape.web.replay.playback.dto;

import java.time.LocalDateTime;

/** A recording with captured tick evidence available to the observed playback UI. */
public class ReplayPlaybackCatalogEntry {

  private String recordingId;
  private String sessionId;
  private String accountName;
  private String scriptName;
  private LocalDateTime startTime;
  private LocalDateTime endTime;
  private Long startTick;
  private Long endTick;
  private long tickCount;
  private long positionFrameCount;
  private long logCount;
  private double positionCoveragePct;
  private boolean replayable;
  private String replayabilityReason;

  public ReplayPlaybackCatalogEntry() {}

  public ReplayPlaybackCatalogEntry(
      String recordingId,
      String sessionId,
      String accountName,
      String scriptName,
      LocalDateTime startTime,
      LocalDateTime endTime,
      Long startTick,
      Long endTick,
      long tickCount,
      long positionFrameCount,
      long logCount,
      double positionCoveragePct,
      boolean replayable,
      String replayabilityReason) {
    this.recordingId = recordingId;
    this.sessionId = sessionId;
    this.accountName = accountName;
    this.scriptName = scriptName;
    this.startTime = startTime;
    this.endTime = endTime;
    this.startTick = startTick;
    this.endTick = endTick;
    this.tickCount = tickCount;
    this.positionFrameCount = positionFrameCount;
    this.logCount = logCount;
    this.positionCoveragePct = positionCoveragePct;
    this.replayable = replayable;
    this.replayabilityReason = replayabilityReason;
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

  public long getPositionFrameCount() {
    return positionFrameCount;
  }

  public void setPositionFrameCount(long positionFrameCount) {
    this.positionFrameCount = positionFrameCount;
  }

  public long getLogCount() {
    return logCount;
  }

  public void setLogCount(long logCount) {
    this.logCount = logCount;
  }

  public double getPositionCoveragePct() {
    return positionCoveragePct;
  }

  public void setPositionCoveragePct(double positionCoveragePct) {
    this.positionCoveragePct = positionCoveragePct;
  }

  public boolean isReplayable() {
    return replayable;
  }

  public void setReplayable(boolean replayable) {
    this.replayable = replayable;
  }

  public String getReplayabilityReason() {
    return replayabilityReason;
  }

  public void setReplayabilityReason(String replayabilityReason) {
    this.replayabilityReason = replayabilityReason;
  }
}

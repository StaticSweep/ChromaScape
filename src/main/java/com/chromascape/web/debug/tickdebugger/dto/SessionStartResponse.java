package com.chromascape.web.debug.tickdebugger.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for session start requests.
 *
 * <p>Note: {@code recordingEnabled} can be {@code false} for two reasons:
 *
 * <ul>
 *   <li>Recording was not requested by the client
 *   <li>Recording was requested but failed to start, and the session degraded to tick-only mode
 *       (see {@code recordingFailureReason}; the session stays in RECORDING with failureCode {@code
 *       REC_START_FAILED})
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

  @JsonProperty("recordingFailureReason")
  private String recordingFailureReason;

  @JsonProperty("clientRequestId")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String clientRequestId;

  public SessionStartResponse() {}

  public SessionStartResponse(
      String sessionId, Boolean recordingEnabled, String recordingId, Long startTick) {
    this(sessionId, recordingEnabled, recordingId, startTick, null);
  }

  public SessionStartResponse(
      String sessionId,
      Boolean recordingEnabled,
      String recordingId,
      Long startTick,
      String recordingFailureReason) {
    this.sessionId = sessionId;
    this.recordingEnabled = recordingEnabled;
    this.recordingId = recordingId;
    this.startTick = startTick;
    this.recordingFailureReason = recordingFailureReason;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getClientRequestId() {
    return clientRequestId;
  }

  public void setClientRequestId(String clientRequestId) {
    this.clientRequestId = clientRequestId;
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

  public String getRecordingFailureReason() {
    return recordingFailureReason;
  }

  public void setRecordingFailureReason(String recordingFailureReason) {
    this.recordingFailureReason = recordingFailureReason;
  }
}

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

  /**
   * Shadow run id opened for this session binding. Returned for observability and correlation only;
   * Micro6 never routes ticks through it, ChromaScape derives routing from the connection binding.
   */
  @JsonProperty("shadowRunId")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String shadowRunId;

  @JsonProperty("clientRequestId")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String clientRequestId;

  private Integer selectedTickBatchVersion;
  private Integer selectedAckVersion;
  private com.fasterxml.jackson.databind.JsonNode runContext;

  public Integer getSelectedTickBatchVersion() {
    return selectedTickBatchVersion;
  }

  public void setSelectedTickBatchVersion(Integer v) {
    selectedTickBatchVersion = v;
  }

  public Integer getSelectedAckVersion() {
    return selectedAckVersion;
  }

  public void setSelectedAckVersion(Integer v) {
    selectedAckVersion = v;
  }

  public com.fasterxml.jackson.databind.JsonNode getRunContext() {
    return runContext;
  }

  public void setRunContext(com.fasterxml.jackson.databind.JsonNode v) {
    runContext = v;
  }

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
    this(sessionId, recordingEnabled, recordingId, startTick, recordingFailureReason, null);
  }

  /**
   * Full constructor accepting the transport correlation id directly so handlers can build a
   * response without mutating a DTO after construction.
   *
   * @param sessionId the authoritative debug session id
   * @param recordingEnabled whether recording is active
   * @param recordingId the recording id, or null for a degraded (tick-only) session
   * @param startTick the session start tick
   * @param recordingFailureReason the degradation reason for a tick-only session, or null
   * @param clientRequestId the echoed transport correlation id, or null
   */
  public SessionStartResponse(
      String sessionId,
      Boolean recordingEnabled,
      String recordingId,
      Long startTick,
      String recordingFailureReason,
      String clientRequestId) {
    this.sessionId = sessionId;
    this.recordingEnabled = recordingEnabled;
    this.recordingId = recordingId;
    this.startTick = startTick;
    this.recordingFailureReason = recordingFailureReason;
    this.clientRequestId = clientRequestId;
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

  public String getShadowRunId() {
    return shadowRunId;
  }

  public void setShadowRunId(String shadowRunId) {
    this.shadowRunId = shadowRunId;
  }
}

package com.chromascape.web.debug.tickdebugger.dto;

import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Request DTO for starting a debug session. */
public class SessionStartRequest {

  /**
   * Transport-level correlation id chosen by the Micro6 client for this specific SESSION_START
   * request. ChromaScape echoes it unchanged in success, degraded-success, and {@code SESSION_START
   * failed} responses. It is correlation only and is never persisted as the authoritative debug
   * session id. Older clients omit it; such requests continue to work.
   */
  @JsonProperty("clientRequestId")
  private String clientRequestId;

  @JsonProperty("accountName")
  private String accountName;

  @JsonProperty("scriptName")
  private String scriptName;

  @JsonProperty("recordingEnabled")
  private Boolean recordingEnabled;

  @JsonProperty("triggerSource")
  private RecordingTriggerSource triggerSource;

  /**
   * Canonical field for the reserved debug session id to bind this client restart to. The legacy
   * {@code sessionId} JSON name is accepted as an alias for backward compatibility with older
   * Micro6 clients; the canonical {@code preferredSessionId} always wins when both are present.
   */
  @JsonProperty("preferredSessionId")
  private String preferredSessionId;

  private java.util.List<Integer> supportedTickBatchVersions;
  private java.util.List<Integer> supportedAckVersions;

  public java.util.List<Integer> getSupportedTickBatchVersions() {
    return supportedTickBatchVersions;
  }

  public void setSupportedTickBatchVersions(java.util.List<Integer> v) {
    supportedTickBatchVersions = v;
  }

  public java.util.List<Integer> getSupportedAckVersions() {
    return supportedAckVersions;
  }

  public void setSupportedAckVersions(java.util.List<Integer> v) {
    supportedAckVersions = v;
  }

  private String taskKey;
  private JsonNode taskArgs;
  private JsonNode runContext;
  private JsonNode captureIdentity;

  public JsonNode getCaptureIdentity() {
    return captureIdentity;
  }

  public void setCaptureIdentity(JsonNode value) {
    captureIdentity = value;
  }

  public String getTaskKey() {
    return taskKey;
  }

  public void setTaskKey(String value) {
    taskKey = value;
  }

  public JsonNode getTaskArgs() {
    return taskArgs;
  }

  public void setTaskArgs(JsonNode value) {
    taskArgs = value;
  }

  public JsonNode getRunContext() {
    return runContext;
  }

  public void setRunContext(JsonNode value) {
    runContext = value;
  }

  public SessionStartRequest() {}

  public SessionStartRequest(
      String accountName,
      String scriptName,
      Boolean recordingEnabled,
      RecordingTriggerSource triggerSource) {
    this.accountName = accountName;
    this.scriptName = scriptName;
    this.recordingEnabled = recordingEnabled;
    this.triggerSource = triggerSource;
  }

  public SessionStartRequest(
      String accountName,
      String scriptName,
      Boolean recordingEnabled,
      RecordingTriggerSource triggerSource,
      String preferredSessionId) {
    this.accountName = accountName;
    this.scriptName = scriptName;
    this.recordingEnabled = recordingEnabled;
    this.triggerSource = triggerSource;
    this.preferredSessionId = preferredSessionId;
  }

  public SessionStartRequest(
      String accountName,
      String scriptName,
      Boolean recordingEnabled,
      RecordingTriggerSource triggerSource,
      String preferredSessionId,
      String clientRequestId) {
    this.accountName = accountName;
    this.scriptName = scriptName;
    this.recordingEnabled = recordingEnabled;
    this.triggerSource = triggerSource;
    this.preferredSessionId = preferredSessionId;
    this.clientRequestId = clientRequestId;
  }

  public String getClientRequestId() {
    return clientRequestId;
  }

  public void setClientRequestId(String clientRequestId) {
    this.clientRequestId = clientRequestId;
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

  public Boolean getRecordingEnabled() {
    return recordingEnabled != null ? recordingEnabled : false;
  }

  public void setRecordingEnabled(Boolean recordingEnabled) {
    this.recordingEnabled = recordingEnabled;
  }

  public RecordingTriggerSource getTriggerSource() {
    return triggerSource != null ? triggerSource : RecordingTriggerSource.MICRO6_AUTO;
  }

  public void setTriggerSource(RecordingTriggerSource triggerSource) {
    this.triggerSource = triggerSource;
  }

  /**
   * Optional reserved debug session id to bind this client restart to.
   *
   * <p>When provided and the session is still active, the recording and tick ingestion reuse that
   * session so run-to-session continuity is preserved across a patch relaunch.
   */
  public String getPreferredSessionId() {
    return preferredSessionId;
  }

  public void setPreferredSessionId(String preferredSessionId) {
    this.preferredSessionId = preferredSessionId;
  }

  /**
   * Legacy {@code sessionId} JSON binding for older Micro6 clients. Accepted only when the
   * canonical {@code preferredSessionId} was not provided, so the canonical field always wins
   * regardless of JSON field order.
   */
  @JsonProperty("sessionId")
  public void setLegacySessionId(String sessionId) {
    if (preferredSessionId == null) {
      preferredSessionId = sessionId;
    }
  }
}

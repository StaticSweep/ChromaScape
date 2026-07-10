package com.chromascape.web.debug.tickdebugger.dto;

import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Request DTO for starting a debug session. */
public class SessionStartRequest {

  @JsonProperty("accountName")
  private String accountName;

  @JsonProperty("scriptName")
  private String scriptName;

  @JsonProperty("recordingEnabled")
  private Boolean recordingEnabled;

  @JsonProperty("triggerSource")
  private RecordingTriggerSource triggerSource;

  @JsonProperty("preferredSessionId")
  private String preferredSessionId;

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
}

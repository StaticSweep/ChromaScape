package com.chromascape.web.debug.tickdebugger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request DTO for starting a debug session. */
public class SessionStartRequest {

  @JsonProperty("accountName")
  private String accountName;

  @JsonProperty("scriptName")
  private String scriptName;

  @JsonProperty("recordingEnabled")
  private Boolean recordingEnabled;

  public SessionStartRequest() {}

  public SessionStartRequest(String accountName, String scriptName, Boolean recordingEnabled) {
    this.accountName = accountName;
    this.scriptName = scriptName;
    this.recordingEnabled = recordingEnabled;
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
}

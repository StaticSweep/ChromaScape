package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Response DTO for patch loop events.
 *
 * <p>Represents a single event in an iteration's lifecycle.
 */
public class PatchLoopEventResponse {

  @JsonProperty("eventType")
  private String eventType;

  @JsonProperty("timestamp")
  private LocalDateTime timestamp;

  @JsonProperty("message")
  private String message;

  @JsonProperty("severity")
  private String severity;

  @JsonProperty("data")
  private Map<String, Object> data;

  public PatchLoopEventResponse() {
    this.data = new HashMap<>();
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  public Map<String, Object> getData() {
    return data;
  }

  public void setData(Map<String, Object> data) {
    this.data = data;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchLoopEventResponse that = (PatchLoopEventResponse) o;
    return Objects.equals(timestamp, that.timestamp)
        && Objects.equals(eventType, that.eventType)
        && Objects.equals(message, that.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(timestamp, eventType, message);
  }

  @Override
  public String toString() {
    return "PatchLoopEventResponse{"
        + "eventType='"
        + eventType
        + '\''
        + ", timestamp="
        + timestamp
        + ", message='"
        + message
        + '\''
        + ", severity='"
        + severity
        + '\''
        + '}';
  }
}

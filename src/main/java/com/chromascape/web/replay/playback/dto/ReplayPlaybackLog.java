package com.chromascape.web.replay.playback.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Stable v1 representation of a tick-correlated log attached to a captured frame. */
public class ReplayPlaybackLog {

  private Long id;
  private Long tick;
  private LocalDateTime timestamp;
  private String subsystem;
  private String severity;
  private String eventType;
  private String message;
  private JsonNode details;
  private UUID correlationId;
  private List<String> categories;

  public ReplayPlaybackLog() {}

  public ReplayPlaybackLog(
      Long id,
      Long tick,
      LocalDateTime timestamp,
      String subsystem,
      String severity,
      String eventType,
      String message,
      JsonNode details,
      UUID correlationId,
      List<String> categories) {
    this.id = id;
    this.tick = tick;
    this.timestamp = timestamp;
    this.subsystem = subsystem;
    this.severity = severity;
    this.eventType = eventType;
    this.message = message;
    this.details = details;
    this.correlationId = correlationId;
    this.categories = categories;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getTick() {
    return tick;
  }

  public void setTick(Long tick) {
    this.tick = tick;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getSubsystem() {
    return subsystem;
  }

  public void setSubsystem(String subsystem) {
    this.subsystem = subsystem;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public JsonNode getDetails() {
    return details;
  }

  public void setDetails(JsonNode details) {
    this.details = details;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(UUID correlationId) {
    this.correlationId = correlationId;
  }

  public List<String> getCategories() {
    return categories;
  }

  public void setCategories(List<String> categories) {
    this.categories = categories;
  }
}

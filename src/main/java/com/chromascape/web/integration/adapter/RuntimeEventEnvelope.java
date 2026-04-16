package com.chromascape.web.integration.adapter;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Canonical event envelope published by runtime adapters.
 *
 * <p>All adapters emit events through this envelope so that downstream consumers (task synthesis,
 * dashboard, etc.) can process events uniformly regardless of source.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuntimeEventEnvelope {

  private final String eventId;
  private final String adapterId;
  private final String eventType;
  private final LocalDateTime timestamp;
  private final Map<String, Object> payload;

  public RuntimeEventEnvelope(
      String eventId,
      String adapterId,
      String eventType,
      LocalDateTime timestamp,
      Map<String, Object> payload) {
    this.eventId = eventId;
    this.adapterId = adapterId;
    this.eventType = eventType;
    this.timestamp = timestamp;
    this.payload = payload;
  }

  public String getEventId() {
    return eventId;
  }

  public String getAdapterId() {
    return adapterId;
  }

  public String getEventType() {
    return eventType;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public Map<String, Object> getPayload() {
    return payload;
  }
}

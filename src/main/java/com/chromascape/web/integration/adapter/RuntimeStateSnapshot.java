package com.chromascape.web.integration.adapter;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Immutable snapshot of the bot runtime state at a point in time.
 *
 * <p>Carries position, health, inventory summary, current task, and arbitrary metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuntimeStateSnapshot {

  private final String adapterId;
  private final LocalDateTime capturedAt;
  private final String botState;
  private final String currentScript;
  private final Map<String, Object> position;
  private final Map<String, Object> metadata;

  public RuntimeStateSnapshot(
      String adapterId,
      LocalDateTime capturedAt,
      String botState,
      String currentScript,
      Map<String, Object> position,
      Map<String, Object> metadata) {
    this.adapterId = adapterId;
    this.capturedAt = capturedAt;
    this.botState = botState;
    this.currentScript = currentScript;
    this.position = position;
    this.metadata = metadata;
  }

  public String getAdapterId() {
    return adapterId;
  }

  public LocalDateTime getCapturedAt() {
    return capturedAt;
  }

  public String getBotState() {
    return botState;
  }

  public String getCurrentScript() {
    return currentScript;
  }

  public Map<String, Object> getPosition() {
    return position;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }
}

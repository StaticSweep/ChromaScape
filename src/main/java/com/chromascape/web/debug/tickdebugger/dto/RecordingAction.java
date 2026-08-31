package com.chromascape.web.debug.tickdebugger.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned semantic action captured at the client dispatch boundary.
 *
 * <p>This is intentionally separate from legacy {@code PLAYER_ACTION}/{@code MENU_CLICK} logs.
 * The legacy records remain useful for diagnostics, but only this envelope is an authoritative
 * replay input when every field is captured by Micro6.
 */
public class RecordingAction {

  public static final String SCHEMA = "recording-action/v1";
  public static final String SOURCE_MICRO6_SEMANTIC_DISPATCH = "MICRO6_SEMANTIC_DISPATCH";

  private String schema;
  private String sessionId;
  private long tick;
  private int sequence;
  private String actionType;
  private Map<String, Object> target;
  private Map<String, Object> arguments;
  private String source;
  private BigDecimal confidence;
  private String capturedAt;

  public RecordingAction() {
    this.schema = SCHEMA;
    this.target = new LinkedHashMap<>();
    this.arguments = new LinkedHashMap<>();
    this.source = SOURCE_MICRO6_SEMANTIC_DISPATCH;
    this.confidence = BigDecimal.ONE;
  }

  @JsonCreator
  public RecordingAction(
      @JsonProperty("schema") String schema,
      @JsonProperty("sessionId") String sessionId,
      @JsonProperty("tick") long tick,
      @JsonProperty("sequence") int sequence,
      @JsonProperty("actionType") String actionType,
      @JsonProperty("target") Map<String, Object> target,
      @JsonProperty("arguments") Map<String, Object> arguments,
      @JsonProperty("source") String source,
      @JsonProperty("confidence") BigDecimal confidence,
      @JsonProperty("capturedAt") String capturedAt) {
    this.schema = schema;
    this.sessionId = sessionId;
    this.tick = tick;
    this.sequence = sequence;
    this.actionType = actionType;
    this.target = target;
    this.arguments = arguments;
    this.source = source;
    this.confidence = confidence;
    this.capturedAt = capturedAt;
  }

  public String getSchema() {
    return schema;
  }

  public void setSchema(String schema) {
    this.schema = schema;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public long getTick() {
    return tick;
  }

  public void setTick(long tick) {
    this.tick = tick;
  }

  public int getSequence() {
    return sequence;
  }

  public void setSequence(int sequence) {
    this.sequence = sequence;
  }

  public String getActionType() {
    return actionType;
  }

  public void setActionType(String actionType) {
    this.actionType = actionType;
  }

  public Map<String, Object> getTarget() {
    return target;
  }

  public void setTarget(Map<String, Object> target) {
    this.target = target;
  }

  public Map<String, Object> getArguments() {
    return arguments;
  }

  public void setArguments(Map<String, Object> arguments) {
    this.arguments = arguments;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public void setConfidence(BigDecimal confidence) {
    this.confidence = confidence;
  }

  public String getCapturedAt() {
    return capturedAt;
  }

  public void setCapturedAt(String capturedAt) {
    this.capturedAt = capturedAt;
  }
}

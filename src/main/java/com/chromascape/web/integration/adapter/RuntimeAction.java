package com.chromascape.web.integration.adapter;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Represents an action to be dispatched to a bot runtime.
 *
 * <p>Actions are high-level intents (e.g., "click_at", "type_text", "run_script") that the adapter
 * translates into runtime-specific commands.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuntimeAction {

  private final String actionType;
  private final Map<String, Object> parameters;
  private boolean accepted;
  private String rejectionReason;

  public RuntimeAction(String actionType, Map<String, Object> parameters) {
    this.actionType = actionType;
    this.parameters = parameters;
  }

  public String getActionType() {
    return actionType;
  }

  public Map<String, Object> getParameters() {
    return parameters;
  }

  public boolean isAccepted() {
    return accepted;
  }

  public void setAccepted(boolean accepted) {
    this.accepted = accepted;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public void setRejectionReason(String rejectionReason) {
    this.rejectionReason = rejectionReason;
  }
}

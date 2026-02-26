package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Request to control a running graph execution.
 *
 * <p>Supports pause, resume, cancel, and retry-from-node operations.
 */
public class GraphControlRequest {

  /** Control action to perform */
  @JsonProperty("action")
  private ControlAction action;

  /** Node ID to retry from (only for RETRY_FROM_NODE action) */
  @JsonProperty("retryFromNodeId")
  private String retryFromNodeId;

  /** Optional reason/message for the control action */
  @JsonProperty("reason")
  private String reason;

  public GraphControlRequest() {}

  public ControlAction getAction() {
    return action;
  }

  public void setAction(ControlAction action) {
    this.action = action;
  }

  public String getRetryFromNodeId() {
    return retryFromNodeId;
  }

  public void setRetryFromNodeId(String retryFromNodeId) {
    this.retryFromNodeId = retryFromNodeId;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GraphControlRequest that = (GraphControlRequest) o;
    return action == that.action && Objects.equals(retryFromNodeId, that.retryFromNodeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(action, retryFromNodeId);
  }

  @Override
  public String toString() {
    return "GraphControlRequest{" + "action=" + action + ", retryFromNodeId='" + retryFromNodeId + '\'' + '}';
  }

  /** Control actions for graph execution. */
  public enum ControlAction {
    /** Pause execution at the next node boundary */
    PAUSE,

    /** Resume a paused execution */
    RESUME,

    /** Cancel execution entirely */
    CANCEL,

    /** Retry from a specific node */
    RETRY_FROM_NODE,

    /** Skip current node and move to next */
    SKIP,

    /** Force stop with timeout */
    FORCE_STOP
  }
}

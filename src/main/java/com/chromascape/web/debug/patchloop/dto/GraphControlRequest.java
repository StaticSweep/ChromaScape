package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Request to control a running graph execution.
 *
 * <p>Supports pause, resume, cancel, retry-from-node, and relaunch-if-created operations.
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
    return "GraphControlRequest{"
        + "action="
        + action
        + ", retryFromNodeId='"
        + retryFromNodeId
        + '\''
        + '}';
  }

  /** Creates a RESUME control request. */
  public static GraphControlRequest resume() {
    GraphControlRequest request = new GraphControlRequest();
    request.setAction(ControlAction.RESUME);
    return request;
  }

  /** Creates a PAUSE control request. */
  public static GraphControlRequest pause() {
    GraphControlRequest request = new GraphControlRequest();
    request.setAction(ControlAction.PAUSE);
    return request;
  }

  /** Creates a CANCEL control request with optional reason. */
  public static GraphControlRequest cancel(String reason) {
    GraphControlRequest request = new GraphControlRequest();
    request.setAction(ControlAction.CANCEL);
    request.setReason(reason);
    return request;
  }

  /** Creates a RETRY_FROM_NODE control request. */
  public static GraphControlRequest retryFromNode(String nodeId) {
    GraphControlRequest request = new GraphControlRequest();
    request.setAction(ControlAction.RETRY_FROM_NODE);
    request.setRetryFromNodeId(nodeId);
    return request;
  }

  /** Creates a RELAUNCH_IF_CREATED control request. */
  public static GraphControlRequest relaunchIfCreated(String reason) {
    GraphControlRequest request = new GraphControlRequest();
    request.setAction(ControlAction.RELAUNCH_IF_CREATED);
    request.setReason(reason);
    return request;
  }

  /** Creates a SKIP control request. */
  public static GraphControlRequest skip() {
    GraphControlRequest request = new GraphControlRequest();
    request.setAction(ControlAction.SKIP);
    return request;
  }

  /** Creates a FORCE_STOP control request. */
  public static GraphControlRequest forceStop() {
    GraphControlRequest request = new GraphControlRequest();
    request.setAction(ControlAction.FORCE_STOP);
    return request;
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

    /** Relaunch async execution if still in CREATED and no node has started */
    RELAUNCH_IF_CREATED,

    /** Skip current node and move to next */
    SKIP,

    /** Force stop with timeout */
    FORCE_STOP
  }
}

package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Response from executing a patch loop iteration.
 *
 * <p>Returns the iteration ID, execution ID, initial status, and connection URLs.
 *
 * @since 2.1.0
 */
public class PatchLoopExecuteResponse {

  /** Unique iteration ID */
  @JsonProperty("iterationId")
  private String iterationId;

  /** Graph execution ID (links iteration to graph execution) */
  @JsonProperty("executionId")
  private String executionId;

  /** Current status */
  @JsonProperty("status")
  private String status;

  /** Status message */
  @JsonProperty("message")
  private String message;

  /** WebSocket URL for real-time updates */
  @JsonProperty("wsUrl")
  private String wsUrl;

  /** REST API URL for this execution */
  @JsonProperty("apiUrl")
  private String apiUrl;

  public PatchLoopExecuteResponse() {}

  public PatchLoopExecuteResponse(String iterationId, String status, String message) {
    this.iterationId = iterationId;
    this.status = status;
    this.message = message;
  }

  public String getIterationId() {
    return iterationId;
  }

  public void setIterationId(String iterationId) {
    this.iterationId = iterationId;
  }

  public String getExecutionId() {
    return executionId;
  }

  public void setExecutionId(String executionId) {
    this.executionId = executionId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getWsUrl() {
    return wsUrl;
  }

  public void setWsUrl(String wsUrl) {
    this.wsUrl = wsUrl;
  }

  public String getApiUrl() {
    return apiUrl;
  }

  public void setApiUrl(String apiUrl) {
    this.apiUrl = apiUrl;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PatchLoopExecuteResponse that = (PatchLoopExecuteResponse) o;
    return Objects.equals(iterationId, that.iterationId)
        && Objects.equals(status, that.status)
        && Objects.equals(message, that.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(iterationId, status, message);
  }

  @Override
  public String toString() {
    return "PatchLoopExecuteResponse{"
        + "iterationId='"
        + iterationId
        + '\''
        + ", executionId='"
        + executionId
        + '\''
        + ", status='"
        + status
        + '\''
        + ", message='"
        + message
        + '\''
        + '}';
  }
}

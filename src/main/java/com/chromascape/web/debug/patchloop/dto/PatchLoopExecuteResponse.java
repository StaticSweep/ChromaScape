package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Response from executing a patch loop iteration.
 *
 * <p>Returns the iteration ID and initial status.
 */
public class PatchLoopExecuteResponse {

  /** Unique iteration ID */
  @JsonProperty("iterationId")
  private String iterationId;

  /** Current status */
  @JsonProperty("status")
  private String status;

  /** Status message */
  @JsonProperty("message")
  private String message;

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
        + ", status='"
        + status
        + '\''
        + ", message='"
        + message
        + '\''
        + '}';
  }
}

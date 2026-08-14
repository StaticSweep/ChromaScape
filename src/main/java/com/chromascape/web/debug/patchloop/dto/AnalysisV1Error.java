package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Error information for failed analysis requests.
 *
 * <p>Returned in {@link AnalysisV1Response} when status is FAILURE.
 */
public class AnalysisV1Error {

  /** Error code from {@link com.chromascape.web.debug.patchloop.constants.AnalysisV1ErrorCode} */
  @JsonProperty("code")
  private String code;

  /** Human-readable error message */
  @JsonProperty("message")
  private String message;

  /** Additional context about the error (e.g., stack trace, API response) */
  @JsonProperty("details")
  private String details;

  /** Timestamp when the error occurred */
  @JsonProperty("timestamp")
  private LocalDateTime timestamp;

  public AnalysisV1Error() {
    this.timestamp = LocalDateTime.now();
  }

  public AnalysisV1Error(String code, String message) {
    this.code = code;
    this.message = message;
    this.timestamp = LocalDateTime.now();
  }

  public AnalysisV1Error(String code, String message, String details) {
    this.code = code;
    this.message = message;
    this.details = details;
    this.timestamp = LocalDateTime.now();
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AnalysisV1Error that = (AnalysisV1Error) o;
    return Objects.equals(code, that.code)
        && Objects.equals(message, that.message)
        && Objects.equals(details, that.details)
        && Objects.equals(timestamp, that.timestamp);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code, message, details, timestamp);
  }

  @Override
  public String toString() {
    return "AnalysisV1Error{"
        + "code='"
        + code
        + '\''
        + ", message='"
        + message
        + '\''
        + ", details='"
        + details
        + '\''
        + ", timestamp="
        + timestamp
        + '}';
  }
}

package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Session context for attaching debugger session information to patch loop iterations.
 *
 * <p>Allows linking patch loop analysis to specific debug sessions, recordings, and time windows.
 *
 * @since 2.1.0
 */
public class SessionContextDto {

  /** Associated debug session ID */
  @JsonProperty("debugSessionId")
  private String debugSessionId;

  /** Associated recording ID */
  @JsonProperty("recordingId")
  private String recordingId;

  /** Tick window start (for tick-based analysis) */
  @JsonProperty("tickWindowStart")
  private Long tickWindowStart;

  /** Tick window end (for tick-based analysis) */
  @JsonProperty("tickWindowEnd")
  private Long tickWindowEnd;

  /** Time window start (ISO timestamp) */
  @JsonProperty("timeWindowStart")
  private String timeWindowStart;

  /** Time window end (ISO timestamp) */
  @JsonProperty("timeWindowEnd")
  private String timeWindowEnd;

  /** How the backend resolved the effective session linkage. */
  @JsonProperty("resolutionSource")
  private String resolutionSource;

  public SessionContextDto() {}

  public String getDebugSessionId() {
    return debugSessionId;
  }

  public void setDebugSessionId(String debugSessionId) {
    this.debugSessionId = debugSessionId;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
  }

  public Long getTickWindowStart() {
    return tickWindowStart;
  }

  public void setTickWindowStart(Long tickWindowStart) {
    this.tickWindowStart = tickWindowStart;
  }

  public Long getTickWindowEnd() {
    return tickWindowEnd;
  }

  public void setTickWindowEnd(Long tickWindowEnd) {
    this.tickWindowEnd = tickWindowEnd;
  }

  public String getTimeWindowStart() {
    return timeWindowStart;
  }

  public void setTimeWindowStart(String timeWindowStart) {
    this.timeWindowStart = timeWindowStart;
  }

  public String getTimeWindowEnd() {
    return timeWindowEnd;
  }

  public void setTimeWindowEnd(String timeWindowEnd) {
    this.timeWindowEnd = timeWindowEnd;
  }

  public String getResolutionSource() {
    return resolutionSource;
  }

  public void setResolutionSource(String resolutionSource) {
    this.resolutionSource = resolutionSource;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SessionContextDto that = (SessionContextDto) o;
    return Objects.equals(debugSessionId, that.debugSessionId)
        && Objects.equals(recordingId, that.recordingId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(debugSessionId, recordingId);
  }

  @Override
  public String toString() {
    return "SessionContextDto{"
        + "debugSessionId='"
        + debugSessionId
        + '\''
        + ", recordingId='"
        + recordingId
        + '\''
        + ", tickWindow="
        + tickWindowStart
        + "-"
        + tickWindowEnd
        + '}';
  }
}

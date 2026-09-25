package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Target specification for V2 analysis requests.
 *
 * <p>Defines what is being analyzed: script name, run ID, and session/time bounds.
 *
 * @since 2.0.0
 */
public class AnalysisV2Target {

  /** Name of the script being analyzed (required). */
  @JsonProperty("scriptName")
  private String scriptName;

  /** Unique run ID for this execution (optional). */
  @JsonProperty("runId")
  private String runId;

  /** Time-based session bounds (optional). */
  @JsonProperty("sessionBounds")
  private SessionBounds sessionBounds;

  public AnalysisV2Target() {}

  public AnalysisV2Target(String scriptName) {
    this.scriptName = scriptName;
  }

  public AnalysisV2Target(String scriptName, String runId, SessionBounds sessionBounds) {
    this.scriptName = scriptName;
    this.runId = runId;
    this.sessionBounds = sessionBounds;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public SessionBounds getSessionBounds() {
    return sessionBounds;
  }

  public void setSessionBounds(SessionBounds sessionBounds) {
    this.sessionBounds = sessionBounds;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AnalysisV2Target that = (AnalysisV2Target) o;
    return Objects.equals(scriptName, that.scriptName)
        && Objects.equals(runId, that.runId)
        && Objects.equals(sessionBounds, that.sessionBounds);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scriptName, runId, sessionBounds);
  }

  @Override
  public String toString() {
    return "AnalysisV2Target{"
        + "scriptName='"
        + scriptName
        + '\''
        + ", runId='"
        + runId
        + '\''
        + ", sessionBounds="
        + sessionBounds
        + '}';
  }

  /** Time-based bounds for the analysis session. */
  public static class SessionBounds {

    /** Start of the session window. */
    @JsonProperty("start")
    private LocalDateTime start;

    /** End of the session window. */
    @JsonProperty("end")
    private LocalDateTime end;

    public SessionBounds() {}

    public SessionBounds(LocalDateTime start, LocalDateTime end) {
      this.start = start;
      this.end = end;
    }

    public LocalDateTime getStart() {
      return start;
    }

    public void setStart(LocalDateTime start) {
      this.start = start;
    }

    public LocalDateTime getEnd() {
      return end;
    }

    public void setEnd(LocalDateTime end) {
      this.end = end;
    }

    /** Returns true if this session bound is valid (start before end, both non-null). */
    @JsonProperty(value = "valid", access = JsonProperty.Access.READ_ONLY)
    public boolean isValid() {
      return start != null && end != null && start.isBefore(end);
    }

    /** Returns the duration of this session in seconds. */
    @JsonProperty(value = "durationSeconds", access = JsonProperty.Access.READ_ONLY)
    public long getDurationSeconds() {
      if (!isValid()) {
        return 0;
      }
      return java.time.Duration.between(start, end).getSeconds();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SessionBounds that = (SessionBounds) o;
      return Objects.equals(start, that.start) && Objects.equals(end, that.end);
    }

    @Override
    public int hashCode() {
      return Objects.hash(start, end);
    }

    @Override
    public String toString() {
      return "SessionBounds{" + "start=" + start + ", end=" + end + '}';
    }
  }
}

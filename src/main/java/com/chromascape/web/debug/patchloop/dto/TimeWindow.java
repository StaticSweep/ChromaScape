package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents a time-based window for evidence collection.
 *
 * <p>Defines a start and end timestamp for filtering logs, decisions, and other time-series
 * evidence.
 */
public class TimeWindow {

  @JsonProperty("start")
  private LocalDateTime start;

  @JsonProperty("end")
  private LocalDateTime end;

  public TimeWindow() {}

  public TimeWindow(LocalDateTime start, LocalDateTime end) {
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

  /** Returns true if this time window is valid (start before end, both non-null). */
  public boolean isValid() {
    return start != null && end != null && start.isBefore(end);
  }

  /** Returns the duration of this time window in seconds. */
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
    TimeWindow that = (TimeWindow) o;
    return Objects.equals(start, that.start) && Objects.equals(end, that.end);
  }

  @Override
  public int hashCode() {
    return Objects.hash(start, end);
  }

  @Override
  public String toString() {
    return "TimeWindow{" + "start=" + start + ", end=" + end + '}';
  }
}

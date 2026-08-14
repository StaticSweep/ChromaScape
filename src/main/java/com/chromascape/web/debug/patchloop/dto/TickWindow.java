package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Represents a tick-based window for evidence collection.
 *
 * <p>Defines a start and end tick number for filtering tick logs from debug sessions.
 */
public class TickWindow {

  @JsonProperty("startTick")
  private Long startTick;

  @JsonProperty("endTick")
  private Long endTick;

  public TickWindow() {}

  public TickWindow(Long startTick, Long endTick) {
    this.startTick = startTick;
    this.endTick = endTick;
  }

  public Long getStartTick() {
    return startTick;
  }

  public void setStartTick(Long startTick) {
    this.startTick = startTick;
  }

  public Long getEndTick() {
    return endTick;
  }

  public void setEndTick(Long endTick) {
    this.endTick = endTick;
  }

  /** Returns true if this tick window is valid (start <= end, both non-null). */
  public boolean isValid() {
    return startTick != null && endTick != null && startTick <= endTick;
  }

  /** Returns the number of ticks in this window. */
  public long getTickCount() {
    if (!isValid()) {
      return 0;
    }
    return endTick - startTick + 1;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TickWindow that = (TickWindow) o;
    return Objects.equals(startTick, that.startTick) && Objects.equals(endTick, that.endTick);
  }

  @Override
  public int hashCode() {
    return Objects.hash(startTick, endTick);
  }

  @Override
  public String toString() {
    return "TickWindow{" + "startTick=" + startTick + ", endTick=" + endTick + '}';
  }
}

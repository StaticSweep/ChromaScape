package com.chromascape.web.replay.session;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a ChromaScape-owned xRSPS session.
 *
 * <p>{@code CREATING -> READY -> RUNNING}, with explicit pause, stop and terminal states. Worker
 * loss moves a live session to {@code RECONCILING} instead of triggering duplicate execution.
 */
public enum XrspsSessionStatus {
  CREATING,
  READY,
  RUNNING,
  PAUSED,
  RECONCILING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  private static final Map<XrspsSessionStatus, Set<XrspsSessionStatus>> ALLOWED =
      new EnumMap<>(XrspsSessionStatus.class);

  static {
    ALLOWED.put(CREATING, EnumSet.of(READY, CANCELLED));
    ALLOWED.put(READY, EnumSet.of(RUNNING, CANCELLED));
    ALLOWED.put(RUNNING, EnumSet.of(PAUSED, RECONCILING, SUCCEEDED, FAILED, CANCELLED));
    ALLOWED.put(PAUSED, EnumSet.of(RUNNING, RECONCILING, SUCCEEDED, FAILED, CANCELLED));
    ALLOWED.put(RECONCILING, EnumSet.of(PAUSED, CANCELLED, FAILED));
    ALLOWED.put(SUCCEEDED, EnumSet.noneOf(XrspsSessionStatus.class));
    ALLOWED.put(FAILED, EnumSet.noneOf(XrspsSessionStatus.class));
    ALLOWED.put(CANCELLED, EnumSet.noneOf(XrspsSessionStatus.class));
  }

  /** Returns true when this status may move directly to {@code next}. */
  public boolean canTransitionTo(XrspsSessionStatus next) {
    return ALLOWED.getOrDefault(this, Set.of()).contains(next);
  }

  /** Returns true for terminal states: no further transitions are legal. */
  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }
}

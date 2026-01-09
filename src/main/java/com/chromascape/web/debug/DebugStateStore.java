package com.chromascape.web.debug;

import com.chromascape.web.decisions.entity.DecisionEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory store for live debugger state snapshots.
 *
 * <p>This avoids polling the database at high frequency by caching recent decision and overlay
 * details as they arrive.
 */
@Component
public class DebugStateStore {

  private static final int MAX_CLICKS = 50;

  private final ObjectMapper objectMapper;
  private final Deque<OverlayClick> recentClicks = new ArrayDeque<>();
  private final AtomicReference<DecisionSnapshot> lastDecision = new AtomicReference<>();

  @Autowired
  public DebugStateStore(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Records a new decision for overlay and inspector use. */
  public synchronized void recordDecision(DecisionEntity decision) {
    if (decision == null) {
      return;
    }

    DecisionSnapshot snapshot =
        new DecisionSnapshot(
            decision.getId(),
            decision.getScriptName(),
            decision.getDecisionType() != null ? decision.getDecisionType().name() : null,
            decision.getDecisionReason(),
            decision.getSuccess(),
            decision.getFailureCode(),
            decision.getFailureReason(),
            decision.getScreenCoords(),
            decision.getTargetEntity(),
            decision.getGameState(),
            decision.getTimestamp() != null
                ? decision.getTimestamp().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : System.currentTimeMillis());

    lastDecision.set(snapshot);

    Optional<PointPayload> point = parsePoint(decision.getScreenCoords());
    if (point.isPresent()) {
      recentClicks.addFirst(
          new OverlayClick(
              point.get().x(),
              point.get().y(),
              Boolean.TRUE.equals(decision.getSuccess()),
              snapshot.decisionType(),
              snapshot.failureCode(),
              snapshot.timestamp()));
      while (recentClicks.size() > MAX_CLICKS) {
        recentClicks.removeLast();
      }
    }
  }

  /** Returns a snapshot list of recent clicks for overlay rendering. */
  public synchronized List<OverlayClick> getRecentClicks() {
    return new ArrayList<>(recentClicks);
  }

  /** Returns the last known decision snapshot if available. */
  public DecisionSnapshot getLastDecision() {
    return lastDecision.get();
  }

  private Optional<PointPayload> parsePoint(String json) {
    if (json == null || json.isBlank()) {
      return Optional.empty();
    }
    try {
      Map<String, Number> values =
          objectMapper.readValue(json, new TypeReference<Map<String, Number>>() {});
      Number x = values.get("x");
      Number y = values.get("y");
      if (x == null || y == null) {
        return Optional.empty();
      }
      return Optional.of(new PointPayload(x.intValue(), y.intValue()));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  /** Overlay click data for the frontend. */
  public record OverlayClick(
      int x, int y, boolean success, String decisionType, String failureCode, long timestamp) {}

  /** Snapshot of the most recent decision. */
  public record DecisionSnapshot(
      Long id,
      String scriptName,
      String decisionType,
      String decisionReason,
      Boolean success,
      String failureCode,
      String failureReason,
      String screenCoords,
      String targetEntity,
      String gameState,
      long timestamp) {}

  private record PointPayload(int x, int y) {}
}

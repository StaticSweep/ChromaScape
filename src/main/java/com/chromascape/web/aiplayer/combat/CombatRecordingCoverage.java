package com.chromascape.web.aiplayer.combat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Combat evidence adapter for SC-752; counts are not dataset readiness or independent runs. */
public final class CombatRecordingCoverage {
  public static final String SCHEMA = "combat-recording-coverage/v1";

  private CombatRecordingCoverage() {}

  /** Deterministic evidence counts for one explicitly selected session/range. */
  public record Report(
      String schema,
      String sessionId,
      int distinctTicks,
      int distinctRows,
      int invalidLabels,
      Map<String, Long> actionCounts,
      Map<String, Long> deferredTickCounts) {}

  /**
   * Summarizes rows from one recording/range. Repeated windows cannot inflate counts; conflicting
   * rows for one tick/action identity fail closed. Empty producers remain distinguishable from
   * absent producers through each row's deferred list. Caller supplies the immutable session/range
   * selection and its dataset role; this adapter neither selects nor recommends held-out data.
   */
  public static Report summarize(String sessionId, List<Map<String, Object>> rows) {
    if (sessionId == null || sessionId.isBlank() || rows == null) {
      throw new IllegalArgumentException("session identity and rows are required");
    }
    Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
    Map<Long, List<?>> deferredByTick = new TreeMap<>();
    Map<String, Long> actions = new TreeMap<>();
    Map<String, Long> gaps = new TreeMap<>();
    int invalid = 0;
    for (Map<String, Object> row : rows) {
      if (row == null
          || !CombatTickRowService.SCHEMA.equals(row.get("schema"))
          || !(row.get("tick_id") instanceof Number tick)
          || tick.longValue() < 0
          || !(row.get("label") instanceof Map<?, ?> label)
          || !(row.get("deferred") instanceof List<?> deferred)) {
        throw new IllegalArgumentException("invalid combat row shape");
      }
      String key = tick.longValue() + ":" + label.get("sourceTick") + ":" + label.get("sequence");
      Map<String, Object> previous = unique.putIfAbsent(key, row);
      if (previous != null) {
        if (!previous.equals(row)) {
          throw new IllegalArgumentException("conflicting combat rows for " + key);
        }
        continue;
      }
      List<?> previousDeferred = deferredByTick.putIfAbsent(tick.longValue(), deferred);
      if (previousDeferred != null && !previousDeferred.equals(deferred)) {
        throw new IllegalArgumentException("conflicting producer gaps for tick " + tick);
      }
      try {
        CombatTickRowService.requireLabelSafe(row);
        actions.merge((String) label.get("canonicalType"), 1L, Long::sum);
      } catch (IllegalArgumentException rejected) {
        invalid++;
      }
    }
    for (List<?> deferred : deferredByTick.values()) {
      deferred.stream()
          .map(String::valueOf)
          .distinct()
          .forEach(field -> gaps.merge(field, 1L, Long::sum));
    }
    return new Report(
        SCHEMA,
        sessionId,
        deferredByTick.size(),
        unique.size(),
        invalid,
        Collections.unmodifiableMap(actions),
        Collections.unmodifiableMap(gaps));
  }
}

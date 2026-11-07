package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Exact source-clock requests; no invented WAIT action, later state or reordered source row. */
public final class ReplaySourceSchedule {
  private static final List<String> METADATA =
      List.of(
          "mappingVersion",
          "sourceActionTick",
          "sourceActionSequence",
          "provenance",
          "provenanceReason",
          "sourceEvidence");
  private final JsonNode actions;
  private final boolean timed;
  private final long endTick;

  public ReplaySourceSchedule(JsonNode manifest) {
    actions = manifest.path("actions").deepCopy();
    if (!actions.isArray() || actions.size() > 10_000) fail("bounded action array required");
    boolean anyTimed = false;
    for (JsonNode action : actions) {
      if (!action.isObject()) fail("action object required");
      anyTimed |= action.has("sourceActionTick") || action.has("sourceActionSequence");
    }
    timed = manifest.path("manifestVersion").asInt() == 2 && anyTimed;
    endTick = manifest.path("recording").path("endTick").asLong(-1);
    if (!timed) return;
    JsonNode start = manifest.path("recording").path("startTick");
    JsonNode end = manifest.path("recording").path("endTick");
    if (!integer(start, 0, 10_000_000) || !integer(end, start.asLong(), 10_000_000))
      fail("bounded source recording range required");
    long previousTick = -1, previousSequence = -1;
    for (JsonNode action : actions) {
      JsonNode tick = action.path("sourceActionTick"),
          sequence = action.path("sourceActionSequence");
      if (!integer(tick, start.asLong(), endTick)
          || !integer(sequence, 0, 1_000_000)
          || tick.asLong() < previousTick
          || tick.asLong() == previousTick && sequence.asLong() <= previousSequence)
        fail("all recording actions need exact strictly ordered tick/sequence pairs");
      previousTick = tick.asLong();
      previousSequence = sequence.asLong();
    }
  }

  public boolean timed() {
    return timed;
  }

  public ObjectNode next(int index, long currentTick, int bound) {
    if (index < 0 || index >= actions.size() || currentTick < 0 || bound < 1 || bound > 10_000)
      fail("invalid scheduling coordinates");
    JsonNode source = actions.get(index);
    if (timed) {
      long target = source.path("sourceActionTick").asLong();
      if (currentTick > target) fail("source action is behind the worker tick");
      if (currentTick < target) return advance(currentTick, target, bound);
    }
    ObjectNode params =
        JsonNodeFactory.instance
            .objectNode()
            .put("expectedTick", currentTick)
            .put("maxTicks", timed ? 0 : bound)
            .put("sourceActionIndex", index);
    params.set("action", semanticAction(source));
    return params;
  }

  public ObjectNode tail(long currentTick, int bound) {
    if (!timed || currentTick == endTick) return null;
    return nativeTail(currentTick, bound);
  }

  /** Native execution owns a recording deadline even for historical untimed source actions. */
  public ObjectNode nativeTail(long currentTick, int bound) {
    if (currentTick == endTick) return null;
    if (currentTick < 0 || currentTick > endTick || bound < 1 || bound > 10_000)
      fail("invalid recording tail coordinates");
    return advance(currentTick, endTick, bound);
  }

  public static ObjectNode semanticAction(JsonNode source) {
    if (source == null || !source.isObject()) fail("action object required");
    ObjectNode semantic = ((ObjectNode) source).deepCopy();
    for (String field : METADATA) semantic.remove(field);
    return semantic;
  }

  private static ObjectNode advance(long tick, long target, int bound) {
    return JsonNodeFactory.instance
        .objectNode()
        .put("expectedTick", tick)
        .put("maxTicks", Math.min(bound, target - tick));
  }

  private static boolean integer(JsonNode value, long min, long max) {
    return value.isIntegralNumber()
        && value.canConvertToLong()
        && value.asLong() >= min
        && value.asLong() <= max;
  }

  private static void fail(String reason) {
    throw ReplayException.invalid(
        ReplayErrorCode.ACTION_SEQUENCE_INVALID, "SOURCE_ACTION_SCHEDULE_INVALID: " + reason);
  }
}

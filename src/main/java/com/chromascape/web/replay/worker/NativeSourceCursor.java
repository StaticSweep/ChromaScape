package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Committed native source progress, independent of hash samples and actionless steps. */
public final class NativeSourceCursor {
  public static final String SCHEMA = "xrsps-source-cursor/v1";

  private NativeSourceCursor() {}

  public static ObjectNode initial(JsonNode state, boolean restored) {
    long tick = number(state.path("tick"), 10_000_000);
    int actions = (int) number(state.path("acceptedActions"), 10_000);
    int steps = (int) number(state.path("acceptedSteps"), 100_000);
    if (!NativeRunLedger.SCHEMA.equals(state.path("schema").asText())
        || !restored && (actions != 0 || steps != 0)) invalid();
    return cursor(tick, actions, steps, restored ? actions : 0);
  }

  public static ObjectNode advance(JsonNode previous, JsonNode state) {
    validate(previous);
    ObjectNode next = initial(state, true);
    if (next.path("tick").asLong() < previous.path("tick").asLong()
        || next.path("acceptedSteps").asInt() != previous.path("acceptedSteps").asInt() + 1
        || next.path("acceptedSourceActions").asInt()
            < previous.path("acceptedSourceActions").asInt()
        || next.path("acceptedSourceActions").asInt()
            > previous.path("acceptedSourceActions").asInt() + 1) invalid();
    next.put("inheritedSourceActions", previous.path("inheritedSourceActions").asInt());
    return next;
  }

  public static int acceptedActions(JsonNode cursor) {
    validate(cursor);
    return cursor.path("acceptedSourceActions").asInt();
  }

  public static void validate(JsonNode value) {
    if (value == null || !value.isObject() || value.size() != 5
        || !SCHEMA.equals(value.path("schema").asText())) invalid();
    number(value.path("tick"), 10_000_000);
    long actions = number(value.path("acceptedSourceActions"), 10_000);
    long steps = number(value.path("acceptedSteps"), 100_000);
    long inherited = number(value.path("inheritedSourceActions"), actions);
    if (actions > steps || inherited > actions) invalid();
  }

  private static ObjectNode cursor(long tick, int actions, int steps, int inherited) {
    ObjectNode result = JsonNodeFactory.instance.objectNode()
        .put("schema", SCHEMA).put("tick", tick)
        .put("acceptedSourceActions", actions).put("acceptedSteps", steps)
        .put("inheritedSourceActions", inherited);
    validate(result);
    return result;
  }

  private static long number(JsonNode value, long maximum) {
    if (!value.isIntegralNumber() || !value.canConvertToLong()
        || value.asLong() < 0 || value.asLong() > maximum) invalid();
    return value.asLong();
  }

  private static void invalid() {
    throw ReplayException.invalid(ReplayErrorCode.XRSPS_PROTOCOL_ERROR,
        "NATIVE_SOURCE_CURSOR_INVALID: committed native source progress is inconsistent");
  }
}

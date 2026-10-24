package com.chromascape.web.replay.manifest;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Native deterministic ceilings, separate from gameplay success and OS resource qualification. */
public final class ExecutionLimitContract {
  static final String SCHEMA = "xrsps-execution-limits/v1";
  static final int MAXIMUM_TICKS = 100_000;
  static final int MAXIMUM_ACTIONS = 10_000;
  static final int MAXIMUM_STEPS = 100_000;

  private ExecutionLimitContract() {}

  public static ObjectNode defaults(ObjectMapper json, long start, long end) {
    if (start < 0 || end < start || end > 10_000_000) invalid();
    return json.createObjectNode()
        .put("schema", SCHEMA)
        .put("maximumTicks", Math.min(end - start, MAXIMUM_TICKS))
        .put("maximumActions", MAXIMUM_ACTIONS)
        .put("maximumSteps", MAXIMUM_STEPS);
  }

  public static void validate(JsonNode manifest) {
    JsonNode limits = manifest.path("execution");
    if (limits.isMissingNode()) return; // Historical manifests remain inspectable/importable.
    JsonNode start = manifest.path("recording").path("startTick");
    JsonNode end = manifest.path("recording").path("endTick");
    if (!integer(start, 0, 10_000_000)
        || !integer(end, start.asLong(), 10_000_000)
        || !limits.isObject()
        || limits.size() != 4
        || !SCHEMA.equals(limits.path("schema").asText())
        || !integer(
            limits.path("maximumTicks"), 0, Math.min(end.asLong() - start.asLong(), MAXIMUM_TICKS))
        || !integer(limits.path("maximumActions"), 1, MAXIMUM_ACTIONS)
        || !integer(limits.path("maximumSteps"), 1, MAXIMUM_STEPS)) invalid();
  }

  private static boolean integer(JsonNode value, long min, long max) {
    return value.isIntegralNumber()
        && value.canConvertToLong()
        && value.asLong() >= min
        && value.asLong() <= max;
  }

  private static void invalid() {
    throw ReplayException.invalid(
        ReplayErrorCode.VALIDATION_ERROR,
        "RUN_LIMITS_INVALID: execution limits must be bounded by the recording span and native"
            + " ceilings");
  }
}

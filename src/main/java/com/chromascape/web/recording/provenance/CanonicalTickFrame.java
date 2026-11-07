package com.chromascape.web.recording.provenance;

import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.Set;
import java.util.UUID;

/** Immutable canonical frame: recording, features and read-only scoring use this identity. */
public final class CanonicalTickFrame {
  private static final BigInteger MAX_SEQUENCE = new BigInteger("18446744073709551615");
  private static final Set<String> SOURCES =
      Set.of("SCRIPT_INTENT", "CLIENT_EVENT", "STATE_DIFF_INFERENCE", "OPERATOR_ANNOTATION");
  private final ObjectNode frame;

  private CanonicalTickFrame(ObjectNode frame) {
    this.frame = frame.deepCopy();
  }

  public JsonNode payload() {
    return frame.deepCopy();
  }

  public String digest() {
    return frame.path("contentHash").asText();
  }

  public BigInteger sequence() {
    return frame.path("sequence").bigIntegerValue();
  }

  public static CanonicalTickFrame validate(JsonNode input, JsonNode context) {
    if (!input.isObject()
        || input.path("schemaVersion").asInt() != 3
        || !"TICK_BATCH".equals(input.path("type").asText())) fail("UNSUPPORTED_TICK_VERSION");
    for (String key : new String[] {"runId", "sessionId", "producerEpoch"}) {
      try {
        String text = input.path(key).asText();
        if (!UUID.fromString(text).toString().equals(text)) fail("INVALID_" + key);
      } catch (IllegalArgumentException e) {
        fail("INVALID_" + key);
      }
    }
    if (context == null
        || !input.path("sessionId").asText().equals(context.path("debugSessionId").asText())
        || !input.path("runId").asText().equals(context.path("runId").asText()))
      fail("CROSS_SESSION_FRAME");
    if (input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4 * 1024 * 1024)
      fail("FRAME_TOO_LARGE");
    JsonNode sequence = input.path("sequence");
    if (!sequence.isIntegralNumber()
        || sequence.bigIntegerValue().signum() < 1
        || sequence.bigIntegerValue().compareTo(MAX_SEQUENCE) > 0) fail("INVALID_SEQUENCE");
    ObjectNode copy = input.deepCopy();
    String digest = copy.path("contentHash").asText();
    copy.remove("contentHash");
    if (!digest.matches("[0-9a-f]{64}")
        || !Micro6CanonicalHash.sha256WithPrefix(copy).substring(7).equals(digest))
      fail("FRAME_HASH_MISMATCH");
    for (String field :
        new String[] {
          "allocationId",
          "executorId",
          "clientInstanceId",
          "taskKey",
          "behaviorProfileId",
          "catalogHash",
          "settingsHash"
        }) {
      if (!input.path("runContext").path(field).asText().equals(context.path(field).asText())
          || context.path(field).asText().isBlank()) fail("RUN_CONTEXT_MISMATCH");
    }
    if (!input
        .path("runContext")
        .path("behaviorProfileVersion")
        .asText()
        .equals(context.path("behaviorProfileVersion").asText())) fail("RUN_CONTEXT_MISMATCH");
    BehaviorSemanticContract.requireDigest(input.path("semanticContractDigest").asText());
    JsonNode state = input.path("taskState");
    if (!state.path("stage").isTextual() || state.path("stage").asText().isBlank())
      fail("TASK_STAGE_REQUIRED");
    for (String field : new String[] {"stageRevision", "progressRevision"})
      nonnegative(state, field);
    if (state.hasNonNull("terminalState")
        && !Set.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN")
            .contains(state.path("terminalState").asText())) fail("INVALID_TERMINAL_STATE");
    if (!input.path("tick").isIntegralNumber()
        || !input.path("tick").canConvertToLong()
        || input.path("tick").asLong(-1) < 0) fail("INVALID_SOURCE_TICK");
    long tick = input.path("tick").asLong();
    if (!input.path("semanticEvents").isArray() || input.path("semanticEvents").size() > 2048)
      fail("EVENT_QUEUE_LIMIT");
    java.util.Set<String> ids = new java.util.HashSet<>();
    BigInteger previous = BigInteger.ZERO;
    for (JsonNode event : input.path("semanticEvents")) {
      try {
        if (!UUID.fromString(event.path("eventId").asText())
            .toString()
            .equals(event.path("eventId").asText())) fail("INVALID_EVENT_ID");
      } catch (Exception e) {
        fail("INVALID_EVENT_ID");
      }
      if (!ids.add(event.path("eventId").asText())) fail("DUPLICATE_EVENT_ID");
      JsonNode eventSequence = event.path("eventSequence");
      if (!eventSequence.isIntegralNumber()
          || eventSequence.bigIntegerValue().compareTo(MAX_SEQUENCE) > 0
          || eventSequence.bigIntegerValue().compareTo(previous) <= 0)
        fail("INVALID_EVENT_SEQUENCE");
      previous = eventSequence.bigIntegerValue();
      if (!SOURCES.contains(event.path("source").asText())) fail("EVENT_SOURCE_REQUIRED");
      if (!event.path("sourceTick").isIntegralNumber()) fail("SOURCE_TICK_REQUIRED");
      long sourceTick = event.path("sourceTick").asLong();
      if (sourceTick < 0) fail("INVALID_SOURCE_TICK");
      if (sourceTick > tick) fail("FUTURE_OBSERVATION");
      long observationWindow =
          HumanFishingFrameContract.applies(input)
              ? HumanFishingFrameContract.MAX_TRANSITION_DELAY_TICKS
              : 1;
      if (sourceTick < tick - observationWindow) fail("STALE_OBSERVATION");
      if (!event.path("kind").isTextual() || !event.path("stage").isTextual())
        fail("INVALID_SEMANTIC_EVENT");
      if (!event
          .path("sessionId")
          .asText(input.path("sessionId").asText())
          .equals(input.path("sessionId").asText())) fail("CROSS_SESSION_ACTION");
      if (event.hasNonNull("snapshotTick")) {
        long snapshot = event.path("snapshotTick").asLong(-1);
        if (snapshot > sourceTick) fail("FUTURE_SNAPSHOT");
        if (snapshot < sourceTick - 1) fail("UNALIGNED_ACTION");
      }
      if (event.path("humanObserved").asBoolean()
          && (!context.path("dataOrigin").asText().equals("HUMAN")
              || event.path("automated").asBoolean()
              || !event.path("source").asText().equals("CLIENT_EVENT")))
        fail("HUMAN_ACTION_RELABELLED");
    }
    JsonNode quality = input.path("quality");
    for (String key : new String[] {"expected", "observed", "dropped", "unknown"})
      nonnegative(quality, key);
    if (!quality.path("producerVersion").isTextual()
        || !quality.path("adapterVersion").isTextual()
        || !quality.path("captureLimitations").isArray()
        || !quality.path("unsupportedFamilies").isArray()
        || !quality.path("eventFamilyCounters").isObject()
        || !quality.path("gapRanges").isArray()) fail("QUALITY_METADATA_REQUIRED");
    if (quality.path("observed").asLong() != input.path("semanticEvents").size())
      fail("EVENT_COUNT_MISMATCH");
    if (!quality
        .path("expected")
        .bigIntegerValue()
        .equals(
            quality
                .path("observed")
                .bigIntegerValue()
                .add(quality.path("dropped").bigIntegerValue())
                .add(quality.path("unknown").bigIntegerValue()))) fail("QUALITY_COUNT_MISMATCH");
    quality
        .path("eventFamilyCounters")
        .fields()
        .forEachRemaining(
            entry -> {
              if (!entry.getValue().isIntegralNumber() || entry.getValue().asLong(-1) < 0)
                fail("INVALID_FAMILY_COUNT");
            });
    if (!input.path("privacy").equals(context.path("privacy"))) fail("PRIVACY_POLICY_MISMATCH");
    // Prove sensitive raw fields have already been excluded at the producer boundary.
    if (input.has("chat") || input.has("players") && !input.path("players").isEmpty())
      fail("UNREDACTED_CAPTURE");
    if (HumanFishingFrameContract.applies(input))
      HumanFishingFrameContract.validate(input, context);
    copy.put("contentHash", digest);
    return new CanonicalTickFrame(copy);
  }

  public static void requireComplete(JsonNode quality) {
    if (quality.path("acceptedFrames").asLong() < 1
        || quality.path("dropped").asLong(-1) != 0
        || quality.path("unknown").asLong(-1) != 0
        || !quality.path("gapRanges").isArray()
        || !quality.path("gapRanges").isEmpty()
        || !quality.path("unsupportedFamilies").isArray()
        || !quality.path("unsupportedFamilies").isEmpty()
        || !quality.path("closure").asText().equals("EXPLICIT_STOP")) fail("CAPTURE_INCOMPLETE");
  }

  private static void nonnegative(JsonNode node, String field) {
    if (!node.path(field).isIntegralNumber()
        || !node.path(field).canConvertToLong()
        || node.path(field).asLong() < 0) fail("INVALID_QUALITY_" + field);
  }

  private static void fail(String code) {
    throw new ProvenanceException(code);
  }
}

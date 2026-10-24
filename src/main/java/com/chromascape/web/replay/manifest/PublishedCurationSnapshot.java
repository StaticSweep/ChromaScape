package com.chromascape.web.replay.manifest;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.replay.conversion.ActionProvenance;
import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.conversion.ReplayGap;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An immutable, owner-checked operator input; never a replacement for missing capture evidence. */
final class PublishedCurationSnapshot {
  private final ObjectMapper json;
  private final CanonicalProjectionService canonical;
  private final ObjectNode source;

  PublishedCurationSnapshot(
      RecordingCuration row,
      String recordingId,
      String sessionId,
      String ownerId,
      ObjectMapper json,
      CanonicalProjectionService canonical) {
    if (row == null
        || !row.isPublished()
        || row.getId() == null
        || !recordingId.equals(row.getRecordingId())
        || !sessionId.equals(row.getDebugSessionId())
        || (ownerId != null && !ownerId.equals(row.getCreatedBy()))
        || ScenarioManifestV2Compiler.CONVERSION_CREATED_BY.equals(row.getCreatedBy())) {
      throw ReplayException.notFound(
          ReplayErrorCode.CURATION_NOT_PUBLISHED,
          "Published curation is not available for this recording and owner");
    }
    if (row.getRole() == null
        || row.getVersion() < 1
        || row.getSchemaVersion() != 1
        || row.getContentChecksum() == null
        || row.getContentChecksum().isBlank()
        || row.getContentJson() == null
        || !row.getContentJson().isObject()) fail("Invalid published curation snapshot");
    this.json = json;
    this.canonical = canonical;
    source = json.createObjectNode();
    source
        .put("id", row.getId().toString())
        .put("version", row.getVersion())
        .put("checksum", row.getContentChecksum())
        .put("role", row.getRole().name());
    // The legacy curation address was based on JSON serialization order. Retain that exact
    // address AND independently pin canonical content; never rewrite a published row's checksum.
    source.set("contentSnapshot", row.getContentJson().deepCopy());
    source.put(
        "canonicalContentChecksum",
        canonical.sha256Hex(canonical.canonicalJsonBytes(row.getContentJson())));
    var content = source.path("contentSnapshot");
    requireTick(content.path("tickRange").path("start"));
    requireTick(content.path("tickRange").path("end"));
    if (startTick() > endTick()
        || !content.path("actionLabels").isArray()
        || !content.path("initialStateOverrides").isObject())
      fail("Invalid published curation content");
    if (content.has("termination")) TerminationPolicyContract.validate(content.path("termination"));
  }

  long startTick() {
    return source.path("contentSnapshot").path("tickRange").path("start").asLong();
  }

  long endTick() {
    return source.path("contentSnapshot").path("tickRange").path("end").asLong();
  }

  ObjectNode source() {
    return source.deepCopy();
  }

  ReplayActionConversionResult apply(ReplayActionConversionResult raw) {
    Map<String, ConvertedReplayAction> actions = new LinkedHashMap<>();
    for (var converted : raw.actions()) {
      String key = key(converted.action().getTick(), converted.action().getSequence());
      if (actions.putIfAbsent(key, converted) != null)
        fail("Duplicate source action in curation basis");
    }
    List<ReplayGap> gaps = new ArrayList<>(raw.gaps());
    long previousTick = -1;
    int previousSequence = -1;
    int index = 0;
    boolean edited = !source.path("contentSnapshot").path("initialStateOverrides").isEmpty();
    for (JsonNode label : source.path("contentSnapshot").path("actionLabels")) {
      requireTick(label.path("tick"));
      long tick = label.path("tick").asLong();
      JsonNode sequenceNode = label.path("sequence");
      if (!sequenceNode.isIntegralNumber()
          || !sequenceNode.canConvertToInt()
          || sequenceNode.asInt() < 0
          || sequenceNode.asInt() > 1_000_000
          || tick < startTick()
          || tick > endTick()) fail("Curation action is outside its exact tick/sequence bounds");
      int sequence = sequenceNode.asInt();
      if (tick < previousTick || (tick == previousTick && sequence <= previousSequence))
        fail("Published labels must preserve strict tick/sequence order");
      previousTick = tick;
      previousSequence = sequence;
      String key = key(tick, sequence);
      String reference =
          "curation:" + source.path("id").asText() + "/content/actionLabels/" + index++;
      String status = label.path("status").asText();
      if (!Set.of("ACCEPTED_LABEL", "IGNORED", "UNSUPPORTED").contains(status))
        fail("Invalid curation label status");
      edited = true;
      if (status.equals("IGNORED") || status.equals("UNSUPPORTED")) {
        actions.remove(key);
        // Ignoring a captured dispatch is an explicit gap, not proof that its effects vanished.
        gaps.add(
            new ReplayGap(
                tick, "CURATION_" + status, reference, ActionProvenance.OPERATOR_SUPPLIED, null));
        continue;
      }
      if (!label.path("semanticActionType").isTextual()
          || label.path("semanticActionType").asText().isBlank()
          || !label.path("argumentsJson").isObject()
          || !label.path("evidenceRef").isObject()
          || label.path("evidenceRef").isEmpty())
        fail("Accepted curation action requires type, arguments and audit evidence");
      String type = label.path("semanticActionType").asText();
      if (!type.equals("WAIT_UNTIL_TICK")
          && (!label.path("targetStableId").isTextual()
              || label.path("targetStableId").asText().isBlank()))
        fail("Accepted curation action requires an exact target");
      var action = new RecordingAction();
      action.setSessionId(raw.sessionId());
      action.setTick(tick);
      action.setSequence(sequence);
      action.setActionType(type);
      action.setSource("OPERATOR_SUPPLIED");
      action.setProvenance(RecordingAction.PROVENANCE_OPERATOR_SUPPLIED);
      action.setTarget(Map.of("stableId", label.path("targetStableId").asText()));
      action.setArguments(json.convertValue(label.path("argumentsJson"), Map.class));
      if (label.has("confidence")) {
        if (!label.path("confidence").isNumber()
            || label.path("confidence").decimalValue().signum() < 0
            || label.path("confidence").decimalValue().compareTo(java.math.BigDecimal.ONE) > 0)
          fail("Invalid curation confidence");
        action.setConfidence(label.path("confidence").decimalValue());
      }
      actions.put(
          key,
          new ConvertedReplayAction(
              action, ActionProvenance.OPERATOR_SUPPLIED, reference, null, null));
    }
    List<ConvertedReplayAction> ordered = new ArrayList<>(actions.values());
    ordered.sort(
        Comparator.comparingLong((ConvertedReplayAction a) -> a.action().getTick())
            .thenComparingInt(a -> a.action().getSequence()));
    ObjectNode basis =
        json.createObjectNode().put("rawConversionChecksum", raw.conversionChecksum());
    basis.set("sourceCuration", source);
    basis.set("actions", json.valueToTree(ordered));
    basis.set("gaps", json.valueToTree(gaps));
    return new ReplayActionConversionResult(
        raw.sessionId(),
        raw.startTick(),
        raw.endTick(),
        raw.mode(),
        edited ? ConversionQuality.BEST_EFFORT : raw.quality(),
        ordered,
        gaps,
        raw.approximations(),
        raw.typedActionCount(),
        raw.legacyLogCount(),
        raw.actionCoveragePct(),
        canonical.sha256Hex(canonical.canonicalJsonBytes(basis)));
  }

  ObjectNode overrideInitialState(ObjectNode initialState) {
    JsonNode overrides = source.path("contentSnapshot").path("initialStateOverrides");
    Set<String> allowed =
        Set.of(
            "worldId",
            "collisionRevision",
            "player",
            "npcs",
            "objects",
            "groundItems",
            "widgets",
            "vars",
            "bank",
            "bankOpen",
            "exchange",
            "travelProfile",
            "questProfile",
            "worldView",
            "collision",
            "nativeFishingWorld",
            "nativeNpcBindings");
    overrides
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!allowed.contains(key)) fail("Unsupported initial-state override field");
            });
    ObjectNode result = initialState.deepCopy();
    merge(result, overrides);
    NativeNpcBindingContract.validateInitialState(result);
    NativeBankStateContract.validateInitialState(result);
    return result;
  }

  static void merge(ObjectNode target, JsonNode patch) {
    patch
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue().isObject() && target.path(entry.getKey()).isObject()) {
                merge((ObjectNode) target.get(entry.getKey()), entry.getValue());
              } else target.set(entry.getKey(), entry.getValue().deepCopy());
            });
  }

  private static void requireTick(JsonNode value) {
    if (!value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.asLong() < 0
        || value.asLong() > 10_000_000) fail("Curation requires bounded exact ticks");
  }

  private static String key(long tick, int sequence) {
    return tick + ":" + sequence;
  }

  private static void fail(String message) {
    throw ReplayException.invalid(ReplayErrorCode.VALIDATION_ERROR, message);
  }
}

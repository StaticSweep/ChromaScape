package com.chromascape.web.replay.manifest;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.replay.catalog.ReplayNpcPinValidator;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inert source/native binding validation, never a world-support attestation. No catalogue, code,
 * filesystem path or network resource is loaded from these declarations. The native worker checks
 * the actual catalogue and entity; compiler world requirements remain independently unresolved.
 */
final class NativeNpcBindingContract {
  private static final int MAX_ACTIONS = 10_000;
  private static final Set<String> WORLD_FIELDS =
      Set.of("schema", "catalogChecksum", "spawnIndices");
  private static final Set<String> BINDING_FIELDS = Set.of("schema", "catalogChecksum", "bindings");
  private static final Set<String> ROW_FIELDS =
      Set.of(
          "actionIndex",
          "tick",
          "sequence",
          "sourceStableId",
          "spawnIndex",
          "npcTypeId",
          "position",
          "sourceSnapshotRef");
  private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "plane");

  private NativeNpcBindingContract() {}

  static boolean hasNativeWorld(JsonNode state) {
    return state.has("nativeFishingWorld") || state.has("nativeNpcBindings");
  }

  /** Also used before a published operator patch can enter the compilation basis. */
  static void validateInitialState(JsonNode state) {
    if (!hasNativeWorld(state)) return;
    JsonNode world = state.path("nativeFishingWorld");
    if (!exact(world, WORLD_FIELDS)
        || !"xrsps-native-fishing-world/v1".equals(world.path("schema").asText())
        || !hash(world.path("catalogChecksum"))
        || !world.path("spawnIndices").isArray()
        || world.path("spawnIndices").isEmpty()
        || world.path("spawnIndices").size() > 64)
      fail("native world requires a closed v1 catalogue selection of 1..64 spawns");
    Set<Integer> selected = new HashSet<>();
    int previousSpawn = -1;
    for (JsonNode index : world.path("spawnIndices")) {
      if (!integer(index, 0, 99_999) || index.asInt() <= previousSpawn)
        fail("native spawn indices must be unique ascending bounded integers");
      selected.add(index.asInt());
      previousSpawn = index.asInt();
    }
    if (!state.path("npcs").isArray()
        || !state.path("npcs").isEmpty()
        || state.hasNonNull("questProfile")
        || state.hasNonNull("exchange"))
      fail("native Fishing world cannot mix observed NPCs, quest or exchange profiles");
    if (!state.has("nativeNpcBindings")) return;
    JsonNode value = state.path("nativeNpcBindings");
    if (!exact(value, BINDING_FIELDS)
        || !"xrsps-native-npc-bindings/v1".equals(value.path("schema").asText())
        || !value.path("catalogChecksum").equals(world.path("catalogChecksum"))
        || !value.path("bindings").isArray()
        || value.path("bindings").isEmpty()
        || value.path("bindings").size() > MAX_ACTIONS)
      fail("bindings require a closed v1 contract with the selected catalogue pin");
    int previousIndex = -1;
    long previousTick = -1;
    int previousSequence = -1;
    for (JsonNode row : value.path("bindings")) {
      if (!exact(row, ROW_FIELDS)
          || !integer(row.path("actionIndex"), 0, MAX_ACTIONS - 1)
          || row.path("actionIndex").asInt() <= previousIndex
          || !integer(row.path("tick"), 0, 10_000_000)
          || !integer(row.path("sequence"), 0, 1_000_000)
          || row.path("tick").asLong() < previousTick
          || row.path("tick").asLong() == previousTick
              && row.path("sequence").asInt() <= previousSequence
          || !sourceId(row.path("sourceStableId"))
          || !integer(row.path("spawnIndex"), 0, 99_999)
          || !selected.contains(row.path("spawnIndex").asInt())
          || !integer(row.path("npcTypeId"), 1, 65534)
          || !position(row.path("position"))
          || !row.path("sourceSnapshotRef").isTextual()
          || !row.path("sourceSnapshotRef").asText().matches("snapshot:sha256:[0-9a-f]{64}"))
        fail("binding rows must be closed, bounded, selected and strictly source-ordered");
      previousIndex = row.path("actionIndex").asInt();
      previousTick = row.path("tick").asLong();
      previousSequence = row.path("sequence").asInt();
    }
  }

  /** Revalidate exact action and compiler audit tuples, even after an import is resealed. */
  static void validateManifest(JsonNode document) {
    JsonNode state = document.path("initialState");
    validateInitialState(state);
    if (!state.has("nativeNpcBindings")) return;
    JsonNode rows = state.path("nativeNpcBindings").path("bindings");
    JsonNode actions = document.path("actions");
    JsonNode targets = document.path("compilationEvidence").path("world").path("actionTargets");
    if (!actions.isArray()
        || rows.size() > actions.size()
        || !targets.isArray()
        || targets.size() > MAX_ACTIONS)
      fail("bindings require existing manifest actions and bounded compiler action targets");
    Map<String, List<JsonNode>> audit = new HashMap<>();
    for (JsonNode target : targets) {
      if (!"NPC".equals(target.path("kind").asText())
          || !integer(target.path("tick"), 0, 10_000_000)
          || !integer(target.path("sequence"), 0, 1_000_000)) continue;
      audit.computeIfAbsent(key(target), ignored -> new ArrayList<>()).add(target);
    }
    for (JsonNode row : rows) {
      JsonNode action = actions.path(row.path("actionIndex").asInt());
      if (!"INTERACT_NPC".equals(action.path("type").asText())
          || !row.path("sourceStableId").equals(action.path("targetStableId"))
          || !sameInteger(row.path("tick"), action.path("sourceActionTick"))
          || !sameInteger(row.path("sequence"), action.path("sourceActionSequence"))
          || !integer(document.path("recording").path("startTick"), 0, row.path("tick").asLong())
          || !integer(
              document.path("recording").path("endTick"), row.path("tick").asLong(), 10_000_000))
        fail("binding does not match the exact indexed source action and recording range");
      List<JsonNode> matches = audit.getOrDefault(key(row), List.of());
      if (matches.size() != 1
          || !matches.get(0).path("stableId").equals(row.path("sourceStableId"))
          || !matches
              .get(0)
              .path("sourceRef")
              .asText()
              .equals(
                  row.path("sourceSnapshotRef").asText()
                      + "/action:"
                      + row.path("sequence").asInt()))
        fail("binding must name one exact unambiguous compiler NPC action target");
    }
  }

  /**
   * Operator declarations must agree with the original action-tick snapshot, not a patched scene.
   */
  static void validateSourceSnapshots(
      JsonNode initialState,
      List<TickSnapshot> snapshots,
      ReplayActionConversionResult conversion,
      ScenarioCompilationEvidence evidence) {
    validateInitialState(initialState);
    if (!initialState.has("nativeNpcBindings")) return;
    var pinViolations = ReplayNpcPinValidator.validate(snapshots, conversion, true);
    if (!pinViolations.isEmpty()) {
      var violation = pinViolations.get(0);
      throw ReplayException.invalid(violation.code(), violation.message());
    }
    Map<Long, TickSnapshot> byTick = new HashMap<>();
    for (TickSnapshot snapshot : snapshots) {
      if (snapshot.getTick() == null || byTick.putIfAbsent(snapshot.getTick(), snapshot) != null)
        fail("binding source snapshots must have unique exact ticks");
    }
    for (JsonNode row : initialState.path("nativeNpcBindings").path("bindings")) {
      int index = row.path("actionIndex").asInt();
      if (index >= conversion.actions().size()) fail("binding source action index is absent");
      var action = conversion.actions().get(index).action();
      TickSnapshot atTick = byTick.get(row.path("tick").asLong());
      if (action == null
          || !"INTERACT_NPC".equals(action.getActionType())
          || action.getTick() != row.path("tick").asLong()
          || action.getSequence() != row.path("sequence").asInt()
          || action.getTarget() == null
          || !row.path("sourceStableId").asText().equals(action.getTarget().get("stableId"))
          || atTick == null
          || !evidence.snapshotRef(atTick).equals(row.path("sourceSnapshotRef").asText()))
        fail("binding source tuple or snapshot pin differs from original evidence");
      List<JsonNode> matches = new ArrayList<>();
      if (atTick.getNpcs() == null || !atTick.getNpcs().isArray())
        fail("binding source NPC array is missing");
      for (JsonNode npc : atTick.getNpcs()) {
        if (row.path("sourceStableId").equals(npc.path("stableId"))) matches.add(npc);
      }
      if (matches.size() != 1
          || !sameInteger(row.path("npcTypeId"), matches.get(0).path("npcTypeId")))
        fail("binding source NPC type is absent, ambiguous or different");
      JsonNode sourcePosition = matches.get(0).path("position");
      for (String axis : POSITION_FIELDS) {
        if (!sameInteger(row.path("position").path(axis), sourcePosition.path(axis)))
          fail("binding position differs from the exact source snapshot");
      }
    }
  }

  private static String key(JsonNode row) {
    return row.path("tick").asLong() + ":" + row.path("sequence").asInt();
  }

  private static boolean exact(JsonNode value, Set<String> fields) {
    if (!value.isObject() || value.size() != fields.size()) return false;
    for (String field : fields) if (!value.has(field)) return false;
    return true;
  }

  private static boolean integer(JsonNode value, long min, long max) {
    return value.isIntegralNumber()
        && value.canConvertToLong()
        && value.asLong() >= min
        && value.asLong() <= max;
  }

  private static boolean sameInteger(JsonNode left, JsonNode right) {
    return left.isIntegralNumber()
        && left.canConvertToLong()
        && right.isIntegralNumber()
        && right.canConvertToLong()
        && left.asLong() == right.asLong();
  }

  private static boolean hash(JsonNode value) {
    return value.isTextual() && value.asText().matches("sha256:[0-9a-f]{64}");
  }

  private static boolean position(JsonNode value) {
    return exact(value, POSITION_FIELDS)
        && integer(value.path("x"), 0, 16383)
        && integer(value.path("y"), 0, 16383)
        && integer(value.path("plane"), 0, 3);
  }

  private static boolean sourceId(JsonNode value) {
    if (!value.isTextual()) return false;
    String text = value.asText();
    return !text.isEmpty()
        && text.length() <= 128
        && !jsWhitespace(text.charAt(0))
        && !jsWhitespace(text.charAt(text.length() - 1))
        && !text.startsWith("native-fishing-spawn:")
        && text.chars().noneMatch(character -> character < 32 || character == 127);
  }

  private static boolean jsWhitespace(char value) {
    return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == '\ufeff';
  }

  private static void fail(String message) {
    throw ReplayException.invalid(
        ReplayErrorCode.VALIDATION_ERROR, "NATIVE_NPC_BINDING_INVALID: " + message);
  }
}

package com.chromascape.web.aiplayer.combat;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.replay.catalog.ReplayEvidenceValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the SC-1976 combat tick row (combat-tick-row/v1) from persisted snapshots or live tick
 * batches.
 *
 * <p>Field provenance follows the contract: captured fields come straight from stored data, derived
 * fields (target block, frame range) are computed here. Missing producers are listed in {@code
 * deferred}; unavailable absolute NPC HP stays null while the captured health ratio and scale
 * remain explicit.
 *
 * <p>Raw inputs are fenced into {@code diagnosticInputs} and are never training labels (SC-718,
 * SC-751). The sole label is {@code label}: one semantic action. Use {@link #requireLabelSafe(Map)}
 * to enforce the fence before a row enters any training gate.
 */
public final class CombatTickRowService {

  /** Schema identifier emitted on every row. */
  public static final String SCHEMA = "combat-tick-row/v1";

  /**
   * Combat vocabulary to semantic-action-v1 canonical type. Null value = ambiguous, stays unmapped.
   */
  private static final Map<String, String> VOCAB_TO_CANONICAL =
      Map.of(
          "ATTACK", "INTERACT_NPC",
          "EAT", "INTERACT_INVENTORY_ITEM",
          "MOVE_TILE", "WALK_TO",
          "CAST_SPELL", "CAST_SPELL",
          "WAIT", "WAIT_UNTIL_TICK");

  /**
   * PRAYER_MAGIC is deliberately absent from the map: a prayer toggle and a combat spell are
   * different canonical actions and micro6 must disambiguate the producer first (SC-1976 scope 2).
   */
  public static final Set<String> AMBIGUOUS_VOCAB = Set.of("PRAYER_MAGIC");

  /** Keys permitted inside the {@code label} block. Anything else is a fencing violation. */
  private static final Set<String> LABEL_KEYS =
      Set.of("vocab", "canonicalType", "provenance", "sourceTick", "sequence", "unmapped");

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private CombatTickRowService() {}

  /** Video-sync inputs for the derived frame range (TickVideoSyncService formula). */
  public record VideoSync(long startTick, Integer fps) {
    /** Sync for a session with no recording: frames stay null and {@code hasVideo} is false. */
    public static VideoSync absent(long startTick) {
      return new VideoSync(startTick, null);
    }
  }

  /** Optional menu/click evidence for the diagnostic block. Null when the tick has none. */
  public record DiagnosticEvidence(
      String menuOption, String clickedEntity, Map<String, Integer> clickedTile) {}

  /**
   * Label evidence: either a combat vocab word (mapped forward) or an already-canonical action.
   * {@code unmapped} is true when a vocab word has no canonical mapping yet (deferred
   * disambiguation, e.g. PRAYER_MAGIC).
   */
  public record LabelEvidence(
      String vocab,
      String canonicalType,
      String provenance,
      Long sourceTick,
      Integer sequence,
      boolean unmapped) {

    /** Label from a combat vocabulary word; unmapped stays true until micro6 disambiguates. */
    public static LabelEvidence ofVocab(
        String vocab, String provenance, Long sourceTick, Integer sequence) {
      String canonical = vocab == null ? null : VOCAB_TO_CANONICAL.get(vocab);
      return new LabelEvidence(
          vocab, canonical, provenance, sourceTick, sequence, vocab != null && canonical == null);
    }

    /** Label from an already-canonical typed action (e.g. a persisted RecordingAction). */
    public static LabelEvidence fromAction(RecordingAction action) {
      if (action == null) {
        return new LabelEvidence(null, null, null, null, null, false);
      }
      return new LabelEvidence(
          null,
          action.getActionType(),
          action.getSource(),
          action.getTick(),
          action.getSequence(),
          false);
    }
  }

  /**
   * Maps a combat vocabulary word to its semantic-action-v1 canonical type.
   *
   * @return the canonical type, or null when the word is ambiguous (PRAYER_MAGIC) or unknown. Null
   *     is never silently replaced: callers must surface it via {@code label.unmapped}.
   */
  public static String mapVocabToCanonical(String vocab) {
    if (vocab == null) {
      return null;
    }
    return VOCAB_TO_CANONICAL.get(vocab);
  }

  /**
   * Rejects any row whose {@code label} block carries non-label data (raw inputs or unknown keys).
   *
   * @throws IllegalArgumentException when the fence is violated
   */
  public static void requireLabelSafe(Map<String, Object> row) {
    Object label = row == null ? null : row.get("label");
    if (!(label instanceof Map<?, ?> labelMap)) {
      throw new IllegalArgumentException("combat tick row has no label block");
    }
    for (Object key : labelMap.keySet()) {
      if (!LABEL_KEYS.contains(String.valueOf(key))) {
        throw new IllegalArgumentException("label block carries non-label key: " + key);
      }
    }
    Object type = labelMap.get("canonicalType");
    if (!(type instanceof String actionType)
        || !ReplayEvidenceValidator.CANONICAL_ACTION_TYPES.contains(actionType)
        || !Boolean.FALSE.equals(labelMap.get("unmapped"))) {
      throw new IllegalArgumentException("label has no supported unambiguous semantic action");
    }
    Object vocab = labelMap.get("vocab");
    if (vocab != null && !actionType.equals(VOCAB_TO_CANONICAL.get(vocab))) {
      throw new IllegalArgumentException("label vocabulary does not match its semantic action");
    }
    if (!Set.of("MICRO6_SEMANTIC_DISPATCH", "MICRO6_LEGACY_CONVERSION", "OPERATOR_SUPPLIED")
        .contains(String.valueOf(labelMap.get("provenance")))) {
      throw new IllegalArgumentException("label has no attributable action provenance");
    }
    if (!(labelMap.get("sourceTick") instanceof Number sourceTick)
        || sourceTick.longValue() < 0
        || !(labelMap.get("sequence") instanceof Number sequence)
        || sequence.intValue() < 0) {
      throw new IllegalArgumentException("label has no source tick and action sequence");
    }
  }

  /** Builds a row from a persisted snapshot. */
  public static Map<String, Object> build(
      TickSnapshot snapshot,
      LabelEvidence label,
      DiagnosticEvidence diagnostics,
      VideoSync videoSync) {
    if (snapshot == null) {
      throw new IllegalArgumentException("snapshot is required");
    }
    return buildCore(
        snapshot.getTick() == null ? 0L : snapshot.getTick(),
        snapshot.getTimestamp() == null ? null : snapshot.getTimestamp().toString(),
        snapshot.getPlayerState(),
        snapshot.getInventory(),
        snapshot.getEquipment(),
        snapshot.getNpcs(),
        snapshot.getProjectiles(),
        snapshot.getGroundObjects(),
        snapshot.getTiles(),
        snapshot.getHazards(),
        snapshot.getPhase(),
        snapshot.getInputs(),
        label,
        diagnostics,
        videoSync);
  }

  /** Builds a row from a live tick batch (same extraction, no persistence round-trip). */
  public static Map<String, Object> build(
      TickBatchMessage batch,
      LabelEvidence label,
      DiagnosticEvidence diagnostics,
      VideoSync videoSync) {
    if (batch == null) {
      throw new IllegalArgumentException("batch is required");
    }
    return buildCore(
        batch.getTick(),
        batch.getTimestamp(),
        valueToTree(batch.getPlayerState()),
        valueToTree(batch.getInventory()),
        valueToTree(batch.getEquipment()),
        valueToTree(batch.getNpcs()),
        valueToTree(batch.getProjectiles()),
        valueToTree(batch.getGroundObjects()),
        valueToTree(batch.getTiles()),
        valueToTree(batch.getHazards()),
        valueToTree(batch.getPhase()),
        valueToTree(batch.getInputs()),
        label,
        diagnostics,
        videoSync);
  }

  private static Map<String, Object> buildCore(
      long tick,
      String timestamp,
      JsonNode playerState,
      JsonNode inventory,
      JsonNode equipment,
      JsonNode npcs,
      JsonNode projectiles,
      JsonNode groundObjects,
      JsonNode tiles,
      JsonNode hazards,
      JsonNode phase,
      JsonNode inputs,
      LabelEvidence label,
      DiagnosticEvidence diagnostics,
      VideoSync videoSync) {
    int playerX = intValue(playerState, "worldX", "x");
    int playerY = intValue(playerState, "worldY", "y", "z");
    int playerPlane = intValue(playerState, "plane");
    JsonNode position = playerState != null ? playerState.get("position") : null;
    if (playerX < 0) {
      playerX = intValue(position, "worldX", "x");
    }
    if (playerY < 0) {
      playerY = intValue(position, "worldY", "y", "z");
    }
    if (playerPlane < 0) {
      playerPlane = intValue(position, "plane");
    }

    Map<String, Object> player = new LinkedHashMap<>();
    player.put("hp", intOrNull(nestedInt(playerState, "hp", "current", "currentHp")));
    player.put("maxHp", intOrNull(nestedInt(playerState, "hp", "max", "maxHp")));
    player.put("prayer", intOrNull(nestedInt(playerState, "prayer", "current", "currentPrayer")));
    player.put("maxPrayer", intOrNull(nestedInt(playerState, "prayer", "max", "maxPrayer")));
    player.put("runEnergy", intOrNull(intValue(playerState, "energy", "runEnergy")));
    player.put(
        "specialAttack", intOrNull(intValue(playerState, "specialAttack", "specialAttackPercent")));
    player.put("animationId", intOrNull(intValue(playerState, "animation", "animationId")));
    player.put("x", intOrNull(playerX));
    player.put("y", intOrNull(playerY));
    player.put("plane", intOrNull(playerPlane));

    List<String> prayersActive = stringList(playerState, "prayersActive");
    List<Map<String, Object>> tileList = nodeMapList(tiles);
    List<Map<String, Object>> hazardList =
        hazards == null ? new ArrayList<>() : nodeMapList(hazards);
    Map<String, Object> environment = new LinkedHashMap<>();
    environment.put(
        "nearbyNpcs", nearbyNpcs(npcs, orZero(playerX), orZero(playerY), orZero(playerPlane)));
    environment.put("projectiles", nodeList(projectiles));
    environment.put("groundObjects", nodeList(groundObjects));
    environment.put("tiles", tileList.isEmpty() ? null : tileList);
    environment.put("hazards", hazardList);
    environment.put("phase", phase == null || phase.isNull() ? null : nodeToMap(phase));
    environment.put("prayersActive", prayersActive);

    Map<String, Object> inventoryBlock = new LinkedHashMap<>();
    List<Integer> ids = new ArrayList<>();
    List<Integer> amounts = new ArrayList<>();
    List<Integer> slots = new ArrayList<>();
    if (inventory != null && inventory.isArray()) {
      for (JsonNode item : inventory) {
        ids.add(orZero(intValue(item, "itemId", "id")));
        amounts.add(orZero(quantityValue(item)));
        slots.add(orZero(intValue(item, "slot")));
      }
    }
    inventoryBlock.put("ids", ids);
    inventoryBlock.put("amounts", amounts);
    inventoryBlock.put("slots", slots);

    Map<String, Object> diagnosticBlock = new LinkedHashMap<>();
    diagnosticBlock.put("mouseX", intOrNull(intValue(inputs, "mouseX")));
    diagnosticBlock.put("mouseY", intOrNull(intValue(inputs, "mouseY")));
    diagnosticBlock.put("mouseButton", intOrNull(intValue(inputs, "mouseButton")));
    diagnosticBlock.put(
        "menuOption",
        diagnostics != null && diagnostics.menuOption() != null
            ? diagnostics.menuOption()
            : textOrNull(inputs, "menuOption"));
    diagnosticBlock.put(
        "clickedEntity",
        diagnostics != null && diagnostics.clickedEntity() != null
            ? diagnostics.clickedEntity()
            : textOrNull(inputs, "clickedEntity"));
    Object clickedTile =
        diagnostics != null && diagnostics.clickedTile() != null
            ? diagnostics.clickedTile()
            : nodeToMap(inputs == null ? null : inputs.get("clickedTile"));
    diagnosticBlock.put("clickedTile", clickedTile);

    LabelEvidence effective = label == null ? LabelEvidence.fromAction(null) : label;
    Map<String, Object> labelBlock = new LinkedHashMap<>();
    labelBlock.put("vocab", effective.vocab());
    labelBlock.put("canonicalType", effective.canonicalType());
    labelBlock.put("provenance", effective.provenance());
    labelBlock.put("sourceTick", effective.sourceTick());
    labelBlock.put("sequence", effective.sequence());
    labelBlock.put("unmapped", effective.unmapped());

    Map<String, Object> target = targetBlock(playerState, npcs);

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("schema", SCHEMA);
    row.put("tick_id", tick);
    row.put("timestamp", timestamp);
    row.put("player", player);
    row.put("target", target);
    row.put("environment", environment);
    row.put("inventory", inventoryBlock);
    row.put("equipment", nodeList(equipment));
    row.put("diagnosticInputs", diagnosticBlock);
    row.put("label", labelBlock);
    row.put("video", videoBlock(tick, videoSync));
    row.put(
        "deferred",
        deferredFields(
            player,
            environment,
            hazards != null,
            inputs != null,
            hasNpcInteraction(playerState) && !Boolean.TRUE.equals(target.get("resolved")),
            videoSync));
    return row;
  }

  /**
   * Computes the deferred list from what the producer actually delivered: a field leaves the list
   * only when its producer ran. Empty hazards/prayers are valid data (none active), but an empty
   * tile grid means the collision data never arrived.
   */
  private static List<String> deferredFields(
      Map<String, Object> player,
      Map<String, Object> environment,
      boolean hazardsPresent,
      boolean inputsPresent,
      boolean targetContextMissing,
      VideoSync videoSync) {
    List<String> deferred = new ArrayList<>();
    if (player.get("specialAttack") == null) {
      deferred.add("special_attack");
    }
    if (environment.get("prayersActive") == null) {
      deferred.add("prayers_active");
    }
    if (environment.get("tiles") == null) {
      deferred.add("tiles");
    }
    if (!hazardsPresent) {
      deferred.add("hazards");
    }
    if (environment.get("phase") == null) {
      deferred.add("phase");
    }
    if (!inputsPresent) {
      deferred.add("mouse_x");
      deferred.add("mouse_y");
      deferred.add("mouse_button");
    }
    if (targetContextMissing) {
      deferred.add("target_context");
    }
    if (videoSync == null || videoSync.fps() == null) {
      deferred.add("per_tick_frames_stored");
    }
    return deferred;
  }

  /** Resolves the target block by joining playerState.interacting.index to npcs[].index. */
  private static Map<String, Object> targetBlock(JsonNode playerState, JsonNode npcs) {
    Map<String, Object> target = new LinkedHashMap<>();
    JsonNode interacting = playerState == null ? null : playerState.get("interacting");
    int index = intValue(interacting, "index");
    JsonNode match = null;
    if (hasNpcInteraction(playerState) && index >= 0 && npcs != null && npcs.isArray()) {
      for (JsonNode npc : npcs) {
        if (intValue(npc, "index") == index) {
          match = npc;
          break;
        }
      }
    }
    target.put("resolved", match != null);
    target.put("targetId", match == null ? null : intOrNull(intValue(match, "id", "npcId")));
    target.put(
        "targetHp", match == null ? null : intOrNull(nestedInt(match, "hp", "current", "hp")));
    target.put(
        "targetMaxHp", match == null ? null : intOrNull(nestedInt(match, "hp", "max", "maxHp")));
    target.put(
        "targetHealthRatio", match == null ? null : intOrNull(intValue(match, "healthRatio")));
    target.put(
        "targetHealthScale", match == null ? null : intOrNull(intValue(match, "healthScale")));
    target.put("targetAnimation", match == null ? null : intOrNull(intValue(match, "animation")));
    target.put("x", match == null ? null : intOrNull(intValue(match, "x")));
    target.put("y", match == null ? null : intOrNull(intValue(match, "y", "z")));
    return target;
  }

  private static boolean hasNpcInteraction(JsonNode playerState) {
    JsonNode interacting = playerState == null ? null : playerState.get("interacting");
    return "NPC".equals(textOrNull(interacting, "type"));
  }

  private static List<Map<String, Object>> nearbyNpcs(
      JsonNode npcs, int playerX, int playerY, int playerPlane) {
    List<Map<String, Object>> nearby = new ArrayList<>();
    if (npcs == null || !npcs.isArray()) {
      return nearby;
    }
    for (JsonNode npc : npcs) {
      int x = intValue(npc, "x");
      int y = intValue(npc, "y", "z");
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("index", intOrNull(intValue(npc, "index")));
      entry.put("id", intOrNull(intValue(npc, "id", "npcId")));
      entry.put("name", textOrNull(npc, "name"));
      entry.put("hp", intOrNull(nestedInt(npc, "hp", "current", "hp")));
      entry.put("healthRatio", intOrNull(intValue(npc, "healthRatio")));
      entry.put("healthScale", intOrNull(intValue(npc, "healthScale")));
      entry.put("animation", intOrNull(intValue(npc, "animation")));
      entry.put("x", intOrNull(x));
      entry.put("y", intOrNull(y));
      if (x >= 0 && y >= 0) {
        entry.put("distance", Math.max(Math.abs(x - playerX), Math.abs(y - playerY)));
      } else {
        entry.put("distance", null);
      }
      nearby.add(entry);
    }
    return nearby;
  }

  /** Frame range for one tick; frameEnd is frame(tick+1)-1. Nulls when the session has no video. */
  private static Map<String, Object> videoBlock(long tick, VideoSync videoSync) {
    Map<String, Object> video = new LinkedHashMap<>();
    boolean hasVideo = videoSync != null && videoSync.fps() != null;
    video.put("hasVideo", hasVideo);
    video.put("fps", hasVideo ? videoSync.fps() : null);
    if (!hasVideo) {
      video.put("frameStart", null);
      video.put("frameEnd", null);
      return video;
    }
    long startTick = videoSync.startTick();
    long frameStart = Math.max(0, Math.round((tick - startTick) * videoSync.fps() * 0.6));
    long frameEnd = Math.max(0, Math.round((tick + 1 - startTick) * videoSync.fps() * 0.6) - 1);
    video.put("frameStart", (int) frameStart);
    video.put("frameEnd", (int) frameEnd);
    return video;
  }

  private static List<Object> nodeList(JsonNode node) {
    List<Object> out = new ArrayList<>();
    if (node != null && node.isArray()) {
      node.forEach(out::add);
    }
    return out;
  }

  private static List<Map<String, Object>> nodeMapList(JsonNode node) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode entry : node) {
        Map<String, Object> map = nodeToMap(entry);
        if (map != null) {
          out.add(map);
        }
      }
    }
    return out;
  }

  private static Map<String, Object> nodeToMap(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    return OBJECT_MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {});
  }

  private static List<String> stringList(JsonNode node, String key) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(key);
    if (value == null || !value.isArray()) {
      return null;
    }
    List<String> out = new ArrayList<>();
    value.forEach(entry -> out.add(entry.isTextual() ? entry.asText() : entry.toString()));
    return out;
  }

  private static int nestedInt(JsonNode node, String group, String nestedKey, String flatKey) {
    if (node != null && node.get(group) != null && node.get(group).isObject()) {
      int nested = intValue(node.get(group), nestedKey);
      if (nested >= 0) {
        return nested;
      }
    }
    return intValue(node, flatKey);
  }

  private static int intValue(JsonNode node, String... keys) {
    if (node == null) {
      return -1;
    }
    for (String key : keys) {
      JsonNode value = node.get(key);
      if (value != null && value.isNumber()) {
        return value.intValue();
      }
      if (value != null && value.isTextual()) {
        try {
          return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException ignored) {
          // fall through to next key
        }
      }
    }
    return -1;
  }

  private static int quantityValue(JsonNode item) {
    return Math.max(0, intValue(item, "quantity"));
  }

  private static Integer intOrNull(int value) {
    return value < 0 ? null : value;
  }

  private static int orZero(int value) {
    return Math.max(0, value);
  }

  private static String textOrNull(JsonNode node, String key) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(key);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  private static JsonNode valueToTree(Object value) {
    if (value == null) {
      return null;
    }
    return OBJECT_MAPPER.valueToTree(value);
  }
}

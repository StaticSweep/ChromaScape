package com.chromascape.web.replay.catalog;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE single interpretation of recorded-evidence validity, shared by preflight ({@code
 * RecordingIntegrityService}/{@code ReplayPreflightService}) and the scenario compiler ({@code
 * ScenarioCompiler}). Before this class existed the two components carried separate
 * interpretations: preflight checked most evidence only on the FIRST snapshot while the compiler
 * re-derived its own rules, so a recording could pass preflight yet fail compilation (or worse,
 * silently diverge).
 *
 * <p>Contract enforced here:
 *
 * <ol>
 *   <li>snapshot count EXACTLY equals {@code maxTick - minTick + 1} AND the ordered ticks are
 *       strictly consecutive — duplicates and sequences such as {@code 0,0,2} are rejected;
 *   <li>every snapshot carries an explicit, SUPPORTED schema version — null is never accepted;
 *   <li>the INITIAL tick carries required world/player/inventory/equipment/NPC evidence, every
 *       initial NPC spotType belongs to the supported v1 set, and equipment slots are within range
 *       and unique;
 *   <li>every ACCEPTED curated action has a snapshot at ITS OWN tick;
 *   <li>the target stable id exists UNIQUELY at that tick;
 *   <li>NPC id, position, plane, and spot type are valid AT THAT TICK (NPCs move);
 *   <li>the selected option equals the recorded target spot type at that tick.
 * </ol>
 */
public final class ReplayEvidenceValidator {

  /** The only supported snapshot schema version in v1. */
  public static final int SUPPORTED_SNAPSHOT_SCHEMA_VERSION = 1;

  /** Supported interaction options == supported fishing spot types in v1. */
  public static final Set<String> SUPPORTED_SPOT_TYPES =
      Set.of("net", "bait", "lure", "cage", "harpoon");

  /** The only semantic action types compilable in v1. */
  public static final Set<String> SUPPORTED_ACTION_TYPES =
      Set.of("INTERACT_NPC", "WAIT_UNTIL_TICK");

  /** Inclusive upper bound of supported v1 equipment slots (OSRS wearable indices). */
  public static final int MAX_EQUIPMENT_SLOT = 13;

  // ── curated action statuses with explicit v1 meaning ──

  public static final String STATUS_ACCEPTED_LABEL = "ACCEPTED_LABEL";
  public static final String STATUS_IGNORED = "IGNORED";
  public static final String STATUS_UNSUPPORTED = "UNSUPPORTED";

  private ReplayEvidenceValidator() {}

  /** One evidence finding with its stable error code and remediation hint. */
  public record Violation(ReplayErrorCode code, String message, String remediation) {}

  /**
   * Narrow view of one curated action so the validator never touches persistence. Only
   * ACCEPTED_LABEL actions are validated; IGNORED/UNSUPPORTED statuses are caller policy.
   */
  public record AcceptedAction(
      long tick,
      int sequence,
      String semanticActionType,
      String targetStableId,
      JsonNode argumentsJson) {}

  /**
   * Persistence-free view of a full curated-action row INCLUDING its label status, so preflight and
   * compiler hand the SAME rows to {@link #validateCurationActions}.
   */
  public record CuratedActionRow(
      long tick,
      int sequence,
      String status,
      String semanticActionType,
      String targetStableId,
      JsonNode argumentsJson) {}

  /**
   * THE single action pipeline shared by POST preflight and the scenario compiler: status policy
   * FIRST (only explicitly {@code IGNORED} actions are dropped; {@code UNSUPPORTED} and unknown
   * statuses are violations), then semantic-action-type support, then per-tick evidence validation
   * of every surviving action via {@link #validateAcceptedActions}. Because both components call
   * this one method, a curation can never pass preflight yet fail compilation — or vice versa.
   */
  public static List<Violation> validateCurationActions(
      List<CuratedActionRow> actions, Map<Long, TickSnapshot> snapshotsByTick) {
    List<Violation> violations = new ArrayList<>();
    List<AcceptedAction> accepted = new ArrayList<>();
    for (CuratedActionRow action : actions) {
      String status = action.status() == null ? "" : action.status();
      switch (status) {
        case STATUS_IGNORED -> {
          // The ONLY dropping path: an explicitly ignored action carries no contract.
        }
        case STATUS_ACCEPTED_LABEL -> {
          if (action.semanticActionType() == null
              || !SUPPORTED_ACTION_TYPES.contains(action.semanticActionType())) {
            violations.add(
                new Violation(
                    ReplayErrorCode.ACTION_UNSUPPORTED,
                    "Curated action type "
                        + action.semanticActionType()
                        + " at tick "
                        + action.tick()
                        + " is unsupported",
                    "Label the action with a v1-supported semantic type"));
            continue;
          }
          accepted.add(
              new AcceptedAction(
                  action.tick(),
                  action.sequence(),
                  action.semanticActionType(),
                  action.targetStableId(),
                  action.argumentsJson()));
        }
        case STATUS_UNSUPPORTED -> violations.add(
            new Violation(
                ReplayErrorCode.ACTION_UNSUPPORTED,
                "Curation marks tick "
                    + action.tick()
                    + " sequence "
                    + action.sequence()
                    + " UNSUPPORTED",
                "Re-label or remove unsupported actions before publishing"));
        default -> violations.add(
            new Violation(
                ReplayErrorCode.ACTION_UNSUPPORTED,
                "Unknown curated action status '"
                    + status
                    + "' at tick "
                    + action.tick()
                    + " sequence "
                    + action.sequence(),
                "Use one of the v1 label statuses"));
      }
    }
    violations.addAll(validateAcceptedActions(accepted, snapshotsByTick));
    return violations;
  }

  /**
   * Validates recording-level evidence: tick continuity, explicit supported schema versions on
   * EVERY snapshot, and complete initial-tick world evidence.
   */
  public static List<Violation> validateRecordingEvidence(
      long startTick, long endTick, long snapshotCount, List<TickSnapshot> orderedSnapshots) {
    List<Violation> violations = new ArrayList<>();

    // 1. Tick continuity: exactly one snapshot per tick across the inclusive range.
    long expected = endTick - startTick + 1;
    if (expected != snapshotCount) {
      violations.add(
          new Violation(
              ReplayErrorCode.TICK_GAP_DETECTED,
              "Tick snapshots contain gaps: expected "
                  + expected
                  + " snapshots for range ["
                  + startTick
                  + ","
                  + endTick
                  + "] but found "
                  + snapshotCount,
              "Re-record the debug session without tick gaps"));
      return violations;
    }

    // 1b. Per-tick verification of the ORDERED rows: count/range equality alone cannot
    // detect sequences such as 0,0,2 (right count, wrong ticks). Ticks must be strictly
    // consecutive from startTick; a repeat is reported explicitly as a duplicate.
    Set<Long> seenTicks = new HashSet<>();
    for (int i = 0; i < orderedSnapshots.size(); i++) {
      long tick = orderedSnapshots.get(i).getTick();
      Long previous = i > 0 ? orderedSnapshots.get(i - 1).getTick() : null;
      if (!seenTicks.add(tick)) {
        violations.add(
            new Violation(
                ReplayErrorCode.TICK_GAP_DETECTED,
                "Duplicate tick snapshot at tick " + tick,
                "Re-record the debug session without duplicated ticks"));
        break;
      }
      if (tick != startTick + i) {
        violations.add(
            new Violation(
                ReplayErrorCode.TICK_GAP_DETECTED,
                "Ordered snapshot "
                    + i
                    + " carries tick "
                    + tick
                    + " but the contiguous range expects "
                    + (startTick + i)
                    + (previous != null ? " (previous tick " + previous + ")" : ""),
                "Re-record the debug session without tick gaps or duplicates"));
        break;
      }
    }

    // 2. Explicit supported schema version on EVERY snapshot — never null, never drifted.
    for (TickSnapshot snap : orderedSnapshots) {
      Integer version = snap.getSchemaVersion();
      if (version == null) {
        violations.add(
            new Violation(
                ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                "Snapshot at tick " + snap.getTick() + " carries NO schema version",
                "Re-record on a pipeline that stamps schema versions"));
        break;
      }
      if (version != SUPPORTED_SNAPSHOT_SCHEMA_VERSION) {
        violations.add(
            new Violation(
                ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                "Snapshot at tick "
                    + snap.getTick()
                    + " carries unsupported schema version "
                    + version,
                "Re-record on a pipeline producing schema version "
                    + SUPPORTED_SNAPSHOT_SCHEMA_VERSION));
        break;
      }
    }

    // 3. Initial-tick world evidence.
    TickSnapshot first = orderedSnapshots.isEmpty() ? null : orderedSnapshots.get(0);
    if (first != null) {
      violations.addAll(validateInitialTick(first));
    }
    return violations;
  }

  /**
   * Initial-tick requirements: player state/position, world identity, inventory, equipment, NPCs.
   */
  public static List<Violation> validateInitialTick(TickSnapshot first) {
    List<Violation> violations = new ArrayList<>();
    var ps = first.getPlayerState();
    if (ps == null || ps.isNull()) {
      violations.add(
          new Violation(
              ReplayErrorCode.PLAYER_STATE_MISSING,
              "Earliest tick snapshot has no player state",
              "Record player state capture during the session"));
    } else {
      var pos =
          ps.has("position")
              ? ps.get("position")
              : ps.has("worldPoint") ? ps.get("worldPoint") : null;
      if (pos == null || !pos.isObject()) {
        violations.add(
            new Violation(
                ReplayErrorCode.POSITION_MISSING,
                "Recorded player position missing at start tick",
                "Record world-point capture during the session"));
      }
      violations.addAll(checkWorld(ps));
    }
    violations.addAll(checkInventory(first));
    violations.addAll(checkEquipment(first));
    violations.addAll(checkNpcTargets(first));
    return violations;
  }

  /**
   * Validates every ACCEPTED curated action against the snapshot AT ITS TICK: presence of the
   * snapshot, unique live target stable id, valid NPC identity/position/plane/spot type there, and
   * option/spot-type agreement.
   */
  public static List<Violation> validateAcceptedActions(
      List<AcceptedAction> actions, Map<Long, TickSnapshot> snapshotsByTick) {
    List<Violation> violations = new ArrayList<>();
    for (AcceptedAction action : actions) {
      // 4. A snapshot must exist at the action's own tick.
      TickSnapshot snap = snapshotsByTick.get(action.tick());
      if (snap == null) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
                "No recorded snapshot exists at curated tick "
                    + action.tick()
                    + " (sequence "
                    + action.sequence()
                    + ")",
                "Curate only ticks captured by the debug session"));
        continue;
      }
      if ("WAIT_UNTIL_TICK".equals(action.semanticActionType())) {
        continue; // no target evidence beyond the snapshot itself
      }

      // 5. Target stable id must exist UNIQUELY at that tick.
      String stableId = action.targetStableId();
      if (stableId == null || stableId.isBlank()) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
                "INTERACT_NPC at tick " + action.tick() + " has no stable target id",
                "Label the action with a resolved stable target id"));
        continue;
      }
      JsonNode targetNpc = findUniqueNpcWithStableId(snap, stableId, violations, action);

      // Option agreement requires the target row.
      String option = extractOption(action.argumentsJson());
      if (option == null || !SUPPORTED_SPOT_TYPES.contains(option)) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_UNSUPPORTED,
                "INTERACT_NPC at tick " + action.tick() + " has missing or unsupported option",
                "Select a supported interaction option"));
        continue;
      }
      if (targetNpc == null) {
        continue; // uniqueness/presence violation already reported
      }

      // 6./7. The target's recorded spot type at THIS tick must equal the chosen option.
      String spotType = targetNpc.path("spotType").asText(null);
      if (spotType == null || spotType.isBlank()) {
        violations.add(
            new Violation(
                ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
                "Target " + stableId + " lacks its typed spotType at tick " + action.tick(),
                "Record typed interaction options for every spot"));
        continue;
      }
      if (!option.equals(spotType)) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_UNSUPPORTED,
                "Option '"
                    + option
                    + "' does not match recorded spotType '"
                    + spotType
                    + "' of "
                    + stableId
                    + " at tick "
                    + action.tick(),
                "Align the curated option with the recorded spot type"));
      }
    }
    return violations;
  }

  /** Unique-target lookup: absence AND duplication both produce violations. */
  private static JsonNode findUniqueNpcWithStableId(
      TickSnapshot snapshot, String stableId, List<Violation> violations, AcceptedAction action) {
    JsonNode npcs = snapshot.getNpcs();
    JsonNode match = null;
    int matches = 0;
    if (npcs != null && npcs.isArray()) {
      for (JsonNode npc : npcs) {
        JsonNode stable = npc.get("stableId");
        if (stable != null && stable.isTextual() && stableId.equals(stable.asText())) {
          match = npc;
          matches++;
        }
      }
    }
    if (matches == 0) {
      violations.add(
          new Violation(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              "Stable target " + stableId + " not present at its action tick " + action.tick(),
              "Curate against targets observed at the action tick"));
      return null;
    }
    if (matches > 1) {
      violations.add(
          new Violation(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              "Stable target "
                  + stableId
                  + " appears "
                  + matches
                  + " times at tick "
                  + action.tick()
                  + "; target must be unique",
              "Enable unambiguous stable-id resolution in the recording pipeline"));
      return null;
    }
    // 6. Identity/position/plane validity at the action's tick.
    int npcId = match.has("npcId") ? match.path("npcId").asInt(-1) : match.path("id").asInt(-1);
    if (npcId <= 0) {
      violations.add(
          new Violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "NPC " + stableId + " has no positive numeric npc id at tick " + action.tick(),
              "Record numeric npc ids in the recording pipeline"));
    }
    JsonNode p =
        match.path("position").isObject() ? match.path("position") : match.path("worldPoint");
    if (!p.isObject()
        || !p.has("x")
        || !p.has("y")
        || !p.has("plane")
        || !p.get("x").isNumber()
        || !p.get("y").isNumber()
        || !p.get("plane").isNumber()) {
      violations.add(
          new Violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "NPC "
                  + stableId
                  + " lacks numeric position coordinates and plane at tick "
                  + action.tick(),
              "Record world-point capture for every observed NPC"));
    }
    return match;
  }

  /** Option extraction shared by compiler and preflight: argumentsJson.option must be textual. */
  public static String extractOption(JsonNode argumentsJson) {
    if (argumentsJson == null) {
      return null;
    }
    JsonNode option = argumentsJson.get("option");
    return option != null && option.isTextual() ? option.asText() : null;
  }

  /** Indexes ordered snapshots by tick for per-action lookups. */
  public static Map<Long, TickSnapshot> indexByTick(List<TickSnapshot> orderedSnapshots) {
    Map<Long, TickSnapshot> byTick = new HashMap<>();
    for (TickSnapshot snap : orderedSnapshots) {
      byTick.putIfAbsent(snap.getTick(), snap);
    }
    return byTick;
  }

  // ── initial-tick field checks (moved verbatim from RecordingIntegrityService) ──

  private static List<Violation> checkWorld(JsonNode ps) {
    List<Violation> violations = new ArrayList<>();
    JsonNode worldNode = ps.has("world") && ps.get("world").isObject() ? ps.get("world") : ps;
    JsonNode worldId = worldNode.get("worldId");
    if (worldId == null || !worldId.canConvertToInt() || worldId.asInt() <= 0) {
      violations.add(
          new Violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "Recorded world id missing or invalid",
              "Record world identity during the session"));
    }
    JsonNode collisionRevision = ps.get("collisionRevision");
    if (collisionRevision == null
        || !collisionRevision.isTextual()
        || collisionRevision.asText().isBlank()) {
      violations.add(
          new Violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "Recorded collision revision missing or blank",
              "Record collision/cache revision during the session"));
    }
    return violations;
  }

  private static List<Violation> checkInventory(TickSnapshot snap) {
    List<Violation> violations = new ArrayList<>();
    var inv = snap.getInventory();
    if (inv == null || !inv.isArray()) {
      violations.add(
          new Violation(
              ReplayErrorCode.INVENTORY_SLOT_MISSING,
              "Earliest tick snapshot carries no inventory array",
              "Record inventory capture during the session"));
      return violations;
    }
    Set<Integer> seenSlots = new HashSet<>();
    for (var item : inv) {
      int itemId =
          item.has("itemId")
              ? item.get("itemId").asInt()
              : item.has("id") ? item.get("id").asInt() : item.asInt(-1);
      if (itemId <= 0) {
        continue; // empty slot entries carry no item evidence
      }
      // EXPLICIT slot required: array position is never accepted as slot evidence.
      JsonNode slotNode = item.get("slot");
      if (slotNode == null || !slotNode.isInt()) {
        violations.add(
            new Violation(
                ReplayErrorCode.INVENTORY_SLOT_MISSING,
                "Recorded inventory item lacks an explicit integer slot",
                "Fix the recording pipeline inventory mapping"));
        return violations;
      }
      int slot = slotNode.asInt();
      int quantity = item.path("quantity").asInt(1);
      if (slot < 0 || slot > 27) {
        violations.add(
            new Violation(
                ReplayErrorCode.VALIDATION_ERROR,
                "Inventory slot outside the supported range 0..27",
                "Fix the recording pipeline inventory mapping"));
        return violations;
      }
      if (!seenSlots.add(slot)) {
        violations.add(
            new Violation(
                ReplayErrorCode.INVENTORY_SLOT_MISSING,
                "Duplicate or ambiguous inventory slot in recorded evidence",
                "Fix the recording pipeline inventory mapping"));
        return violations;
      }
      if (quantity < 1) {
        violations.add(
            new Violation(
                ReplayErrorCode.VALIDATION_ERROR,
                "Recorded inventory quantity is below one",
                "Fix the recording pipeline inventory mapping"));
        return violations;
      }
    }
    return violations;
  }

  private static List<Violation> checkEquipment(TickSnapshot snap) {
    List<Violation> violations = new ArrayList<>();
    var equipment = snap.getEquipment();
    if (equipment == null || !equipment.isArray()) {
      violations.add(
          new Violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "Earliest tick snapshot carries no equipment capture",
              "Record equipment capture during the session"));
      return violations;
    }
    Set<Integer> seenEquipmentSlots = new HashSet<>();
    for (var piece : equipment) {
      int itemId =
          piece.has("itemId")
              ? piece.get("itemId").asInt()
              : piece.has("id") ? piece.get("id").asInt() : -1;
      if (itemId <= 0) {
        continue;
      }
      JsonNode slotNode = piece.get("slot");
      if (slotNode == null || !slotNode.isInt()) {
        violations.add(
            new Violation(
                ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
                "Recorded equipment item lacks an explicit integer slot",
                "Fix the recording pipeline equipment mapping"));
        return violations;
      }
      int slot = slotNode.asInt();
      if (slot < 0 || slot > MAX_EQUIPMENT_SLOT) {
        violations.add(
            new Violation(
                ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
                "Equipment slot outside the supported range 0.." + MAX_EQUIPMENT_SLOT,
                "Fix the recording pipeline equipment mapping"));
        return violations;
      }
      if (!seenEquipmentSlots.add(slot)) {
        violations.add(
            new Violation(
                ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
                "Duplicate or ambiguous equipment slot in recorded evidence",
                "Fix the recording pipeline equipment mapping"));
        return violations;
      }
    }
    return violations;
  }

  private static List<Violation> checkNpcTargets(TickSnapshot snap) {
    List<Violation> violations = new ArrayList<>();
    var npcs = snap.getNpcs();
    if (npcs == null || !npcs.isArray() || npcs.isEmpty()) {
      violations.add(
          new Violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "Initial world contains no NPC targets",
              "Record a session that observes fishing spots"));
      return violations;
    }
    Set<String> stableIds = new HashSet<>();
    int idx = 0;
    for (var npc : npcs) {
      JsonNode stable = npc.get("stableId");
      String id = stable != null && stable.isTextual() ? stable.asText() : null;
      if (id == null || id.isBlank()) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
                "NPC at index " + idx + " has no stable target id",
                "Enable stable-id resolution in the recording pipeline"));
        return violations;
      }
      if (!stableIds.add(id)) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
                "Duplicate stable target id in initial world",
                "Enable stable-id resolution in the recording pipeline"));
        return violations;
      }
      int npcId = npc.has("npcId") ? npc.path("npcId").asInt(-1) : npc.path("id").asInt(-1);
      if (npcId <= 0) {
        violations.add(
            new Violation(
                ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
                "NPC " + id + " has no positive numeric npc id",
                "Record numeric npc ids in the recording pipeline"));
        return violations;
      }
      JsonNode p = npc.path("position").isObject() ? npc.path("position") : npc.path("worldPoint");
      if (!p.isObject()
          || !p.has("x")
          || !p.has("y")
          || !p.has("plane")
          || !p.get("x").isNumber()
          || !p.get("y").isNumber()
          || !p.get("plane").isNumber()) {
        violations.add(
            new Violation(
                ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
                "NPC " + id + " lacks numeric position coordinates and plane",
                "Record world-point capture for every observed NPC"));
        return violations;
      }
      String spotType = npc.path("spotType").asText(null);
      if (spotType == null || !SUPPORTED_SPOT_TYPES.contains(spotType)) {
        violations.add(
            new Violation(
                ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
                "Fishing spot "
                    + id
                    + " carries spotType "
                    + (spotType == null ? "(none)" : "'" + spotType + "'")
                    + " which is outside the supported v1 set "
                    + SUPPORTED_SPOT_TYPES,
                "Record typed interaction options for every spot"));
        return violations;
      }
      idx++;
    }
    return violations;
  }
}

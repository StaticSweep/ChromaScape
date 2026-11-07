package com.chromascape.web.replay.catalog;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

  /** Legacy full-snapshot version retained by existing fixtures and callers. */
  public static final int SUPPORTED_SNAPSHOT_SCHEMA_VERSION = 1;

  /** Full snapshot projections. V3 canonical frames are redacted and need a separate adapter. */
  public static boolean supportsSnapshotSchema(Integer version) {
    return version != null && (version == 1 || version == 2 || version == 4);
  }

  /** Supported interaction options == supported fishing spot types in v1. */
  public static final Set<String> SUPPORTED_SPOT_TYPES =
      Set.of("net", "bait", "lure", "cage", "harpoon");

  /**
   * The complete recording-action/v1 vocabulary. The two legacy names remain accepted by the v1
   * curation reader so an already-published manifest can still be inspected; the live capability
   * manifest deliberately advertises only the canonical ten actions.
   */
  public static final Set<String> CANONICAL_ACTION_TYPES =
      Set.of(
          "WAIT_UNTIL_TICK",
          "WALK_TO",
          "INTERACT_NPC",
          "INTERACT_OBJECT",
          "INTERACT_GROUND_ITEM",
          "INTERACT_INVENTORY_ITEM",
          "INTERACT_WIDGET",
          "SELECT_DIALOGUE_OPTION",
          "CAST_SPELL",
          "EQUIP_ITEM");

  /** Canonical semantic action types accepted by the new curation/recording contract. */
  public static final Set<String> SUPPORTED_ACTION_TYPES = CANONICAL_ACTION_TYPES;

  /** All action names readable from old and new curations. */
  public static final Set<String> ALL_SUPPORTED_ACTION_TYPES =
      java.util.stream.Stream.concat(
              CANONICAL_ACTION_TYPES.stream(),
              Set.of("INVENTORY_ACTION", "USE_ITEM_ON_NPC").stream())
          .collect(java.util.stream.Collectors.toUnmodifiableSet());

  /** Inclusive upper bound of supported v1 equipment slots (OSRS wearable indices). */
  public static final int MAX_EQUIPMENT_SLOT = 13;

  // ── curated action statuses with explicit v1 meaning ──

  public static final String STATUS_ACCEPTED_LABEL = "ACCEPTED_LABEL";
  public static final String STATUS_IGNORED = "IGNORED";
  public static final String STATUS_UNSUPPORTED = "UNSUPPORTED";

  private ReplayEvidenceValidator() {}

  /** Stable position of an action in the append-only same-tick action stream. */
  private record ActionKey(long tick, int sequence) {}

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
    Set<ActionKey> positions = new HashSet<>();
    for (CuratedActionRow action : actions == null ? List.<CuratedActionRow>of() : actions) {
      if (action == null) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_SEQUENCE_INVALID,
                "Curation contains a null action row",
                "Remove the malformed curation row"));
        continue;
      }
      if (action.tick() < 0 || action.sequence() < 0) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_SEQUENCE_INVALID,
                "Curation action tick and sequence must be non-negative",
                "Use a non-negative recording tick and same-tick sequence"));
        continue;
      }
      if (!positions.add(new ActionKey(action.tick(), action.sequence()))) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_SEQUENCE_INVALID,
                "Duplicate curation action at tick "
                    + action.tick()
                    + " sequence "
                    + action.sequence(),
                "Assign each dispatched action a unique sequence within its tick"));
        continue;
      }
      String status = action.status() == null ? "" : action.status();
      switch (status) {
        case STATUS_IGNORED -> {
          // The ONLY dropping path: an explicitly ignored action carries no contract.
        }
        case STATUS_ACCEPTED_LABEL -> {
          if (action.semanticActionType() == null
              || !ALL_SUPPORTED_ACTION_TYPES.contains(action.semanticActionType())) {
            violations.add(
                new Violation(
                    ReplayErrorCode.ACTION_UNSUPPORTED,
                    "Curated action type "
                        + action.semanticActionType()
                        + " at tick "
                        + action.tick()
                        + " is unsupported",
                    "Label the action with a supported canonical semantic type"));
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
    Integer recordingSchema = null;
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
      if (!supportsSnapshotSchema(version)) {
        violations.add(
            new Violation(
                ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                "Snapshot at tick "
                    + snap.getTick()
                    + " carries unsupported schema version "
                    + version,
                "Use a complete snapshot projection with schema version 1, 2, or 4"));
        break;
      }
      if (recordingSchema != null && !recordingSchema.equals(version)) {
        violations.add(
            new Violation(
                ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                "Snapshot schema changes within the recording at tick " + snap.getTick(),
                "Use one immutable snapshot schema throughout the recording"));
        break;
      }
      recordingSchema = version;
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
    Map<Long, TickSnapshot> indexedSnapshots = snapshotsByTick == null ? Map.of() : snapshotsByTick;
    for (AcceptedAction action : actions == null ? List.<AcceptedAction>of() : actions) {
      if (action == null) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_SEQUENCE_INVALID,
                "Curation contains a null accepted action",
                "Remove the malformed curation row"));
        continue;
      }
      String type = action.semanticActionType();
      if (type == null || !ALL_SUPPORTED_ACTION_TYPES.contains(type)) {
        violations.add(
            new Violation(
                ReplayErrorCode.ACTION_UNSUPPORTED,
                "Curation action at tick " + action.tick() + " has unsupported type " + type,
                "Use one of the supported recording-action/v1 types"));
        continue;
      }
      // 4. A snapshot must exist at the action's own tick.
      TickSnapshot snap = indexedSnapshots.get(action.tick());
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
      switch (type) {
        case "WAIT_UNTIL_TICK" -> {
          // No target evidence is required beyond the snapshot at the action tick.
        }
        case "WALK_TO" -> validateWalkAction(action, violations);
        case "INTERACT_NPC" -> validateNpcInteraction(snap, action, violations);
        case "USE_ITEM_ON_NPC" -> validateUseItemOnNpc(snap, action, violations);
        case "INTERACT_OBJECT" -> validateObjectInteraction(snap, action, violations);
        case "INTERACT_GROUND_ITEM" -> validateGroundItemInteraction(snap, action, violations);
        case "INTERACT_INVENTORY_ITEM" -> validateInventoryInteraction(
            snap, action, violations, true);
        case "INVENTORY_ACTION" -> validateInventoryInteraction(snap, action, violations, false);
        case "EQUIP_ITEM" -> validateEquipAction(snap, action, violations);
        case "INTERACT_WIDGET" -> validateWidgetInteraction(snap, action, violations);
        case "SELECT_DIALOGUE_OPTION" -> validateDialogueInteraction(snap, action, violations);
        case "CAST_SPELL" -> validateSpellInteraction(snap, action, violations);
        default -> violations.add(
            new Violation(
                ReplayErrorCode.ACTION_UNSUPPORTED,
                "Curation action type " + type + " is not executable",
                "Use a canonical v1 semantic action"));
      }
    }
    return violations;
  }

  private static void validateWalkAction(AcceptedAction action, List<Violation> violations) {
    JsonNode destination =
        action.argumentsJson() == null ? null : action.argumentsJson().get("destination");
    if (destination == null
        || !destination.isObject()
        || !integerField(destination, "x")
        || !integerField(destination, "y")
        || !integerField(destination, "plane")
        || destination.path("plane").asInt(-1) < 0
        || destination.path("plane").asInt(-1) > 3) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_UNSUPPORTED,
              action,
              "WALK_TO has no bounded destination with x, y, and plane",
              "Record the server-facing destination tile"));
    }
  }

  private static void validateNpcInteraction(
      TickSnapshot snapshot, AcceptedAction action, List<Violation> violations) {
    String stableId = requireStableId(action, violations);
    if (stableId == null) return;
    JsonNode target = findUniqueTarget(snapshot.getNpcs(), stableId, violations, action, "NPC");
    String option = extractOption(action.argumentsJson());
    if (option == null || option.isBlank()) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_UNSUPPORTED,
              action,
              "INTERACT_NPC has a missing option",
              "Record the selected server menu option"));
      return;
    }
    if (target == null) return;
    validateNpcIdentityAndPosition(target, stableId, action, violations);
    validateOptionAgainstTarget(target, option, action, violations);
    String spotType = normalizedText(target, "spotType");
    if (spotType != null && SUPPORTED_SPOT_TYPES.contains(spotType) && !spotType.equals(option)) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_UNSUPPORTED,
              action,
              "Option '"
                  + option
                  + "' does not match recorded spotType '"
                  + spotType
                  + "' of "
                  + stableId,
              "Align the curated option with the recorded spot type"));
    }
    if (spotType == null || !SUPPORTED_SPOT_TYPES.contains(spotType)) {
      // Nearby generic NPCs are valid evidence, but a generic interaction is only replayable when
      // the server-facing menu was captured and contains the selected option. A missing options
      // array must not be treated as an implicit wildcard.
      if (!hasRecordedOption(target, option)) {
        violations.add(
            actionViolation(
                ReplayErrorCode.ACTION_UNSUPPORTED,
                action,
                "Generic NPC " + stableId + " does not expose recorded option '" + option + "'",
                "Capture the NPC's server menu options at the dispatch tick"));
      }
    }
  }

  private static void validateUseItemOnNpc(
      TickSnapshot snapshot, AcceptedAction action, List<Violation> violations) {
    String stableId = requireStableId(action, violations);
    Integer slot = integerArgument(action.argumentsJson(), "slot");
    Integer itemId = integerArgument(action.argumentsJson(), "itemId");
    if (slot == null || slot < 0 || slot > 27 || itemId == null || itemId <= 0) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_UNSUPPORTED,
              action,
              "USE_ITEM_ON_NPC has an invalid slot or itemId",
              "Record the explicit inventory slot and item identity"));
    }
    if (stableId == null) return;
    JsonNode target = findUniqueTarget(snapshot.getNpcs(), stableId, violations, action, "NPC");
    if (target != null) validateNpcIdentityAndPosition(target, stableId, action, violations);
    if (slot != null && itemId != null) {
      validateInventoryRow(snapshot, stableId, slot, itemId, action, violations, false);
    }
  }

  private static void validateObjectInteraction(
      TickSnapshot snapshot, AcceptedAction action, List<Violation> violations) {
    String stableId = requireStableId(action, violations);
    String option = requireOption(action, violations);
    if (stableId == null || option == null) return;
    JsonNode target =
        findUniqueTarget(snapshot.getGameObjects(), stableId, violations, action, "object");
    if (target == null) return;
    validateWorldTarget(
        target, stableId, action, violations, "object", "objectId", "definitionId", "id");
    validateOptionAgainstTarget(target, option, action, violations);
  }

  private static void validateGroundItemInteraction(
      TickSnapshot snapshot, AcceptedAction action, List<Violation> violations) {
    String stableId = requireStableId(action, violations);
    String option = requireOption(action, violations);
    if (stableId == null || option == null) return;
    JsonNode target =
        findUniqueTarget(snapshot.getGroundItems(), stableId, violations, action, "ground item");
    if (target == null) return;
    validateWorldTarget(
        target, stableId, action, violations, "ground item", "itemId", "id", "definitionId");
    Integer itemId = integerArgument(action.argumentsJson(), "itemId");
    if (itemId != null
        && itemId > 0
        && numericField(target, "itemId", "id", "definitionId") != itemId) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              action,
              "Ground item " + stableId + " does not match the recorded itemId " + itemId,
              "Curate against the item identity observed at the action tick"));
    }
    validateOptionAgainstTarget(target, option, action, violations);
  }

  private static void validateInventoryInteraction(
      TickSnapshot snapshot,
      AcceptedAction action,
      List<Violation> violations,
      boolean requireStableId) {
    String stableId = action.targetStableId();
    if (requireStableId && (stableId == null || stableId.isBlank())) {
      requireStableId(action, violations);
      return;
    }
    Integer slot = integerArgument(action.argumentsJson(), "slot");
    Integer itemId = integerArgument(action.argumentsJson(), "itemId");
    String option = requireOption(action, violations);
    if (slot == null || slot < 0 || slot > 27 || itemId == null || itemId <= 0) {
      violations.add(
          actionViolation(
              ReplayErrorCode.INVENTORY_SLOT_MISSING,
              action,
              "Inventory action has an invalid explicit slot or itemId",
              "Record the inventory slot and item identity"));
      return;
    }
    if (option == null) return;
    validateInventoryRow(snapshot, stableId, slot, itemId, action, violations, requireStableId);
  }

  private static void validateEquipAction(
      TickSnapshot snapshot, AcceptedAction action, List<Violation> violations) {
    validateInventoryInteraction(snapshot, action, violations, true);
    Integer equipmentSlot = integerArgument(action.argumentsJson(), "equipmentSlot");
    if (equipmentSlot != null && (equipmentSlot < 0 || equipmentSlot > MAX_EQUIPMENT_SLOT)) {
      violations.add(
          actionViolation(
              ReplayErrorCode.EQUIPMENT_SLOT_MISSING,
              action,
              "EQUIP_ITEM has an invalid equipmentSlot",
              "Record an OSRS equipment slot in the supported range"));
    }
  }

  private static void validateWidgetInteraction(
      TickSnapshot snapshot, AcceptedAction action, List<Violation> violations) {
    String stableId = requireStableId(action, violations);
    String option = requireOption(action, violations);
    if (stableId == null || option == null) return;
    JsonNode target =
        findUniqueTarget(snapshot.getWidgets(), stableId, violations, action, "widget");
    if (target == null) return;
    Integer widgetId = integerArgument(action.argumentsJson(), "widgetId");
    Integer widgetIndex = integerArgument(action.argumentsJson(), "widgetIndex");
    if (widgetId != null && numericField(target, "widgetId", "id") != widgetId) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              action,
              "Widget " + stableId + " does not match widgetId " + widgetId,
              "Retain the server widget identity from the dispatch"));
    }
    if (widgetIndex != null && numericField(target, "widgetIndex", "index") != widgetIndex) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              action,
              "Widget " + stableId + " does not match widgetIndex " + widgetIndex,
              "Retain the server widget index from the dispatch"));
    }
    validateOptionAgainstTarget(target, option, action, violations);
  }

  private static void validateDialogueInteraction(
      TickSnapshot snapshot, AcceptedAction action, List<Violation> violations) {
    String stableId = requireStableId(action, violations);
    if (stableId == null) return;
    JsonNode dialogue = snapshot.getDialogue();
    if (dialogue == null || !dialogue.isObject()) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              action,
              "No dialogue state is recorded at tick " + action.tick(),
              "Capture the active dialogue options at the dispatch tick"));
      return;
    }
    if (!stableId.equals(dialogue.path("stableId").asText(null))) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              action,
              "Dialogue target " + stableId + " is not active at tick " + action.tick(),
              "Curate against the active dialogue identity"));
      return;
    }
    String option = extractOption(action.argumentsJson());
    Integer optionIndex = integerArgument(action.argumentsJson(), "optionIndex");
    if (option == null && optionIndex == null) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_UNSUPPORTED,
              action,
              "SELECT_DIALOGUE_OPTION requires option text or optionIndex",
              "Record the selected dialogue option"));
      return;
    }
    JsonNode options = dialogue.get("options");
    int matches = 0;
    if (options != null && options.isArray()) {
      for (JsonNode candidate : options) {
        boolean indexMatches =
            optionIndex == null || candidate.path("index").asInt(-1) == optionIndex;
        boolean textMatches =
            option == null || normalizeOption(candidate.path("text").asText(null)).equals(option);
        if (indexMatches && textMatches) matches++;
      }
    }
    if (matches != 1) {
      violations.add(
          actionViolation(
              matches == 0
                  ? ReplayErrorCode.ACTION_TARGET_UNRESOLVED
                  : ReplayErrorCode.ACTION_AMBIGUOUS,
              action,
              "Dialogue selection resolves to " + matches + " options at tick " + action.tick(),
              "Capture one enabled, unambiguous dialogue option"));
    }
  }

  private static void validateSpellInteraction(
      TickSnapshot snapshot, AcceptedAction action, List<Violation> violations) {
    String stableId = requireStableId(action, violations);
    if (stableId == null) return;
    Integer spellId = integerArgument(action.argumentsJson(), "spellId");
    String option = extractOption(action.argumentsJson());
    if (spellId == null && option == null) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_UNSUPPORTED,
              action,
              "CAST_SPELL requires spellId or option",
              "Record the spell definition or selected spell option"));
      return;
    }
    JsonNode target = findUniqueTarget(snapshot.getSpells(), stableId, violations, action, "spell");
    if (target == null) return;
    if (spellId != null && numericField(target, "spellId", "id", "definitionId") != spellId) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              action,
              "Spell target " + stableId + " does not match spellId " + spellId,
              "Retain the pinned spell definition identity"));
    }
    if (option != null) validateOptionAgainstTarget(target, option, action, violations);
  }

  private static void validateInventoryRow(
      TickSnapshot snapshot,
      String stableId,
      int slot,
      int itemId,
      AcceptedAction action,
      List<Violation> violations,
      boolean requireStableId) {
    JsonNode inventory = snapshot.getInventory();
    if (inventory == null || !inventory.isArray()) {
      violations.add(
          actionViolation(
              ReplayErrorCode.INVENTORY_SLOT_MISSING,
              action,
              "No inventory array is recorded at tick " + action.tick(),
              "Capture explicit inventory slots at the dispatch tick"));
      return;
    }
    String expectedStableId = "inventory:" + slot + ":" + itemId;
    JsonNode match = null;
    int matches = 0;
    for (JsonNode item : inventory) {
      if (item == null || !item.isObject()) continue;
      int recordedSlot = numericField(item, "slot");
      int recordedItemId = numericField(item, "itemId", "id");
      boolean identityMatches =
          recordedSlot == slot
              && recordedItemId == itemId
              && (!requireStableId
                  || stableId.equals(item.path("stableId").asText(null))
                  || (item.path("stableId").isMissingNode() && expectedStableId.equals(stableId)));
      if (identityMatches) {
        match = item;
        matches++;
      }
    }
    if (matches != 1) {
      violations.add(
          actionViolation(
              matches == 0
                  ? ReplayErrorCode.ACTION_TARGET_UNRESOLVED
                  : ReplayErrorCode.ACTION_AMBIGUOUS,
              action,
              "Inventory target "
                  + (stableId == null ? expectedStableId : stableId)
                  + " resolves to "
                  + matches
                  + " rows at tick "
                  + action.tick(),
              "Retain explicit slot, item id, and stable inventory identity"));
    }
  }

  /** Unique-target lookup: absence and duplication both produce violations. */
  private static JsonNode findUniqueTarget(
      JsonNode collection,
      String stableId,
      List<Violation> violations,
      AcceptedAction action,
      String subject) {
    JsonNode match = null;
    int matches = 0;
    if (collection != null && collection.isArray()) {
      for (JsonNode target : collection) {
        if (target == null || !target.isObject()) continue;
        JsonNode stable = target.get("stableId");
        if (stable != null && stable.isTextual() && stableId.equals(stable.asText())) {
          match = target;
          matches++;
        }
      }
    }
    if (matches == 0) {
      violations.add(
          new Violation(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              subject + " target " + stableId + " not present at its action tick " + action.tick(),
              "Curate against the resource observed at the action tick"));
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
    return match;
  }

  private static void validateNpcIdentityAndPosition(
      JsonNode target, String stableId, AcceptedAction action, List<Violation> violations) {
    int npcId = numericField(target, "npcId", "id");
    if (npcId <= 0) {
      violations.add(
          new Violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "NPC " + stableId + " has no positive numeric npc id at tick " + action.tick(),
              "Record numeric npc ids in the recording pipeline"));
    }
    validateWorldPosition(target, stableId, action, violations, "NPC");
  }

  private static void validateWorldTarget(
      JsonNode target,
      String stableId,
      AcceptedAction action,
      List<Violation> violations,
      String subject,
      String... idFields) {
    if (numericField(target, idFields) <= 0) {
      violations.add(
          new Violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              subject + stableId + " has no positive definition identity at tick " + action.tick(),
              "Record the pinned content definition identity"));
    }
    validateWorldPosition(target, stableId, action, violations, subject);
  }

  private static void validateWorldPosition(
      JsonNode target,
      String stableId,
      AcceptedAction action,
      List<Violation> violations,
      String subject) {
    JsonNode p =
        target.path("position").isObject() ? target.path("position") : target.path("worldPoint");
    if (!p.isObject()
        || !integerField(p, "x")
        || !(integerField(p, "y") || integerField(p, "z"))
        || !integerField(p, "plane")) {
      violations.add(
          new Violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              subject
                  + " "
                  + stableId
                  + " lacks numeric position coordinates and plane at tick "
                  + action.tick(),
              "Record world-point capture for every observed resource"));
    }
  }

  private static void validateOptionAgainstTarget(
      JsonNode target, String option, AcceptedAction action, List<Violation> violations) {
    JsonNode options = target.has("actions") ? target.get("actions") : target.get("options");
    if (options == null || !options.isArray()) return;
    boolean found = false;
    for (JsonNode candidate : options) {
      if (candidate.isTextual() && normalizeOption(candidate.asText()).equals(option)) {
        found = true;
        break;
      }
    }
    if (!found) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_UNSUPPORTED,
              action,
              "Target " + action.targetStableId() + " does not expose option '" + option + "'",
              "Retain the target's recorded server menu options"));
    }
  }

  private static boolean hasRecordedOption(JsonNode target, String option) {
    if (target == null || option == null) return false;
    JsonNode options = target.has("actions") ? target.get("actions") : target.get("options");
    if (options == null || !options.isArray()) return false;
    for (JsonNode candidate : options) {
      if (candidate.isTextual() && normalizeOption(candidate.asText()).equals(option)) return true;
    }
    return false;
  }

  private static String requireStableId(AcceptedAction action, List<Violation> violations) {
    String stableId = action.targetStableId();
    if (stableId == null || stableId.isBlank()) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
              action,
              action.semanticActionType() + " has no stable target id",
              "Capture the stable resource identity at the semantic dispatch boundary"));
      return null;
    }
    return stableId;
  }

  private static String requireOption(AcceptedAction action, List<Violation> violations) {
    String option = extractOption(action.argumentsJson());
    if (option == null || option.isBlank()) {
      violations.add(
          actionViolation(
              ReplayErrorCode.ACTION_UNSUPPORTED,
              action,
              action.semanticActionType() + " has no selected option",
              "Record the selected server menu option"));
      return null;
    }
    return option;
  }

  private static Violation actionViolation(
      ReplayErrorCode code, AcceptedAction action, String message, String remediation) {
    return new Violation(
        code,
        message + " (tick " + action.tick() + ", sequence " + action.sequence() + ")",
        remediation);
  }

  private static Integer integerArgument(JsonNode arguments, String key) {
    if (arguments == null) return null;
    JsonNode value = arguments.get(key);
    return value != null && value.isIntegralNumber() && value.canConvertToInt()
        ? value.asInt()
        : null;
  }

  private static boolean integerField(JsonNode node, String key) {
    return node != null && node.has(key) && node.get(key).isIntegralNumber();
  }

  private static int numericField(JsonNode node, String... fields) {
    if (node == null) return -1;
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value != null && value.isIntegralNumber() && value.canConvertToInt())
        return value.asInt();
    }
    return -1;
  }

  private static String normalizedText(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isTextual() && !value.asText().isBlank()
        ? normalizeOption(value.asText())
        : null;
  }

  private static String normalizeOption(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  /** Option extraction shared by compiler and preflight: argumentsJson.option must be textual. */
  public static String extractOption(JsonNode argumentsJson) {
    if (argumentsJson == null) {
      return null;
    }
    JsonNode option = argumentsJson.get("option");
    return option != null && option.isTextual() ? normalizeOption(option.asText()) : null;
  }

  /** Indexes ordered snapshots by tick for per-action lookups. */
  public static Map<Long, TickSnapshot> indexByTick(List<TickSnapshot> orderedSnapshots) {
    Map<Long, TickSnapshot> byTick = new HashMap<>();
    for (TickSnapshot snap :
        orderedSnapshots == null ? List.<TickSnapshot>of() : orderedSnapshots) {
      if (snap != null && snap.getTick() != null) byTick.putIfAbsent(snap.getTick(), snap);
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
      // A non-blank spotType is a typed claim, not free-form display metadata.  If it is
      // present, it must be one of the options the v1 replay contract can execute.  Omitting the
      // field remains valid for a nearby generic NPC; accepting an unknown value would let a
      // later compiler invent a fishing behavior for an otherwise ambiguous target.
      JsonNode spotType = npc.get("spotType");
      if (spotType != null
          && (!spotType.isTextual()
              || !spotType.asText().isBlank()
                  && !SUPPORTED_SPOT_TYPES.contains(normalizeOption(spotType.asText())))) {
        violations.add(
            new Violation(
                ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
                "NPC " + id + " has unsupported initial spotType " + spotType.asText(),
                "Record a supported fishing spot type or omit spotType for a generic NPC"));
        return violations;
      }
      // Non-fishing NPCs are legitimate nearby capture context. Whether a referenced NPC can be
      // replayed as a generic interaction is checked at its action tick, where its recorded menu
      // options are available; this initial pass must not force a fabricated fishing spot type.
      idx++;
    }
    return violations;
  }
}

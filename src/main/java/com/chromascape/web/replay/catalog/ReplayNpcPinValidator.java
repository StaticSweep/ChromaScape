package com.chromascape.web.replay.catalog;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the NPC identity pins used by the v2 authoritative replay path.
 *
 * <p>The legacy evidence validator deliberately remains unchanged: old recordings may not carry the
 * fields required to recreate an xRSPS NPC. This validator is opt-in and is used only when a v2
 * conversion is certifiable. It validates the targets that can affect the replay, rather than
 * rejecting unrelated nearby NPCs that observed playback may still display.
 */
public final class ReplayNpcPinValidator {

  /** Conservative upper bound for an NPC footprint in the detached xRSPS world. */
  public static final int MAX_NPC_SIZE = 16;

  private static final int MAX_NPC_ID = Integer.MAX_VALUE;
  private static final int MAX_SPOT_ID_LENGTH = 128;

  private ReplayNpcPinValidator() {}

  public record Violation(ReplayErrorCode code, String message, String remediation) {}

  /** A complete identity/content pin for one recorded NPC. */
  private record Pin(int npcId, int npcTypeId, int size, String spotId) {}

  private record Target(String stableId, long tick, Map<String, Object> fields) {}

  /**
   * Validates all occurrences of action targets and the occurrence at each action's own tick.
   * Missing NPCs on ticks where no action targets them are allowed because a real spot may despawn
   * or be temporarily absent; a target must nevertheless be present when it is dispatched.
   */
  public static List<Violation> validate(
      List<TickSnapshot> snapshots,
      ReplayActionConversionResult conversion,
      boolean authoritative) {
    if (!authoritative || snapshots == null || conversion == null || conversion.actions() == null) {
      return List.of();
    }

    Map<String, List<Target>> targetsByStableId = new HashMap<>();
    for (ConvertedReplayAction converted : conversion.actions()) {
      if (converted == null
          || converted.action() == null
          || !isNpcTargetAction(converted.action().getActionType())) {
        continue;
      }
      String stableId = text(converted.action().getTarget(), "stableId");
      if (stableId != null) {
        targetsByStableId
            .computeIfAbsent(stableId, ignored -> new ArrayList<>())
            .add(
                new Target(stableId, converted.action().getTick(), converted.action().getTarget()));
      }
    }
    if (targetsByStableId.isEmpty()) {
      return List.of();
    }

    List<Violation> violations = new ArrayList<>();
    Map<String, Pin> baselinePins = new HashMap<>();
    for (TickSnapshot snapshot : snapshots) {
      if (snapshot == null || snapshot.getNpcs() == null || !snapshot.getNpcs().isArray()) {
        continue;
      }
      Map<String, Integer> counts = new HashMap<>();
      for (JsonNode npc : snapshot.getNpcs()) {
        String stableId = text(npc, "stableId");
        if (stableId == null || !targetsByStableId.containsKey(stableId)) {
          continue;
        }
        int count = counts.merge(stableId, 1, Integer::sum);
        if (count > 1) {
          violations.add(
              violation(
                  ReplayErrorCode.ACTION_AMBIGUOUS,
                  "NPC target "
                      + stableId
                      + " appears more than once at tick "
                      + snapshot.getTick(),
                  "Capture one stable NPC identity per tick"));
          continue;
        }

        Pin pin = readPin(npc, stableId, snapshot.getTick(), violations);
        if (pin == null) {
          continue;
        }
        Pin previous = baselinePins.putIfAbsent(stableId, pin);
        if (previous != null && !previous.equals(pin)) {
          violations.add(
              violation(
                  ReplayErrorCode.ACTION_AMBIGUOUS,
                  "NPC target "
                      + stableId
                      + " changes its authoritative identity/content pin at tick "
                      + snapshot.getTick(),
                  "Keep npcId, npcTypeId, size, and spotId stable for one recorded target"));
        }
      }
    }

    for (Map.Entry<String, List<Target>> entry : targetsByStableId.entrySet()) {
      String stableId = entry.getKey();
      for (Target target : entry.getValue()) {
        TickSnapshot snapshot = findSnapshot(snapshots, target.tick());
        if (snapshot == null) {
          violations.add(
              violation(
                  ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
                  "No recorded snapshot exists at NPC action tick "
                      + target.tick()
                      + " for "
                      + stableId,
                  "Capture a snapshot at every semantic dispatch tick"));
          continue;
        }
        List<JsonNode> matches = matchingNpcs(snapshot, stableId);
        if (matches.isEmpty()) {
          violations.add(
              violation(
                  ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
                  "NPC target " + stableId + " is absent at action tick " + target.tick(),
                  "Capture the referenced NPC at the semantic dispatch tick"));
          continue;
        }
        if (matches.size() != 1) {
          continue; // The per-snapshot pass already emitted the ambiguity finding.
        }
        Pin observed = readPin(matches.get(0), stableId, target.tick(), violations);
        if (observed != null) {
          compareActionPin(target, observed, violations);
        }
      }
    }
    return List.copyOf(violations);
  }

  private static boolean isNpcTargetAction(String actionType) {
    return "INTERACT_NPC".equals(actionType) || "USE_ITEM_ON_NPC".equals(actionType);
  }

  private static TickSnapshot findSnapshot(List<TickSnapshot> snapshots, long tick) {
    for (TickSnapshot snapshot : snapshots) {
      if (snapshot != null && snapshot.getTick() != null && snapshot.getTick() == tick) {
        return snapshot;
      }
    }
    return null;
  }

  private static List<JsonNode> matchingNpcs(TickSnapshot snapshot, String stableId) {
    List<JsonNode> matches = new ArrayList<>();
    if (snapshot.getNpcs() == null || !snapshot.getNpcs().isArray()) {
      return matches;
    }
    for (JsonNode npc : snapshot.getNpcs()) {
      if (stableId.equals(text(npc, "stableId"))) {
        matches.add(npc);
      }
    }
    return matches;
  }

  private static Pin readPin(JsonNode npc, String stableId, Long tick, List<Violation> violations) {
    if (npc == null || !npc.isObject()) {
      violations.add(
          violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "NPC " + stableId + " is not an object at tick " + tick,
              "Capture typed NPC evidence"));
      return null;
    }
    Integer npcId = positiveInt(npc, "npcId", MAX_NPC_ID);
    Integer npcTypeId = positiveInt(npc, "npcTypeId", MAX_NPC_ID);
    Integer size = positiveInt(npc, "size", MAX_NPC_SIZE);
    String spotId = text(npc, "spotId");
    if (npcId == null || npcTypeId == null || size == null || spotId == null) {
      String missing =
          npcId == null
              ? "npcId"
              : npcTypeId == null ? "npcTypeId" : size == null ? "size" : "spotId";
      violations.add(
          violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "NPC "
                  + stableId
                  + " has an invalid or missing authoritative "
                  + missing
                  + " at tick "
                  + tick,
              "Capture positive npcId/npcTypeId, bounded size, and an explicit spotId"));
      return null;
    }
    return new Pin(npcId, npcTypeId, size, spotId);
  }

  private static void compareActionPin(Target target, Pin observed, List<Violation> violations) {
    Map<String, Object> fields = target.fields();
    if (fields == null) return;
    Integer targetNpcId = optionalPositiveInt(fields.get("npcId"), MAX_NPC_ID);
    Integer targetNpcTypeId = optionalPositiveInt(fields.get("npcTypeId"), MAX_NPC_ID);
    Integer targetSize = optionalPositiveInt(fields.get("size"), MAX_NPC_SIZE);
    String targetSpotId = optionalSpotId(fields.get("spotId"));
    if (fields.containsKey("npcId") && targetNpcId == null
        || fields.containsKey("npcTypeId") && targetNpcTypeId == null
        || fields.containsKey("size") && targetSize == null
        || fields.containsKey("spotId") && targetSpotId == null) {
      violations.add(
          violation(
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              "NPC action target " + target.stableId() + " carries an invalid identity pin",
              "Use positive bounded numeric pins and a nonblank explicit spotId"));
      return;
    }
    if (targetNpcId != null && targetNpcId != observed.npcId()
        || targetNpcTypeId != null && targetNpcTypeId != observed.npcTypeId()
        || targetSize != null && targetSize != observed.size()
        || targetSpotId != null && !targetSpotId.equals(observed.spotId())) {
      violations.add(
          violation(
              ReplayErrorCode.ACTION_UNSUPPORTED,
              "NPC action target "
                  + target.stableId()
                  + " does not match its recorded identity pin",
              "Use the exact NPC pin captured at the semantic dispatch boundary"));
    }
  }

  private static Integer positiveInt(JsonNode node, String field, int max) {
    if (node == null || !node.has(field) || !node.get(field).isIntegralNumber()) return null;
    long value = node.get(field).asLong(Long.MIN_VALUE);
    return value > 0 && value <= max ? (int) value : null;
  }

  private static Integer optionalPositiveInt(Object value, int max) {
    if (value == null) return null;
    if (value instanceof Number number
        && number.longValue() == number.doubleValue()
        && number.longValue() > 0
        && number.longValue() <= max) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        long parsed = Long.parseLong(text.trim());
        return parsed > 0 && parsed <= max ? (int) parsed : null;
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static String optionalSpotId(Object value) {
    if (!(value instanceof String text)) return null;
    String trimmed = text.trim();
    return trimmed.isEmpty() || trimmed.length() > MAX_SPOT_ID_LENGTH ? null : trimmed;
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.has(field) || !node.get(field).isTextual()) return null;
    String value = node.get(field).asText().trim();
    return value.isEmpty() || value.length() > MAX_SPOT_ID_LENGTH ? null : value;
  }

  private static String text(Map<String, Object> values, String field) {
    if (values == null) return null;
    Object value = values.get(field);
    return value instanceof String text && !text.isBlank() ? text.trim() : null;
  }

  private static Violation violation(ReplayErrorCode code, String message, String remediation) {
    return new Violation(code, message, remediation);
  }
}

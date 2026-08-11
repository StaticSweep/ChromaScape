package com.chromascape.web.aiplayer.prototype;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts the shared {@link FishingState} feature representation from persisted tick snapshots.
 *
 * <p>Both the dataset builder and the replay runner must use this extractor so that training and
 * prediction share identical features.
 */
public final class FishingStateExtractor {

  private FishingStateExtractor() {}

  /**
   * Extracts a {@link FishingState} from a snapshot.
   *
   * @param snapshot the persisted snapshot
   * @param inventoryTotals tick -> total inventory quantity map for the recording
   * @param initialInventoryTotal the recording's initial inventory total quantity
   * @return the extracted feature state
   */
  public static FishingState extract(
      TickSnapshot snapshot, Map<Long, Integer> inventoryTotals, int initialInventoryTotal) {
    JsonNode playerState = snapshot.getPlayerState();
    int x = intValue(playerState, "worldX", "x");
    int y = intValue(playerState, "worldY", "y", "z");
    int plane = intValue(playerState, "plane");
    JsonNode position = playerState != null ? playerState.get("position") : null;
    if (x < 0) {
      x = intValue(position, "worldX", "x");
    }
    if (y < 0) {
      y = intValue(position, "worldY", "y", "z");
    }
    if (plane < 0) {
      plane = intValue(position, "plane");
    }
    boolean moving = boolValue(playerState, "isMoving", false);
    boolean idle = boolValue(playerState, "isIdle", true);
    int animationId = intValue(playerState, "animation", "animationId");
    if (animationId < 0) {
      animationId = intValue(position, "animation", "animationId");
    }
    if (animationId == 0) {
      animationId = -1;
    }

    JsonNode inventory = snapshot.getInventory();
    int occupiedSlots = inventory != null ? inventory.size() : 0;
    int totalQuantity = 0;
    if (inventory != null) {
      for (JsonNode item : inventory) {
        totalQuantity += intValue(item, "quantity");
      }
    }
    int positiveDeltaFromStart = Math.max(0, totalQuantity - initialInventoryTotal);

    List<FishingState.NearbyNpc> nearbyNpcs = new ArrayList<>();
    JsonNode npcs = snapshot.getNpcs();
    if (npcs != null) {
      for (JsonNode npc : npcs) {
        String name = FishingActionNormalizer.canonicalize(textValue(npc.get("name")));
        if (name == null || !name.contains(FishingActionNormalizer.FISHING_SPOT_TARGET)) {
          continue;
        }
        int npcX = intValue(npc, "x");
        int npcY = intValue(npc, "y");
        int npcPlane = intValue(npc, "plane");
        int distance = Math.abs(npcX - x) + Math.abs(npcY - y);
        nearbyNpcs.add(
            new FishingState.NearbyNpc(name, npcX, npcY, npcPlane, distance));
      }
    }

    FishingState.PlayerFeature player =
        new FishingState.PlayerFeature(x, y, plane, moving, idle, animationId);
    FishingState.InventoryFeature inventoryFeature =
        new FishingState.InventoryFeature(occupiedSlots, totalQuantity, positiveDeltaFromStart);
    return new FishingState(player, inventoryFeature, nearbyNpcs);
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
          // fall through
        }
      }
    }
    return -1;
  }

  private static boolean boolValue(JsonNode node, String key, boolean defaultValue) {
    if (node == null) {
      return defaultValue;
    }
    JsonNode value = node.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isTextual()) {
      return Boolean.parseBoolean(value.asText().trim());
    }
    return defaultValue;
  }

  private static String textValue(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }
}

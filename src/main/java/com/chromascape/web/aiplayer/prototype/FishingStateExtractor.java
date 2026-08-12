package com.chromascape.web.aiplayer.prototype;

import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts the shared {@link FishingState} feature representation from persisted tick snapshots and
 * live tick batches.
 *
 * <p>The dataset builder, the replay runner, and the live shadow service must use this extractor so
 * that training, prediction, and live inference share identical features.
 */
public final class FishingStateExtractor {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private FishingStateExtractor() {}

  /**
   * Extracts a {@link FishingState} from a live tick batch.
   *
   * <p>Both the persisted-snapshot and live entry points delegate to one shared extraction
   * implementation.
   *
   * @param batch the live tick batch
   * @param initialInventoryTotal the live session's initial inventory total quantity baseline
   * @return the extracted feature state
   */
  public static FishingState extract(TickBatchMessage batch, int initialInventoryTotal) {
    JsonNode playerState =
        batch.getPlayerState() != null ? OBJECT_MAPPER.valueToTree(batch.getPlayerState()) : null;
    JsonNode inventory =
        batch.getInventory() != null ? OBJECT_MAPPER.valueToTree(batch.getInventory()) : null;
    JsonNode npcs = batch.getNpcs() != null ? OBJECT_MAPPER.valueToTree(batch.getNpcs()) : null;
    return extract(playerState, inventory, npcs, initialInventoryTotal);
  }

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
    return extract(
        snapshot.getPlayerState(),
        snapshot.getInventory(),
        snapshot.getNpcs(),
        initialInventoryTotal);
  }

  /**
   * Totals the inventory quantity of a live tick batch using the shared quantity parsing rules.
   *
   * <p>The live baseline and state extraction must agree on quantity parsing so that {@code
   * positiveDeltaFromStart} is consistent. Quantities may be integral Java numbers or numeric
   * strings; anything else counts as zero.
   *
   * @param batch the live tick batch
   * @return the summed inventory quantity
   */
  public static int inventoryTotal(TickBatchMessage batch) {
    if (batch == null || batch.getInventory() == null) {
      return 0;
    }
    int total = 0;
    for (Map<String, Object> item : batch.getInventory()) {
      total += quantityValue(item);
    }
    return total;
  }

  private static FishingState extract(
      JsonNode playerState, JsonNode inventory, JsonNode npcs, int initialInventoryTotal) {
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

    int occupiedSlots = inventory != null ? inventory.size() : 0;
    int totalQuantity = 0;
    if (inventory != null) {
      for (JsonNode item : inventory) {
        totalQuantity += quantityValue(item);
      }
    }
    int positiveDeltaFromStart = Math.max(0, totalQuantity - initialInventoryTotal);

    List<FishingState.NearbyNpc> nearbyNpcs = new ArrayList<>();
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
        nearbyNpcs.add(new FishingState.NearbyNpc(name, npcX, npcY, npcPlane, distance));
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

  /** Parses one inventory item quantity with the shared quantity rules. */
  private static int quantityValue(Map<String, Object> item) {
    if (item == null) {
      return 0;
    }
    Object quantity = item.get("quantity");
    if (quantity instanceof Number number) {
      return number.intValue();
    }
    if (quantity instanceof String text) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }

  /** Parses one inventory item quantity from the tree, using the shared quantity rules. */
  private static int quantityValue(JsonNode item) {
    if (item == null) {
      return 0;
    }
    JsonNode quantity = item.get("quantity");
    if (quantity != null && quantity.isNumber()) {
      return quantity.intValue();
    }
    if (quantity != null && quantity.isTextual()) {
      try {
        return Integer.parseInt(quantity.asText().trim());
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
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

package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Feature-level game state used for both dataset examples and replay decision gates.
 *
 * <p>Identifiers, NPC indices, and exact coordinates beyond position are intentionally excluded
 * from semantic features because they change between recordings.
 */
public record FishingState(
    @JsonProperty("player") PlayerFeature player,
    @JsonProperty("inventory") InventoryFeature inventory,
    @JsonProperty("nearbyNpcs") List<NearbyNpc> nearbyNpcs) {

  /** Player position and movement features. */
  public record PlayerFeature(
      @JsonProperty("x") int x,
      @JsonProperty("y") int y,
      @JsonProperty("plane") int plane,
      @JsonProperty("moving") boolean moving,
      @JsonProperty("idle") boolean idle,
      @JsonProperty("animationId") int animationId) {}

  /** Inventory aggregate features. */
  public record InventoryFeature(
      @JsonProperty("occupiedSlots") int occupiedSlots,
      @JsonProperty("totalQuantity") int totalQuantity,
      @JsonProperty("positiveDeltaFromStart") int positiveDeltaFromStart) {}

  /** A visible NPC whose normalized name matches the fishing target. */
  public record NearbyNpc(
      @JsonProperty("name") String name,
      @JsonProperty("x") int x,
      @JsonProperty("y") int y,
      @JsonProperty("plane") int plane,
      @JsonProperty("distance") int distance) {}

  /** Catch progress (fish caught so far) used by the distance formula. */
  public int catchProgress() {
    return inventory.positiveDeltaFromStart();
  }

  /** Returns true when at least one matching fishing spot is visible. */
  public boolean visibleFishingSpotPresent() {
    return nearbyNpcs != null && !nearbyNpcs.isEmpty();
  }
}

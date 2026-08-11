package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the shared feature extractor used by both training and replay. */
class FishingStateExtractorTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private TickSnapshot snapshot(
      int x,
      int y,
      int plane,
      boolean moving,
      boolean idle,
      int animationId,
      List<Map<String, Object>> inventory,
      List<Map<String, Object>> npcs) {
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", x);
    position.put("y", y);
    position.put("z", y);
    position.put("plane", plane);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("position", position);
    playerState.put("worldX", x);
    playerState.put("worldY", y);
    playerState.put("plane", plane);
    playerState.put("isMoving", moving);
    playerState.put("isIdle", idle);
    playerState.put("animationId", animationId);
    TickSnapshot snap =
        new TickSnapshot("debug_1", 100L, LocalDateTime.now(), objectMapper.valueToTree(playerState));
    snap.setInventory(objectMapper.valueToTree(inventory));
    snap.setNpcs(objectMapper.valueToTree(npcs));
    return snap;
  }

  @Test
  @DisplayName("extracts player, inventory, and fishing spot features")
  void extractsFeatures() {
    List<Map<String, Object>> inventory =
        List.of(
            Map.of("slot", 0, "itemId", 303, "quantity", 1),
            Map.of("slot", 1, "itemId", 331, "quantity", 2));
    List<Map<String, Object>> npcs =
        List.of(
            Map.of("name", "<col=ffff00>Fishing spot", "x", 102, "y", 101, "plane", 0),
            Map.of("name", "Goblin", "x", 200, "y", 200, "plane", 0));
    TickSnapshot snap = snapshot(100, 100, 0, false, true, -1, inventory, npcs);

    FishingState state = FishingStateExtractor.extract(snap, Map.of(100L, 3), 1);

    assertThat(state.player().x()).isEqualTo(100);
    assertThat(state.player().y()).isEqualTo(100);
    assertThat(state.player().plane()).isZero();
    assertThat(state.player().moving()).isFalse();
    assertThat(state.player().idle()).isTrue();
    assertThat(state.player().animationId()).isEqualTo(-1);

    assertThat(state.inventory().occupiedSlots()).isEqualTo(2);
    assertThat(state.inventory().totalQuantity()).isEqualTo(3);
    assertThat(state.inventory().positiveDeltaFromStart()).isEqualTo(2);

    assertThat(state.nearbyNpcs()).hasSize(1);
    FishingState.NearbyNpc spot = state.nearbyNpcs().get(0);
    assertThat(spot.name()).isEqualTo("fishing spot");
    assertThat(spot.distance()).isEqualTo(3);
  }

  @Test
  @DisplayName("non-fishing NPCs are excluded from nearby fishing spots")
  void excludesNonFishingNpcs() {
    List<Map<String, Object>> npcs =
        List.of(Map.of("name", "Goblin", "x", 101, "y", 101, "plane", 0));
    TickSnapshot snap = snapshot(100, 100, 0, false, true, -1, List.of(), npcs);

    FishingState state = FishingStateExtractor.extract(snap, Map.of(100L, 0), 0);
    assertThat(state.visibleFishingSpotPresent()).isFalse();
    assertThat(state.nearbyNpcs()).isEmpty();
  }

  @Test
  @DisplayName("animation id zero is treated as inactive")
  void zeroAnimationTreatedAsInactive() {
    TickSnapshot snap = snapshot(100, 100, 0, false, true, 0, List.of(), List.of());
    FishingState state = FishingStateExtractor.extract(snap, Map.of(100L, 0), 0);
    assertThat(state.player().animationId()).isEqualTo(-1);
  }
}

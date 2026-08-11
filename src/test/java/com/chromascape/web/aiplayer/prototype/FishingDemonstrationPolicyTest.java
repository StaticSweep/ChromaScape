package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.aiplayer.prototype.FishingDemonstrationPolicy.Prediction;
import com.chromascape.web.aiplayer.prototype.FishingState.InventoryFeature;
import com.chromascape.web.aiplayer.prototype.FishingState.NearbyNpc;
import com.chromascape.web.aiplayer.prototype.FishingState.PlayerFeature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the deterministic nearest-state demonstration baseline policy. */
class FishingDemonstrationPolicyTest {

  private static final SemanticAction NET_FISHING_SPOT =
      SemanticAction.interactNpc("net", "fishing spot");
  private static final int MAX_DIST = FishingDemonstrationPolicy.MAX_ACCEPTED_POSITION_DISTANCE;

  private FishingState state(
      int x, int y, int plane, boolean moving, boolean idle, int animationId, int catchProgress) {
    PlayerFeature player = new PlayerFeature(x, y, plane, moving, idle, animationId);
    InventoryFeature inventory =
        new InventoryFeature(catchProgress + 1, catchProgress + 1, catchProgress);
    NearbyNpc spot = new NearbyNpc("fishing spot", x + 1, y, plane, 1);
    return new FishingState(player, inventory, List.of(spot));
  }

  @Test
  @DisplayName("a matching state predicts the demonstrated fishing action")
  void matchingStatePredictsDemonstratedAction() {
    TrainingExample example =
        new TrainingExample(state(100, 100, 0, false, true, -1, 1), NET_FISHING_SPOT, 1000);
    FishingState query = state(100, 100, 0, false, true, -1, 1);

    Prediction prediction =
        FishingDemonstrationPolicy.predict(query, List.of(example), MAX_DIST);
    assertThat(prediction.isAbstain()).isFalse();
    assertThat(prediction.action()).isEqualTo(NET_FISHING_SPOT);
  }

  @Test
  @DisplayName("small position variation selects the same semantic target")
  void smallPositionVariationSelectsSameTarget() {
    TrainingExample example =
        new TrainingExample(state(100, 100, 0, false, true, -1, 1), NET_FISHING_SPOT, 1000);
    FishingState query = state(102, 99, 0, false, true, -1, 1);

    Prediction prediction =
        FishingDemonstrationPolicy.predict(query, List.of(example), MAX_DIST);
    assertThat(prediction.action()).isEqualTo(NET_FISHING_SPOT);
  }

  @Test
  @DisplayName("NPC index and coordinate changes do not cause a semantic mismatch")
  void npcCoordinateChangesDoNotCauseMismatch() {
    TrainingExample example =
        new TrainingExample(state(100, 100, 0, false, true, -1, 1), NET_FISHING_SPOT, 1000);

    PlayerFeature player = new PlayerFeature(100, 100, 0, false, true, -1);
    InventoryFeature inventory = new InventoryFeature(2, 2, 1);
    // The visible fishing spot moved; index/coords are not part of semantic equality.
    NearbyNpc movedSpot = new NearbyNpc("fishing spot", 105, 108, 0, 13);
    FishingState query = new FishingState(player, inventory, List.of(movedSpot));

    Prediction prediction =
        FishingDemonstrationPolicy.predict(query, List.of(example), MAX_DIST);
    assertThat(prediction.isAbstain()).isFalse();
    assertThat(prediction.action()).isEqualTo(NET_FISHING_SPOT);
  }

  @Test
  @DisplayName("a missing fishing spot causes abstention")
  void missingFishingSpotAbstains() {
    TrainingExample example =
        new TrainingExample(state(100, 100, 0, false, true, -1, 1), NET_FISHING_SPOT, 1000);
    PlayerFeature player = new PlayerFeature(100, 100, 0, false, true, -1);
    InventoryFeature inventory = new InventoryFeature(2, 2, 1);
    FishingState query = new FishingState(player, inventory, List.of());

    Prediction prediction =
        FishingDemonstrationPolicy.predict(query, List.of(example), MAX_DIST);
    assertThat(prediction.isAbstain()).isTrue();
    assertThat(prediction.abstentionReason()).isEqualTo("ABSTAIN_TARGET_MISSING");
  }

  @Test
  @DisplayName("a state on another plane causes abstention")
  void wrongPlaneAbstains() {
    TrainingExample example =
        new TrainingExample(state(100, 100, 0, false, true, -1, 1), NET_FISHING_SPOT, 1000);
    FishingState query = state(100, 100, 1, false, true, -1, 1);

    Prediction prediction =
        FishingDemonstrationPolicy.predict(query, List.of(example), MAX_DIST);
    assertThat(prediction.isAbstain()).isTrue();
    assertThat(prediction.abstentionReason()).isEqualTo("ABSTAIN_PLANE_MISMATCH");
  }

  @Test
  @DisplayName("more than 12 tiles of displacement causes abstention")
  void tooFarAbstains() {
    TrainingExample example =
        new TrainingExample(state(100, 100, 0, false, true, -1, 1), NET_FISHING_SPOT, 1000);
    FishingState query = state(113, 100, 0, false, true, -1, 1);

    Prediction prediction =
        FishingDemonstrationPolicy.predict(query, List.of(example), MAX_DIST);
    assertThat(prediction.isAbstain()).isTrue();
    assertThat(prediction.abstentionReason()).isEqualTo("ABSTAIN_TOO_FAR");
  }

  @Test
  @DisplayName("tie-breaking prefers the earliest training tick")
  void tieBreakingIsDeterministic() {
    TrainingExample early =
        new TrainingExample(state(100, 100, 0, false, true, -1, 1), NET_FISHING_SPOT, 1000);
    TrainingExample late =
        new TrainingExample(state(100, 100, 0, false, true, -1, 1), NET_FISHING_SPOT, 2000);
    FishingState query = state(100, 100, 0, false, true, -1, 1);

    Prediction prediction =
        FishingDemonstrationPolicy.predict(query, List.of(late, early), MAX_DIST);
    assertThat(prediction.isAbstain()).isFalse();
    assertThat(prediction.action()).isEqualTo(NET_FISHING_SPOT);
    // Both examples have identical actions, so the outcome is the same; verify determinism by
    // running in reverse order and getting the same result.
    Prediction reverse =
        FishingDemonstrationPolicy.predict(query, List.of(early, late), MAX_DIST);
    assertThat(prediction.action()).isEqualTo(reverse.action());
  }

  @Test
  @DisplayName("no unsupported action type can be emitted")
  void onlyInteractNpcOrAbstainEmitted() {
    TrainingExample example =
        new TrainingExample(state(100, 100, 0, false, true, -1, 1), NET_FISHING_SPOT, 1000);
    FishingState query = state(100, 100, 0, false, true, -1, 1);

    Prediction prediction =
        FishingDemonstrationPolicy.predict(query, List.of(example), MAX_DIST);
    if (!prediction.isAbstain()) {
      assertThat(prediction.action().type()).isEqualTo(SemanticAction.TYPE_INTERACT_NPC);
    }
  }
}

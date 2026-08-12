package com.chromascape.web.aiplayer.prototype;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Deterministic nearest-state demonstration baseline policy.
 *
 * <p>Prototype one does not train a neural network. Training persists state/action examples; the
 * policy predicts by finding the lowest-distance demonstrated example, with the documented distance
 * formula and abstention gates. The policy can only emit {@code INTERACT_NPC} or {@code ABSTAIN}.
 */
public final class FishingDemonstrationPolicy {

  public static final String KIND = "FISHING_DEMONSTRATION_BASELINE";
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_ACCEPTED_POSITION_DISTANCE = 12;

  /** Feature weights used by the distance formula. */
  public static final Map<String, Integer> FEATURE_WEIGHTS =
      Map.of(
          "playerManhattanDistance", 2,
          "catchProgressDifference", 4,
          "movingStateMismatch", 3,
          "idleStateMismatch", 3,
          "animationActiveMismatch", 2,
          "planeMismatch", 100);

  /**
   * Prediction output: either a semantic action or an abstention reason, plus diagnostic metadata.
   *
   * <p>{@code policyDistance} and {@code matchedExampleTick} are diagnostic only; they never change
   * the replay pass/fail semantics.
   */
  public record Prediction(
      SemanticAction action,
      String abstentionReason,
      FishingState.NearbyNpc targetNpc,
      Double policyDistance,
      Long matchedExampleTick) {

    public static Prediction abstain(String reason) {
      return new Prediction(null, reason, null, null, null);
    }

    public boolean isAbstain() {
      return abstentionReason != null;
    }
  }

  private FishingDemonstrationPolicy() {}

  /**
   * Predicts one semantic action for a decision state.
   *
   * @param state the current feature state
   * @param examples the demonstration examples
   * @param maxAcceptedPositionDistance the maximum Manhattan distance to any demonstrated state
   * @return the prediction (action or abstention)
   */
  public static Prediction predict(
      FishingState state, List<TrainingExample> examples, int maxAcceptedPositionDistance) {
    if (examples == null || examples.isEmpty()) {
      return Prediction.abstain("ABSTAIN_NO_EXAMPLES");
    }
    if (!state.visibleFishingSpotPresent()) {
      return Prediction.abstain("ABSTAIN_TARGET_MISSING");
    }

    int statePlane = state.player().plane();
    boolean anySamePlane = false;
    int minPlayerDistance = Integer.MAX_VALUE;
    for (TrainingExample example : examples) {
      if (example.state().player().plane() == statePlane) {
        anySamePlane = true;
        int playerDistance = manhattan(example.state(), state);
        if (playerDistance < minPlayerDistance) {
          minPlayerDistance = playerDistance;
        }
      }
    }

    if (!anySamePlane) {
      return Prediction.abstain("ABSTAIN_PLANE_MISMATCH");
    }
    if (minPlayerDistance > maxAcceptedPositionDistance) {
      return Prediction.abstain("ABSTAIN_TOO_FAR");
    }

    TrainingExample best = null;
    double bestDistance = Double.MAX_VALUE;
    for (TrainingExample example : examples) {
      if (example.state().player().plane() != statePlane) {
        continue;
      }
      double distance = distance(example.state(), state);
      if (distance < bestDistance
          || (distance == bestDistance
              && best != null
              && example.sourceTick() < best.sourceTick())) {
        best = example;
        bestDistance = distance;
      }
    }

    if (best == null) {
      return Prediction.abstain("ABSTAIN_PLANE_MISMATCH");
    }
    return new Prediction(best.action(), null, nearestNpc(state), bestDistance, best.sourceTick());
  }

  private static int manhattan(FishingState a, FishingState b) {
    return Math.abs(a.player().x() - b.player().x()) + Math.abs(a.player().y() - b.player().y());
  }

  private static double distance(FishingState a, FishingState b) {
    int playerDistance = manhattan(a, b);
    return Math.min(20, playerDistance) * 2.0
        + Math.abs(a.catchProgress() - b.catchProgress()) * 4.0
        + (a.player().moving() != b.player().moving() ? 3.0 : 0.0)
        + (a.player().idle() != b.player().idle() ? 3.0 : 0.0)
        + (animationActive(a) != animationActive(b) ? 2.0 : 0.0)
        + (a.player().plane() != b.player().plane() ? 100.0 : 0.0);
  }

  private static boolean animationActive(FishingState state) {
    return state.player().animationId() != -1;
  }

  private static FishingState.NearbyNpc nearestNpc(FishingState state) {
    return state.nearbyNpcs().stream()
        .min(Comparator.comparingInt(FishingState.NearbyNpc::distance))
        .orElse(null);
  }
}

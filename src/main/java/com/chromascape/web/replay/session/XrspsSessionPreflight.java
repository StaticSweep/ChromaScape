package com.chromascape.web.replay.session;

import java.util.ArrayList;
import java.util.List;

/**
 * Preflight evaluator for xRSPS session creation. Lists the specific missing actions, content or
 * capture state before a run is created. A reported coverage percentage over captured typed actions
 * never cancels an incomplete-stream warning.
 */
public final class XrspsSessionPreflight {

  /** Stable preflight block codes. Part of the session contract. */
  public enum BlockCode {
    TYPED_ACTION_STREAM_INCOMPLETE,
    UNRESOLVED_TARGET,
    NO_SUPPORTED_FISHING_NPC,
    INITIAL_WORLD_INCOMPLETE,
    UNSUPPORTED_ACTION
  }

  public record Reason(BlockCode code, String detail, String sourceRef) {}

  public record Input(
      String recordingId,
      boolean typedActionStreamComplete,
      double typedActionCoverage,
      List<String> unresolvedTargets,
      boolean supportedFishingNpcInInitialWorld,
      boolean initialWorldComplete,
      List<String> unsupportedActions,
      String sourceRef) {}

  public record Verdict(String recordingId, boolean eligible, List<Reason> reasons) {}

  private XrspsSessionPreflight() {}

  public static Verdict evaluate(Input input) {
    List<Reason> reasons = new ArrayList<>();
    String source = input.sourceRef() == null ? "" : input.sourceRef();
    if (!input.typedActionStreamComplete()) {
      reasons.add(
          new Reason(
              BlockCode.TYPED_ACTION_STREAM_INCOMPLETE,
              "Typed action stream is incomplete despite "
                  + Math.round(input.typedActionCoverage() * 100)
                  + "% coverage of captured typed actions",
              source));
    }
    for (String target : input.unresolvedTargets()) {
      reasons.add(new Reason(BlockCode.UNRESOLVED_TARGET, "Unresolved target: " + target, source));
    }
    if (!input.supportedFishingNpcInInitialWorld()) {
      reasons.add(
          new Reason(
              BlockCode.NO_SUPPORTED_FISHING_NPC,
              "No supported Fishing NPC in the initial world",
              source));
    }
    if (!input.initialWorldComplete()) {
      reasons.add(
          new Reason(
              BlockCode.INITIAL_WORLD_INCOMPLETE,
              "Required initial state cannot be represented",
              source));
    }
    for (String action : input.unsupportedActions()) {
      reasons.add(
          new Reason(BlockCode.UNSUPPORTED_ACTION, "Unsupported action: " + action, source));
    }
    return new Verdict(input.recordingId(), reasons.isEmpty(), List.copyOf(reasons));
  }
}

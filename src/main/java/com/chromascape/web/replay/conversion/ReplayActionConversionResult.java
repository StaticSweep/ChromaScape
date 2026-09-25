package com.chromascape.web.replay.conversion;

import java.util.List;

/** Deterministic, content-addressed conversion output used by preflight and manifest v2. */
public record ReplayActionConversionResult(
    String sessionId,
    long startTick,
    long endTick,
    ConversionMode mode,
    ConversionQuality quality,
    List<ConvertedReplayAction> actions,
    List<ReplayGap> gaps,
    List<ReplayApproximation> approximations,
    int typedActionCount,
    int legacyLogCount,
    double actionCoveragePct,
    String conversionChecksum) {

  public ReplayActionConversionResult {
    actions = List.copyOf(actions == null ? List.of() : actions);
    gaps = List.copyOf(gaps == null ? List.of() : gaps);
    approximations = List.copyOf(approximations == null ? List.of() : approximations);
  }

  public boolean hasExecutableActions() {
    return !actions.isEmpty();
  }
}

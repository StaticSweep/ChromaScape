package com.chromascape.web.replay.catalog.dto;

import java.util.List;

/**
 * Eligibility of a recording for each replay mode, with stable blocking
 * reasons. Codes come from {@link com.chromascape.web.replay.error.ReplayErrorCode}.
 */
public record ReplayEligibility(
        boolean playbackEligible,
        boolean policyReplayEligible,
        boolean simulationEligible,
        List<ReplayBlockingReason> blockingReasons) {}

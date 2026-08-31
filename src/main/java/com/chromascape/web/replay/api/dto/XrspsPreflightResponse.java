package com.chromascape.web.replay.api.dto;

import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.catalog.dto.ReplayBlockingReason;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ReplayApproximation;
import com.chromascape.web.replay.conversion.ReplayGap;
import java.util.List;

/** Read-only v2 conversion and runtime eligibility preview. */
public record XrspsPreflightResponse(
        String schema,
        String recordingId,
        String debugSessionId,
        String preflightState,
        boolean playbackEligible,
        boolean policyReplayEligible,
        boolean simulationEligible,
        boolean certificationEligible,
        ConversionMode conversionMode,
        ConversionQuality conversionQuality,
        double actionCoveragePct,
        int actionCount,
        int typedActionCount,
        int legacyLogCount,
        int gapCount,
        int approximationCount,
        List<ReplayGap> gaps,
        List<ReplayApproximation> approximations,
        List<ReplayBlockingReason> blockingReasons,
        CapabilityManifest capability) {

    public static final String SCHEMA = "xrsps-replay-preflight/v2";

    public XrspsPreflightResponse {
        schema = schema == null ? SCHEMA : schema;
        gaps = List.copyOf(gaps == null ? List.of() : gaps);
        approximations = List.copyOf(approximations == null ? List.of() : approximations);
        blockingReasons = List.copyOf(blockingReasons == null ? List.of() : blockingReasons);
    }
}

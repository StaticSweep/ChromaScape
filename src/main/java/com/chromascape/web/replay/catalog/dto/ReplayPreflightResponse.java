package com.chromascape.web.replay.catalog.dto;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.curation.entity.RecordingCuration;

import java.util.List;

/**
 * Full preflight response combining eligibility, curations, and capability context.
 */
public record ReplayPreflightResponse(
        String recordingId,
        String debugSessionId,
        ReplayEligibility eligibility,
        ReplayMode requestedMode,
        List<RecordingCuration> curations,
        CapabilityManifest capability) {
}
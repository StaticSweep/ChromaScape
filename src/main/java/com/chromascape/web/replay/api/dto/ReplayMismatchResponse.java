package com.chromascape.web.replay.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Stable, read-only projection of one replay mismatch and the evidence that can be used to
 * navigate back to its recorded source. Expected values are the recorded side; actual values are
 * the simulated side.
 */
public record ReplayMismatchResponse(
        String schema,
        int mismatchVersion,
        UUID mismatchId,
        MismatchLevel level,
        String ruleId,
        Long firstTick,
        Integer sequence,
        String domain,
        String jsonPointer,
        JsonNode expected,
        JsonNode actual,
        Integer relatedActionOrdinal,
        UUID checkpointId,
        UUID causalParentId,
        Integer cascadeCount,
        JsonNode sourceEvidence) {

    public static final String SCHEMA = "xrsps-replay-mismatch/v1";

    public ReplayMismatchResponse {
        schema = schema == null ? SCHEMA : schema;
        mismatchVersion = mismatchVersion <= 0 ? 1 : mismatchVersion;
    }
}

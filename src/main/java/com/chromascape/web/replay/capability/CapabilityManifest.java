package com.chromascape.web.replay.capability;

import java.util.List;

/**
 * Immutable capability manifest describing the current replay subsystem
 * capabilities. Returned by GET /api/replay/capabilities.
 */
public record CapabilityManifest(
        int schemaVersion,
        boolean controlAllowed,
        CapabilityModes modes,
        List<Integer> scenarioSchemaVersions,
        List<Integer> semanticActionVersions,
        List<Integer> snapshotVersions,
        List<Integer> projectionVersions,
        List<String> hashVersions,
        XrspsIdentity xrsps,
        boolean openRuneAvailable) {

    public static CapabilityManifest current(
            CapabilityModes modes,
            XrspsIdentity xrsps) {
        return new CapabilityManifest(
                /* schemaVersion */ 1,
                /* controlAllowed */ false,
                modes,
                List.of(1),
                List.of(1),
                List.of(1),
                List.of(1),
                List.of("sha256-canonical-json-v1"),
                xrsps,
                /* openRuneAvailable */ false);
    }

    public record CapabilityModes(
            boolean observedPlayback,
            boolean policyReplay,
            boolean causalSimulation) {}

    public record XrspsIdentity(
            String adapterVersion,
            String buildCommit,
            String cacheRevision,
            String contentRevision) {}
}
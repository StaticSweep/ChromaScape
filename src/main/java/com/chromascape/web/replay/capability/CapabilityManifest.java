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
        boolean openRuneAvailable,
        boolean certifiable,
        String fidelity,
        List<String> supportedActions,
        String viewerTraceSchema) {

    /** Compatibility constructor for callers that only know the original v1 fields. */
    public CapabilityManifest(
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
        this(
                schemaVersion,
                controlAllowed,
                modes,
                scenarioSchemaVersions,
                semanticActionVersions,
                snapshotVersions,
                projectionVersions,
                hashVersions,
                xrsps,
                openRuneAvailable,
                false,
                "SYNTHETIC_CONTRACT",
                List.of(),
                null);
    }

    public CapabilityManifest {
        scenarioSchemaVersions = List.copyOf(scenarioSchemaVersions == null ? List.of() : scenarioSchemaVersions);
        semanticActionVersions = List.copyOf(semanticActionVersions == null ? List.of() : semanticActionVersions);
        snapshotVersions = List.copyOf(snapshotVersions == null ? List.of() : snapshotVersions);
        projectionVersions = List.copyOf(projectionVersions == null ? List.of() : projectionVersions);
        hashVersions = List.copyOf(hashVersions == null ? List.of() : hashVersions);
        supportedActions = List.copyOf(supportedActions == null ? List.of() : supportedActions);
        fidelity = fidelity == null || fidelity.isBlank() ? "SYNTHETIC_CONTRACT" : fidelity;
    }

    public static CapabilityManifest current(
            CapabilityModes modes,
            XrspsIdentity xrsps) {
        return new CapabilityManifest(
                /* schemaVersion */ 1,
                /* controlAllowed */ false,
                modes,
                List.of(1, 2),
                List.of(1),
                List.of(1),
                List.of(1),
                List.of("sha256-canonical-json-v1"),
                xrsps,
                /* openRuneAvailable */ false,
                /* certifiable */ false,
                "SYNTHETIC_CONTRACT",
                List.of("WAIT_UNTIL_TICK", "INTERACT_NPC"),
                "xrsps-replay-trace/v1");
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

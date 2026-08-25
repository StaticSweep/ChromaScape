package com.chromascape.web.replay.manifest.contract;

import com.chromascape.web.replay.compiler.action.SemanticAction;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Immutable scenario manifest compiled from a recording and curation.
 * Serialized as canonical JSON and content-addressed.
 */
public record ScenarioManifestDocument(
        int manifestVersion,
        String manifestId,
        RecordingIdentity recording,
        CurationIdentity curation,
        CompilerIdentity compiler,
        RuntimeIdentity runtime,
        DeterminismSettings determinism,
        JsonNode initialState,
        List<SemanticAction> actions,
        List<JsonNode> expectations,
        JsonNode tolerances,
        boolean controlAllowed,
        String manifestChecksum) {}
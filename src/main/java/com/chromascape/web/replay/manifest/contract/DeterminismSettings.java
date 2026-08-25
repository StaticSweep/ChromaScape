package com.chromascape.web.replay.manifest.contract;

/**
 * Determinism configuration pinned in every manifest.
 */
public record DeterminismSettings(
        String seed,
        String clockOrigin,
        int tickDurationMs,
        String idNamespace) {}
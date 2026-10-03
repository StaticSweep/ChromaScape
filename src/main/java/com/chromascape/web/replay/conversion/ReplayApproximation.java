package com.chromascape.web.replay.conversion;

/** A deterministic action inferred from state evidence rather than a semantic dispatch. */
public record ReplayApproximation(
    long tick, String code, String message, ActionProvenance provenance, Long sourceSnapshotId) {}

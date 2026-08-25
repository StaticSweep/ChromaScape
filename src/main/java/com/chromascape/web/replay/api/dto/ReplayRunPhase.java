package com.chromascape.web.replay.api.dto;

/** Phases within a run lifecycle. */
public enum ReplayRunPhase {
    PREFLIGHT,
    LOAD_MANIFEST,
    START_KERNEL,
    RESET,
    EXECUTE,
    COMPARE,
    FINALIZE_ARTIFACTS,
    FINALIZE_REPORT
}
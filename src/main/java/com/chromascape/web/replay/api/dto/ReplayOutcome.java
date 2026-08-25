package com.chromascape.web.replay.api.dto;

/** Terminal outcome of a replay run. */
public enum ReplayOutcome {
    PASSED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    INFRASTRUCTURE_FAILURE
}
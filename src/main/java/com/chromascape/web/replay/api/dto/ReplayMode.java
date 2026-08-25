package com.chromascape.web.replay.api.dto;

/**
 * The three replay modes. A run must have exactly one mode.
 */
public enum ReplayMode {
    OBSERVED_PLAYBACK,
    POLICY_REPLAY,
    CAUSAL_SIMULATION
}
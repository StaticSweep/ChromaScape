package com.chromascape.web.replay.api.dto;

/** Legal run states. */
public enum ReplayRunState {
    QUEUED,
    CLAIMED,
    STARTING,
    RUNNING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
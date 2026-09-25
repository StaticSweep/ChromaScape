package com.chromascape.web.replay.conversion;

/** A disclosed piece of source evidence that cannot be executed as a replay action. */
public record ReplayGap(
    long tick, String code, String message, ActionProvenance provenance, Long sourceLogId) {}

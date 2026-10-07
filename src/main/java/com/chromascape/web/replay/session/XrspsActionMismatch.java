package com.chromascape.web.replay.session;

import java.util.UUID;

/**
 * First-mismatch link between a recorded expectation and the server-computed outcome. Recorded
 * expectations are reported separately from outcomes; the simulation is never corrected from a
 * later capture to make the view match.
 */
public record XrspsActionMismatch(
    UUID sessionId,
    String commandId,
    long expectedTick,
    long expectedRevision,
    long actualTick,
    long actualRevision,
    XrspsReceiptOutcome outcome,
    String detail) {}

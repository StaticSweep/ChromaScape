package com.chromascape.web.replay.manifest.contract;

/** Identifies the source recording within a manifest. */
public record RecordingIdentity(
    String recordingId,
    String recordingChecksum,
    String debugSessionId,
    String debugSessionChecksum,
    long startTick,
    long endTick) {}

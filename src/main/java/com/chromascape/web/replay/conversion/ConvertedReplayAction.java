package com.chromascape.web.replay.conversion;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;

/** A replay action plus immutable source/provenance metadata. */
public record ConvertedReplayAction(
    RecordingAction action,
    ActionProvenance provenance,
    String provenanceReason,
    Long sourceLogId,
    Long sourceSnapshotId) {}

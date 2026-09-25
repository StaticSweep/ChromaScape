package com.chromascape.web.replay.catalog.dto;

import java.time.LocalDateTime;

/** A single row in the recording catalog. */
public record ReplayRecordingCatalogEntry(
    String recordingId,
    String debugSessionId,
    String accountName,
    String scriptName,
    LocalDateTime startTime,
    LocalDateTime endTime,
    ReplayEligibility eligibility) {}

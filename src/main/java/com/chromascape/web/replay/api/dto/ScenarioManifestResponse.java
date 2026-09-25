package com.chromascape.web.replay.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Response containing a compiled scenario manifest summary. */
public record ScenarioManifestResponse(
    UUID manifestId,
    String recordingId,
    UUID curationId,
    String manifestChecksum,
    String completeness,
    String publishState,
    LocalDateTime createdAt) {}

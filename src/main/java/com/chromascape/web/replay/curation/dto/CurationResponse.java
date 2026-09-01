package com.chromascape.web.replay.curation.dto;

import com.chromascape.web.replay.curation.entity.CurationRole;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Response for a single curation. */
public record CurationResponse(
    UUID curationId,
    String recordingId,
    String debugSessionId,
    int version,
    CurationState state,
    CurationRole role,
    int schemaVersion,
    JsonNode contentJson,
    String contentChecksum,
    String createdBy,
    LocalDateTime createdAt,
    LocalDateTime publishedAt,
    List<CuratedActionDto> actions) {}

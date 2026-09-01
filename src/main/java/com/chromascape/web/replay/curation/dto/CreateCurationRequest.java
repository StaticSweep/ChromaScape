package com.chromascape.web.replay.curation.dto;

import com.chromascape.web.replay.curation.entity.CurationRole;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Request to create a new curation for a recording. */
public record CreateCurationRequest(
    CurationRole role,
    @NotNull long tickRangeStart,
    @NotNull long tickRangeEnd,
    JsonNode initialStateOverrides,
    @Valid List<CuratedActionDto> actionLabels,
    String createdBy) {}

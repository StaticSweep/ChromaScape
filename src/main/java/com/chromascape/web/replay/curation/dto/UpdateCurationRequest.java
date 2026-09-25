package com.chromascape.web.replay.curation.dto;

import com.chromascape.web.replay.curation.entity.CurationRole;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.List;

/**
 * Request to update an existing DRAFT curation. When {@code actionLabels} is non-null it fully
 * replaces the draft label collection.
 */
public record UpdateCurationRequest(
    CurationRole role,
    JsonNode initialStateOverrides,
    @Valid List<CuratedActionDto> actionLabels) {}

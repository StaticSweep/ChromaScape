package com.chromascape.web.replay.curation.dto;

import com.chromascape.web.replay.curation.entity.CurationRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Request to update an existing DRAFT curation.
 */
public record UpdateCurationRequest(
        CurationRole role,
        JsonNode initialStateOverrides,
        List<CuratedActionDto> actionLabels) {}

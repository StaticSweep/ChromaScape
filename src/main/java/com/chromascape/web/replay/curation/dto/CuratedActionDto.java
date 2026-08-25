package com.chromascape.web.replay.curation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * A single curated action label.
 */
public record CuratedActionDto(
        long tick,
        int sequence,
        String status,
        String semanticActionType,
        String targetStableId,
        JsonNode argumentsJson,
        JsonNode evidenceRef,
        BigDecimal confidence) {}

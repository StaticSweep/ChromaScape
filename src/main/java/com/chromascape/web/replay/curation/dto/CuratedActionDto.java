package com.chromascape.web.replay.curation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A single curated action label. Bounds enforce the documented limits so malformed labels fail
 * validation instead of poisoning the checksum basis.
 */
public record CuratedActionDto(
    @Min(0) long tick,
    @Min(0) int sequence,
    String status,
    String semanticActionType,
    @Size(max = 200) String targetStableId,
    JsonNode argumentsJson,
    JsonNode evidenceRef,
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence) {}

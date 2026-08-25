package com.chromascape.web.replay.curation.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Provenance for a specific field in the curation.
 */
public record FieldProvenanceDto(
        String jsonPointer,
        String provenance,
        JsonNode evidenceRef,
        String derivationVersion,
        String suppliedBy,
        String reason) {}
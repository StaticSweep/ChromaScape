package com.chromascape.web.replay.curation.dto;

import com.chromascape.web.replay.curation.entity.CurationRole;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Request to create a new curation for a recording.
 */
public record CreateCurationRequest(
        CurationRole role,
        long tickRangeStart,
        long tickRangeEnd,
        List<CuratedActionDto> actionLabels,
        String createdBy) {}

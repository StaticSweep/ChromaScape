package com.chromascape.web.replay.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request to compile a scenario from a recording and curation. */
public record CompileScenarioRequest(
    @NotBlank String recordingId,
    @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String curationId,
    String seed) {}

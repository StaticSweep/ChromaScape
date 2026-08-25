package com.chromascape.web.replay.run.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to branch from a checkpoint.
 */
public record BranchReplayRunRequest(
        @NotBlank String checkpointId,
        @NotBlank @Size(min = 1, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
        String branchKey,
        @NotBlank @Size(min = 8, max = 128)
        @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
        String idempotencyKey) {}

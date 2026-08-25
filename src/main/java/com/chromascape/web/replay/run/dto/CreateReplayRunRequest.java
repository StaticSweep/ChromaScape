package com.chromascape.web.replay.run.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to create a new replay run.
 */
public record CreateReplayRunRequest(
        @NotNull ReplayMode mode,
        @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "manifestId must be a UUID")
        String manifestId,
        @NotBlank @Size(min = 8, max = 128)
        @Pattern(regexp = "^[A-Za-z0-9._:-]+$", message = "idempotencyKey allows [A-Za-z0-9._:-] only")
        String idempotencyKey,
        ReplayStopPolicy stopPolicy) {}

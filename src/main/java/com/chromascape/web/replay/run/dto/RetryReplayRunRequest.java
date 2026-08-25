package com.chromascape.web.replay.run.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to retry a failed/cancelled/timed-out run. The client supplies
 * the idempotency key so retries remain idempotent.
 */
public record RetryReplayRunRequest(
        @NotBlank @Size(min = 8, max = 128)
        @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
        String idempotencyKey) {}

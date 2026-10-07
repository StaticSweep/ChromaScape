package com.chromascape.web.replay.session;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** A versioned semantic-action submission against one session. */
public record XrspsSessionCommand(
    @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "commandId must be a UUID")
        String commandId,
    @NotNull java.util.UUID sessionId,
    @Min(0) long expectedRevision,
    @NotBlank String actionType,
    @NotBlank String payloadHash,
    @NotNull XrspsCommandSource source,
    @NotBlank String leaseId) {}

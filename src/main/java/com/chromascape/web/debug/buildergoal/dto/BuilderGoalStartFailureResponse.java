package com.chromascape.web.debug.buildergoal.dto;

/** Stable, credential-free error body returned when a Builder Goal start cannot be accepted. */
public record BuilderGoalStartFailureResponse(
    String error,
    String code,
    String phase,
    String details,
    String iterationId,
    String statusUrl,
    String logFile,
    String correlationId) {}

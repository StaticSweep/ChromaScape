package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Public summary of one persisted fishing model artifact.
 *
 * <p>Used by the model listing and activation endpoints. {@code replayValidated} is true only when
 * a persisted {@code PASSED} held-out run exists for the model.
 */
public record FishingModelSummary(
    @JsonProperty("modelId") String modelId,
    @JsonProperty("createdAt") String createdAt,
    @JsonProperty("trainingRecordingId") String trainingRecordingId,
    @JsonProperty("exampleCount") int exampleCount,
    @JsonProperty("replayValidated") boolean replayValidated,
    @JsonProperty("active") boolean active) {}

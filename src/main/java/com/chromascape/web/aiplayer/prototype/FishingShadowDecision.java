package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One live shadow decision produced from a Micro6 tick.
 *
 * <p>A recommendation is data, not an execution command. {@code status} is {@code PREDICTED} for a
 * recommendation or {@code ABSTAINED} for an explicit abstention. For abstentions, {@code action}
 * and {@code targetNpc} are null and {@code abstentionReason} is populated.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FishingShadowDecision(
    @JsonProperty("schemaVersion") int schemaVersion,
    @JsonProperty("type") String type,
    @JsonProperty("status") String status,
    @JsonProperty("modelId") String modelId,
    @JsonProperty("sessionId") String sessionId,
    @JsonProperty("tick") long tick,
    @JsonProperty("observedAt") String observedAt,
    @JsonProperty("action") SemanticAction action,
    @JsonProperty("targetNpc") FishingState.NearbyNpc targetNpc,
    @JsonProperty("abstentionReason") String abstentionReason,
    @JsonProperty("policyDistance") Double policyDistance,
    @JsonProperty("matchedExampleTick") Long matchedExampleTick,
    @JsonProperty("latencyMicros") long latencyMicros) {}

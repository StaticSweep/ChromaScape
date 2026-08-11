package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A demonstration-derived state/action example used by the deterministic fishing policy.
 *
 * @param state the extracted feature state at the decision tick
 * @param action the demonstrated semantic action
 * @param sourceTick the authoritative tick of the decision
 */
public record TrainingExample(
    @JsonProperty("state") FishingState state,
    @JsonProperty("action") SemanticAction action,
    @JsonProperty("sourceTick") long sourceTick) {}

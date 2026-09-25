package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The persisted deterministic fishing model artifact.
 *
 * <p>Loaded from {@code .chromascape/datasets/fishing/models/{modelId}.json} by replay runs.
 */
public record FishingModel(
    @JsonProperty("schemaVersion") int schemaVersion,
    @JsonProperty("modelId") String modelId,
    @JsonProperty("kind") String kind,
    @JsonProperty("createdAt") String createdAt,
    @JsonProperty("trainingDatasetId") String trainingDatasetId,
    @JsonProperty("trainingRecordingId") String trainingRecordingId,
    @JsonProperty("trainingSessionId") String trainingSessionId,
    @JsonProperty("requiredCatchCount") int requiredCatchCount,
    @JsonProperty("actionVocabulary") List<SemanticAction> actionVocabulary,
    @JsonProperty("examples") List<TrainingExample> examples,
    @JsonProperty("featureWeights") Map<String, Object> featureWeights,
    @JsonProperty("maxAcceptedPositionDistance") int maxAcceptedPositionDistance) {}
